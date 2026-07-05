/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Agent.kt  2026-07-04 20:06:00 Changed by gwy
 */

@file:Suppress("RedundantUnitReturnType")

package gradum.agent

import gradum.*
import gradum.PromptVariant.*
import gradum.client.*
import gradum.skill.Skill
import gradum.skill.SkillContext
import gradum.skill.SkillRegistry
import gradum.skill.getTodoManagerInstance
import gradum.utils.ContextManager
import gradum.utils.JsonUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("Agent")

/**
 * A single user-supplied attachment (currently only `image`) for
 * a chat turn. Agent-side mirror of
 * `gradum.idea.chat.api.GradumApiClient.ApiImageAttachment` and
 * the `AttachmentDto` defined in `gradum.server.Routes`.
 *
 * All three types are structurally identical and field
 * conversions happen at exactly one boundary — `Routes.events`
 * maps `List<AttachmentDto>` → `List<AttachmentPayload>` once,
 * then the wire shape stays untouched all the way to
 * [buildUserMessage] and from there to the multimodal rewriter
 * in [gradum.client.LLMClient]. This avoids a server → agent
 * import cycle (Routes already depends on Agent).
 *
 * The payload is already base64-encoded JPEG bytes — the plugin
 * pipeline normalizes every uploaded image to JPEG at quality
 * 0.85 before serializing, so the server does not need to
 * re-decode.
 */
data class AttachmentPayload(
    val type: String,
    val mime: String?,
    val data: String,
    val filename: String?
)

/**
 * Drives a single user request through one or more LLM turns, dispatching
 * tool calls to [SkillRegistry] between turns until the model stops
 * requesting tools. Streams progress through [emitEvent] as NDJSON.
 */
class Agent(
    private val configuration: AgentConfiguration,
    private val emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit
) {
    private val ollamaClient: OllamaClient = OllamaClient(configuration)
    private val openAiClient: OpenAICompatibleClient = OpenAICompatibleClient(configuration)
    private var activeClient: LlmClient

    /**
     * Per-session ContextManager that stores .gradum output inside the IDE's
     * open project, not the server's CWD.
     *
     * The plugin provides `projectRoot` over HTTP, and Routes validates it
     * before Agent construction, so this is never empty in production.
     *
     * Using ProjectPaths.outputDirectory() would write to the wrong project
     * if the server was launched from a developer workspace instead of the
     * user's target project.
     */
    private val contextManager: ContextManager = ContextManager(
        Path.of(configuration.projectRoot).resolve(".gradum")
    )

    /**
     * Per-session [SkillContext] shared by every [Skill.execute] call.
     *
     * Constructed once from [configuration] and frozen for the lifetime
     * of this Agent — both `toolMode` and `projectRoot` are immutable
     * for the duration of a session, so passing the same instance down
     * means every Skill sees the same values. The previous design
     * relied on `ProjectPaths.setProjectRoot` (a process-global) and
     * gave Skills no way to access `toolMode` at all; this replaces both.
     */
    private val skillContext: SkillContext = SkillContext(
        toolMode = configuration.toolMode,
        projectRoot = configuration.projectRoot,
        provider = configuration.provider,
        modelName = configuration.modelName,
    )

    private val conversationHistory: MutableList<Map<String, Any>> = mutableListOf()
    private val repeatedResponseTracker: MutableList<String> = mutableListOf()
    private val redLineHitKeywords: MutableList<String> = mutableListOf()
    private var redLineKeywords: List<String> = emptyList()

    private var lastToolCallKey: String? = null

    private var toolCallCounter: Int = 0
    private var redLineHitCounter: Int = 0
    private var repeatedToolCallCount: Int = 0
    private var sessionStartTimeMillis: Long = 0L

    private var sessionAborted: Boolean = false

    /** Maximum messages to keep in conversation history sent to LLM. */
    private val maxHistoryMessages: Int = 30

    init {
        activeClient = when (configuration.provider) {
            Provider.OPENAI -> openAiClient
            Provider.OLLAMA -> ollamaClient
        }
        redLineKeywords = loadRedLineKeywords()
    }

    internal constructor(
        configuration: AgentConfiguration,
        emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit, llmClient: LlmClient,
        redLineKeywords: List<String> = emptyList()
    ) : this(configuration, emitEvent) {
        activeClient = llmClient
        this.redLineKeywords = redLineKeywords
    }

    fun executeTask(
        userInput: String,
        loadPreviousContext: Boolean = false,
        attachments: List<AttachmentPayload> = emptyList(),
    ): Unit {
        val startTimeMillis: Long = System.currentTimeMillis().also { sessionStartTimeMillis = it }

        val contextLoaded: Boolean =
            if (loadPreviousContext) {
                val loadedMessages: List<Map<String, Any>> = contextManager.loadContext()
                conversationHistory.addAll(loadedMessages)
                loadedMessages.isNotEmpty()
            } else false

        loadSystemPrompt()

        sessionAborted = false; lastToolCallKey = null; repeatedToolCallCount = 0

        for (skill: Skill in SkillRegistry.getAllSkills())
            skill.resetHistoryCount()

        getTodoManagerInstance().resetTaskList()

        emitEvent(
            "session_start", mapOf(
                "version" to Version.GRADUM_VERSION,
                "model" to configuration.modelName,
                "think" to configuration.enableThinking,
                "contextLoaded" to contextLoaded,
                "contextMessages" to conversationHistory.size
            )
        )

        conversationHistory.add(buildUserMessage(userInput, attachments))

        val toolSchemas: List<Map<String, Any>> = SkillRegistry.getSchemas(
            toolMode = configuration.toolMode,
            provider = configuration.provider,
            modelName = configuration.modelName,
        )

        while (true) {
            if (sessionAborted) break

            truncateHistory()

            val result: AgentTurnResult = processLlmTurn(toolSchemas)

            result.errorMessage?.let { error ->
                emitEvent(
                    "error", mapOf(
                        "code" to ErrorCode.CLIENT_ERROR,
                        "message" to error.trim(),
                        "source" to "${configuration.provider.name.lowercase()}_client"
                    )
                )
            }

            val matchedRedLineKeywords: List<String> = checkRedLineKeywords(result.responseText)
            if (matchedRedLineKeywords.isNotEmpty()) {
                redLineHitCounter++
                redLineHitKeywords.addAll(matchedRedLineKeywords)
                emitEvent(
                    "guardrail", mapOf(
                        "type" to "red_line_hit",
                        "keywords" to matchedRedLineKeywords,
                        "hitCount" to redLineHitCounter,
                        "maxAllowed" to configuration.maxRedLineHits,
                    )
                )
                if (redLineHitCounter >= configuration.maxRedLineHits) {
                    result.responseText?.let { text -> appendAssistantMessage(text, null) }
                    emitRevoked(
                        "red_line_violation", mapOf(
                            "hitCount" to redLineHitCounter,
                            "keywords" to redLineHitKeywords.toList(),
                        )
                    )
                    abortSession(startTimeMillis)
                    break
                }
            }

            val currentResponse: String = (result.responseText ?: "").trim()
            val isDuplicate: Boolean =
                repeatedResponseTracker.isNotEmpty() && currentResponse == repeatedResponseTracker.last()
            if (isDuplicate || isAbnormalResponse(result.responseText)) {
                repeatedResponseTracker.add(currentResponse)
                emitEvent(
                    "guardrail", mapOf(
                        "type" to "repeated_response",
                        "detail" to (result.responseText ?: ""),
                        "repeatedCount" to repeatedResponseTracker.size,
                        "maxAllowed" to configuration.maxRepeatedResponses,
                    )
                )
                if (repeatedResponseTracker.size >= configuration.maxRepeatedResponses) {
                    result.responseText?.let { text -> appendAssistantMessage(text, null) }
                    emitRevoked(
                        "repetitive_loop", mapOf(
                            "repeatedCount" to repeatedResponseTracker.size,
                            "responses" to repeatedResponseTracker.toList(),
                        )
                    )
                    abortSession(startTimeMillis)
                    break
                }
            } else
                repeatedResponseTracker.clear()

            if (result.toolCalls.isNullOrEmpty()) {
                result.responseText?.let { text -> appendAssistantMessage(text, null) }
                break
            }

            val processedCalls: List<ProcessedToolCall> = prepareToolCalls(result.toolCalls)
            appendAssistantMessage(result.responseText ?: "", processedCalls)

            for ((index: Int, processedCall: ProcessedToolCall) in processedCalls.withIndex())
                executeSingleTool(processedCall, isLastToolCall = index == processedCalls.lastIndex)

            if (sessionAborted) break
        }

        finishSession(startTimeMillis)
    }

    private fun loadSystemPrompt() {
        val resolvedVariant: PromptVariant = when (configuration.promptVariant) {
            CLOUD, LOCAL -> configuration.promptVariant
            AUTO -> PromptVariant.resolveAuto(configuration.provider)
        }
        val primaryPath: String = when (resolvedVariant) {
            CLOUD -> "/prompts/system/cloud.xml"
            LOCAL -> "/prompts/system/local.xml"
            AUTO -> "/prompts/system/local.xml"
        }

        val promptContent: String = try {
            Agent::class.java.getResourceAsStream(primaryPath)?.use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("No system prompt found on classpath at $primaryPath")
        } catch (exception: Exception) {
            logger.warn("Could not load system prompt, reason: ${exception.message}")
            "You are a helpful AI assistant. You can't call any tool and report it"
        }

        // Prompt must match ToolMode. SkillRegistry hides certain tools in smaller modes.
        // Otherwise, the model may call tools not in its list.
        val modeSectionPath: String = when (configuration.toolMode) {
            ToolMode.READ_ONLY -> "/prompts/modes/read_only.xml"
            ToolMode.EDIT -> "/prompts/modes/edit.xml"
            ToolMode.AGENT -> "/prompts/modes/agent.xml"
        }
        val modeSection: String = try {
            Agent::class.java.getResourceAsStream(modeSectionPath)?.use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            } ?: "You have no tools available in this session."
        } catch (exception: Exception) {
            logger.warn("Could not load mode section $modeSectionPath: ${exception.message}")
            "You have no tools available in this session."
        }

        val osName: String = System.getProperty("os.name")
        val osVersion: String = System.getProperty("os.version")
        val schemaVariant: SchemaVariant = SchemaVariant.resolve(configuration.modelName)
        val substitutedContent: String = promptContent
            .replace("{{OS}}", "$osName $osVersion")
            .replace("{{MODE}}", modeSection)
            .replace("{{SCHEMA_VARIANT}}", schemaVariant.name)

        // Filter conditional sections based on schemaVariant
        val filteredContent: String = filterConditionalSections(substitutedContent, schemaVariant)

        conversationHistory.add(0, mapOf("role" to "system", "content" to filteredContent))
    }

    /**
     * Filters conditional sections in the prompt based on [SchemaVariant].
     *
     * Sections wrapped in `<!-- if SIMPLE -->...<!-- endif -->` are kept
     * only when [schemaVariant] is [SchemaVariant.SECTIONS]. Sections wrapped in
     * `<!-- if FULL -->...<!-- endif -->` are kept only when [schemaVariant] is [SchemaVariant.FULL].
     *
     * Edge cases:
     * - Nested conditionals are NOT supported (outer block wins)
     * - Malformed tags (missing endif) → section is dropped
     * - Empty sections are preserved (may be intentional)
     *
     * @param content the prompt content with conditional sections
     * @param schemaVariant the active schema variant
     * @return content with only the matching conditional sections
     */
    private fun filterConditionalSections(content: String, schemaVariant: SchemaVariant): String {
        if (content.isBlank()) return content

        val result = StringBuilder()
        val lines = content.lines()
        var i = 0
        var insideConditional = false
        var skipUntilEndif = false

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                // Opening tag: <!-- if SIMPLE --> or <!-- if FULL -->
                !insideConditional && trimmed.startsWith("<!-- if ") && trimmed.endsWith(" -->") -> {
                    val conditionName = trimmed
                        .removePrefix("<!-- if ")
                        .removeSuffix(" -->")
                        .trim()

                    val conditionVariant = try {
                        SchemaVariant.valueOf(conditionName)
                    } catch (_: IllegalArgumentException) {
                        null
                    }

                    insideConditional = true
                    skipUntilEndif = conditionVariant != schemaVariant
                    i++
                }

                // Closing tag: <!-- endif -->
                insideConditional && trimmed == "<!-- endif -->" -> {
                    insideConditional = false
                    skipUntilEndif = false
                    i++
                }

                // Inside conditional block
                insideConditional -> {
                    if (!skipUntilEndif) {
                        result.appendLine(line)
                    }
                    i++
                }

                // Normal line outside conditional
                else -> {
                    result.appendLine(line)
                    i++
                }
            }
        }

        return result.toString().trimEnd()
    }

    private fun processLlmTurn(toolSchemas: List<Map<String, Any>>): AgentTurnResult {
        val contentParts: MutableList<String> = mutableListOf()
        var toolCallsResult: List<ToolCallEntry>? = null
        var errorMessage: String? = null

        // Buffers for accumulating complete blocks before emitting.
        // Each buffer collects consecutive chunks of the same type;
        // when the chunk type changes, the buffer is flushed as a
        // single complete event.
        val thinkingBuffer = StringBuilder()
        val responseBuffer = StringBuilder()

        fun flushThinking() {
            if (thinkingBuffer.isNotEmpty()) {
                emitEvent("thinking", mapOf("content" to thinkingBuffer.toString()))
                thinkingBuffer.clear()
            }
        }

        fun flushResponse() {
            if (responseBuffer.isNotEmpty()) {
                val snapshot = activeClient.tokenUsage
                emitEvent("response", mapOf(
                    "content" to responseBuffer.toString(),
                    "promptTokens" to snapshot.promptTokens,
                    "completionTokens" to snapshot.completionTokens,
                    "totalTokens" to snapshot.totalTokens,
                ))
                responseBuffer.clear()
            }
        }

        val responseFlow: Flow<LLMResponseChunk> =
            activeClient.sendChat(conversationHistory, toolSchemas)

        // TODO(tech-debt): migrate this to a `suspend fun` so the Netty event loop is not
        // blocked while streaming. Not safe to convert until `processLlmTurn` and all 9 skill
        // execute() paths are suspend-clean; tracked as the "Agent.runBlocking removal" item.
        runBlocking {
            responseFlow.collect { chunk ->
                when (chunk) {
                    is LLMResponseChunk.TextContent -> {
                        contentParts.add(chunk.text)
                        flushThinking()           // thinking block ended
                        responseBuffer.append(chunk.text)
                    }

                    is LLMResponseChunk.ReasoningContent -> {
                        flushResponse()           // response block ended
                        thinkingBuffer.append(chunk.text)
                    }

                    is LLMResponseChunk.ToolCallBatch -> {
                        flushThinking()
                        flushResponse()
                        toolCallsResult = chunk.toolCalls
                    }

                    is LLMResponseChunk.ErrorMessage -> {
                        flushThinking()
                        flushResponse()
                        errorMessage = chunk.description
                    }
                }
            }
        }

        // Flush any remaining buffered content at stream end.
        flushThinking(); flushResponse()

        return AgentTurnResult(
            responseText = contentParts.joinToString(""),
            toolCalls = toolCallsResult,
            errorMessage = errorMessage
        )
    }

    private fun prepareToolCalls(rawCalls: List<ToolCallEntry>): List<ProcessedToolCall> {
        return rawCalls.map { call: ToolCallEntry ->
            val existingId: String = call.callIdentifier
            if (existingId.isNotBlank()) {
                ProcessedToolCall(callIdentifier = existingId, callData = call)
            } else {
                toolCallCounter++
                ProcessedToolCall(callIdentifier = "call_$toolCallCounter", callData = call)
            }
        }
    }

    /**
     * Build the user message map that gets appended to
     * [conversationHistory]. The shape is the OpenAI-compatible
     * `content` array intermediate:
     *
     * ```
     * {"role": "user", "content": [
     *   {"type": "text", "text": "What is this?"},
     *   {"type": "image", "data": "<base64>", "mime": "image/jpeg",
     *    "filename": "screenshot.png"},
     *   ...
     * ]}
     * ```
     *
     * `text` always appears first when there are attachments so
     * the LLM prompt reads in the natural top-to-bottom order.
     * When there are no attachments, the message collapses to the
     * old shape `{"role": "user", "content": "..."}` for
     * backwards compatibility with persisted conversation
     * history files and with the per-provider `LLMClient` paths
     * that have not yet learned the array shape.
     *
     * The per-provider clients (`OllamaClient`,
     * `OpenAICompatibleClient`) are responsible for translating
     * the array shape into the wire format each backend expects
     * — see [gradum.client.LLMClient.sendChat].
     */
    private fun buildUserMessage(text: String, attachments: List<AttachmentPayload>): Map<String, Any> {
        if (attachments.isEmpty()) {
            return mapOf("role" to "user", "content" to text)
        }
        val parts: MutableList<Map<String, Any>> = mutableListOf(
            mapOf("type" to "text", "text" to text)
        )
        for (attachment in attachments) {
            // Non-image attachments are ignored (generic envelope for future types).
            // Logged once per request to surface unknown kinds.
            if (attachment.type != "image") {
                logger.warn("Ignoring unsupported attachment type '${attachment.type}' (filename=${attachment.filename})")
                continue
            }
            parts.add(
                mapOf(
                    "type" to "image",
                    "data" to (attachment.data),
                    "mime" to (attachment.mime ?: "image/jpeg"),
                    "filename" to (attachment.filename ?: "")
                )
            )
        }
        return mapOf("role" to "user", "content" to parts)
    }

    private fun appendAssistantMessage(content: String, processedCalls: List<ProcessedToolCall>?): Unit {
        val assistantMessage: Map<String, Any> = if (!processedCalls.isNullOrEmpty()) {
            val toolCallsList: List<Map<String, Any>> = if (configuration.provider == Provider.OPENAI) {
                processedCalls.map { call ->
                    mapOf(
                        "id" to call.callIdentifier,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to call.callData.functionName,
                            "arguments" to Json.encodeToString(
                                serializer<Map<String, JsonElement>>(),
                                call.callData.functionArguments
                            ),
                        ),
                    )
                }
            } else {
                processedCalls.map { call ->
                    mapOf(
                        "function" to mapOf(
                            "name" to call.callData.functionName,
                            "arguments" to call.callData.functionArguments.entries.associate {
                                it.key to JsonUtil.fromJsonElement(it.value)
                            }
                        ),
                    )
                }
            }

            mapOf("role" to "assistant", "content" to content, "tool_calls" to toolCallsList)
        } else {
            mapOf("role" to "assistant", "content" to content)
        }

        conversationHistory.add(assistantMessage)
    }

    private fun executeSingleTool(processedCall: ProcessedToolCall, isLastToolCall: Boolean = true): Unit {
        if (sessionAborted) return

        val functionName: String = processedCall.callData.functionName
        val rawArguments: Map<String, JsonElement> = processedCall.callData.functionArguments

        val convertedArguments: MutableMap<String, Any> = mutableMapOf()
        for ((key: String, value: JsonElement) in rawArguments) {
            val converted: Any? = JsonUtil.fromJsonElement(value)
            if (converted != null)
                convertedArguments[key] = converted
        }

        // Remove any project root keys the LLM might have injected.
        // The server provides the actual root; the LLM must not override it.
        convertedArguments.remove("projectRoot")
        convertedArguments.remove("project_root")

        // Inject the session's project root (from IDE request) into every tool call.
        // Routes guarantee this is non-empty.
        convertedArguments["projectRoot"] = configuration.projectRoot

        logger.info("Skill : $functionName")
        logger.info("Args  : ${JsonUtil.encodeMap(convertedArguments, prettyPrint = true)}")

        val executionResult: Map<String, Any>
        val skillInstance: Skill?

        if (checkToolRunaway(functionName, convertedArguments)) {
            logger.error("Result: TOOL_RUNAWAY — repeated call ($repeatedToolCallCount times), aborting")
            executionResult = emptyMap()
            emitRevoked(
                "tool_runaway", mapOf(
                    "tool" to functionName,
                    "arguments" to convertedArguments,
                    "repeatedCount" to repeatedToolCallCount,
                )
            )
            abortSession(sessionStartTimeMillis)
        } else if (configuration.toolMode == ToolMode.READ_ONLY && functionName == "run_cmd") {
            val commandText: String = convertedArguments["command"] as? String ?: ""
            val verdict: gradum.utils.CommandVerdict =
                gradum.utils.classifyCommand(commandText, configuration.toolMode)

            if (verdict is gradum.utils.CommandVerdict.Blocked) {
                logger.error("  Result: ✗ COMMAND_BLOCKED — ${verdict.description} (rule: ${verdict.ruleName})")
                skillInstance = SkillRegistry.getSkill(functionName)
                executionResult = mapOf(
                    "success" to false,
                    "error" to mapOf(
                        "code" to ErrorCode.COMMAND_BLOCKED.name,
                        "message" to verdict.description,
                        "rule" to verdict.ruleName,
                    ),
                )
                emitToolResult(processedCall, functionName, convertedArguments, skillInstance, executionResult, isLastToolCall)
            } else {
                skillInstance = SkillRegistry.getSkill(functionName)
                executionResult = executeSkill(skillInstance, functionName, convertedArguments)
                emitToolResult(processedCall, functionName, convertedArguments, skillInstance, executionResult, isLastToolCall)
            }
        } else {
            skillInstance = SkillRegistry.getSkill(functionName)
            executionResult = executeSkill(skillInstance, functionName, convertedArguments)
            emitToolResult(processedCall, functionName, convertedArguments, skillInstance, executionResult, isLastToolCall)
        }

        logToolResult(functionName, executionResult)
    }

    private fun executeSkill(
        skillInstance: Skill?,
        functionName: String,
        convertedArguments: Map<String, Any>
    ): Map<String, Any> {
        if (skillInstance == null) {
            logger.error("Result: SKILL_NOT_FOUND — '$functionName' not registered")
            return mapOf(
                "success" to false,
                "error" to mapOf("code" to "SKILL_NOT_FOUND", "message" to "Skill '$functionName' not found"),
            )
        }
        if (configuration.toolMode !in skillInstance.allowedToolModes) {
            val allowedNames: List<String> = skillInstance.allowedToolModes.map { it.name }
            logger.error(
                "Result: TOOL_NOT_PERMITTED — '${functionName}' not allowed in ${configuration.toolMode} (allowed: ${
                    allowedNames.joinToString(
                        ", "
                    )
                })"
            )
            return mapOf(
                "success" to false,
                "error" to mapOf(
                    "code" to ErrorCode.TOOL_NOT_PERMITTED.name,
                    "message" to "Tool '${functionName}' is not permitted in ${configuration.toolMode} mode " +
                        "(allowed: ${allowedNames.joinToString(", ")})",
                    "toolMode" to configuration.toolMode.name,
                    "allowedModes" to allowedNames,
                ),
            )
        }
        return when (val result: SkillResult = skillInstance.execute(convertedArguments, skillContext)) {
            is SkillResult.Success -> mapOf("success" to true).plus(result.data)
            is SkillResult.Failure -> mapOf(
                "success" to false,
                "error" to mapOf("code" to result.code, "message" to result.message)
            )
        }
    }

    private fun logToolResult(functionName: String, executionResult: Map<String, Any>) {
        val callSuccess = executionResult["success"] as? Boolean ?: false
        if (callSuccess) {
            logger.info("Result: success")
        } else {
            @Suppress("UNCHECKED_CAST")

            val errorInfo = executionResult["error"] as? Map<String, Any> ?: emptyMap()
            val errorCode = errorInfo["code"] ?: "UNKNOWN"
            val errorMessage = errorInfo["message"] ?: "No message"
            logger.error("  Result: failed [$errorCode] $errorMessage")
        }
    }

    /**
     * Emit the post-execution events for a tool call (`tool_call`, optional
     * `error`) and append the result to [conversationHistory] so the LLM
     * sees it on the next turn. Shared by the normal skill path and the
     * Read-only guard so both produce an identical agent-loop trace.
     */
    private fun emitToolResult(
        processedCall: ProcessedToolCall,
        functionName: String,
        convertedArguments: Map<String, Any>,
        skillInstance: Skill?,
        executionResult: Map<String, Any>,
        isLastToolCall: Boolean = true,
    ) {
        val historyResult: Map<String, Any> = skillInstance?.prepareHistoryResult(executionResult) ?: executionResult
        val callSuccess: Boolean = executionResult["success"] as? Boolean ?: false
        val toolAlias: String = skillInstance?.alias ?: functionName

        emitEvent(
            "tool_call", mapOf(
                "tool" to functionName,
                "alias" to toolAlias,
                "arguments" to convertedArguments,
                "toolCallId" to processedCall.callIdentifier,
                "success" to callSuccess,
                "result" to executionResult
            )
        )

        if (!callSuccess) {
            @Suppress("UNCHECKED_CAST")
            val errorInfo: Map<String, Any> = executionResult["error"] as? Map<String, Any> ?: emptyMap()
            emitEvent(
                "error", mapOf(
                    "code" to (errorInfo["code"] ?: "EXECUTION_ERROR"),
                    "message" to (errorInfo["message"] ?: "Unknown error"),
                    "tool" to functionName,
                    "toolCallId" to processedCall.callIdentifier
                )
            )
        }

        val resultString: String = JsonUtil.encodeMap(historyResult)

        val todoReminder: String? = if (isLastToolCall) getTodoManagerInstance().getTaskReminder() else null
        val finalResult: String = todoReminder?.let { "$resultString\n\n$it" } ?: resultString

        val toolMessage: Map<String, Any> = if (configuration.provider == Provider.OPENAI) {
            mapOf("role" to "tool", "tool_call_id" to processedCall.callIdentifier, "content" to finalResult)
        } else
            mapOf("role" to "tool", "content" to finalResult)

        conversationHistory.add(toolMessage)
    }

    /**
     * Truncates conversation history to [maxHistoryMessages] messages,
     * keeping the system prompt (index 0) and the most recent messages.
     * This prevents local LLMs from being overwhelmed by long histories.
     */
    private fun truncateHistory() {
        if (conversationHistory.size <= maxHistoryMessages + 1) return
        val systemPrompt = conversationHistory.first()
        val recentMessages = conversationHistory.takeLast(maxHistoryMessages)
        conversationHistory.clear()
        conversationHistory.add(systemPrompt)
        conversationHistory.addAll(recentMessages)
    }

    private fun loadRedLineKeywords(): List<String> {
        return try {
            Agent::class.java.getResourceAsStream("/red_line_keywords.txt")?.use { stream ->
                stream.reader(Charsets.UTF_8).readLines().map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            } ?: run {
                logger.info("red_line_keywords.txt not found on classpath, red line detection disabled")
                emptyList()
            }
        } catch (exception: Exception) {
            logger.warn("Failed to load red_line_keywords.txt: ${exception.message}")
            emptyList()
        }
    }

    private fun checkRedLineKeywords(text: String?): List<String> {
        if (text.isNullOrBlank() || redLineKeywords.isEmpty()) {
            return emptyList()
        }

        return redLineKeywords.filter { keyword: String ->
            text.lowercase().contains(keyword.lowercase())
        }
    }

    /**
     * Detects if the same tool is being called repeatedly with identical arguments.
     * Builds a signature from tool name + sorted args; if it matches the previous call,
     * increments the counter. Returns true when the repeat count exceeds the threshold.
     */
    private fun checkToolRunaway(name: String, toolArguments: Map<String, Any>): Boolean {
        // Build a deterministic key: "toolName|arg1=val1,arg2=val2" (sorted by arg name)
        val key = "$name|${toolArguments.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
        repeatedToolCallCount = if (key == lastToolCallKey) repeatedToolCallCount + 1 else 1
        lastToolCallKey = key
        return repeatedToolCallCount >= configuration.maxRepeatedToolCalls
    }

    private fun emitRevoked(reason: String, details: Map<String, Any>): Unit {
        emitEvent(
            "mission_revoked", mapOf(
                "reason" to reason,
                "details" to details,
            )
        )
    }

    private fun abortSession(startTimeMillis: Long): Unit {
        sessionAborted = true
        val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000
        emitEvent(
            "session_end", mapOf(
                "version" to Version.GRADUM_VERSION,
                "elapsedSeconds" to elapsedSeconds,
                "model" to configuration.modelName,
                "tokenUsage" to mapOf(
                    "promptTokens" to activeClient.tokenUsage.promptTokens,
                    "completionTokens" to activeClient.tokenUsage.completionTokens,
                    "totalTokens" to activeClient.tokenUsage.totalTokens
                ),
                "aborted" to true,
            )
        )
    }

    /**
     * Public method to abort the session from outside (e.g., via POST /stop endpoint).
     * Sets the sessionAborted flag which is checked in the main loop.
     */
    fun abort() {
        sessionAborted = true
    }

    companion object {
        private fun isAbnormalResponse(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val trimmed = text.trim()

            val sentences = trimmed.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 3 }

            return sentences.size >= 3 && sentences.groupingBy {
                it.lowercase()
            }.eachCount().values.any { it >= 3 }
        }
    }

    private fun finishSession(startTimeMillis: Long): Unit {
        if (!sessionAborted) {
            val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000

            emitEvent(
                "session_end", mapOf(
                    "version" to Version.GRADUM_VERSION,
                    "elapsedSeconds" to elapsedSeconds,
                    "model" to configuration.modelName,
                    "tokenUsage" to mapOf(
                        "promptTokens" to activeClient.tokenUsage.promptTokens,
                        "completionTokens" to activeClient.tokenUsage.completionTokens,
                        "totalTokens" to activeClient.tokenUsage.totalTokens
                    ),
                )
            )
        }

        contextManager.saveContext(conversationHistory, configuration.modelName, emptySet())
    }

    private data class AgentTurnResult(
        val responseText: String?,
        val toolCalls: List<ToolCallEntry>?,
        val errorMessage: String?
    )

    data class ProcessedToolCall(
        val callIdentifier: String,
        val callData: ToolCallEntry
    )
}
