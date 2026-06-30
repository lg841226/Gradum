/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Agent.kt  2026-06-30 21:42:45 Changed by gwy
 */

@file:Suppress("RedundantUnitReturnType")

package gradum.agent

import gradum.*
import gradum.PromptVariant.*
import gradum.client.*
import gradum.skill.Skill
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

private val logger: Logger = LoggerFactory.getLogger("Agent")

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

    private val contextManager: ContextManager = ContextManager(ProjectPaths.outputDirectory())

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
    private val maxHistoryMessages: Int = 20

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
        loadPreviousContext: Boolean = false
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

        conversationHistory.add(mapOf("role" to "user", "content" to userInput))

        val toolSchemas: List<Map<String, Any>> = SkillRegistry.getSchemas(toolMode = configuration.toolMode)

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
            } else {
                repeatedResponseTracker.clear()
            }

            if (result.toolCalls.isNullOrEmpty()) {
                result.responseText?.let { text -> appendAssistantMessage(text, null) }
                break
            }

            val processedCalls: List<ProcessedToolCall> = prepareToolCalls(result.toolCalls)
            appendAssistantMessage(result.responseText ?: "", processedCalls)

            for (processedCall in processedCalls)
                executeSingleTool(processedCall)

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
            CLOUD -> "/system_prompt_cloud.txt"
            LOCAL -> "/system_prompt_local.txt"
            AUTO -> "/system_prompt_local.txt"  // unreachable — resolveAuto() never returns AUTO
        }

        val promptContent: String = try {
            Agent::class.java.getResourceAsStream(primaryPath)?.use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("No system prompt found on classpath at $primaryPath")
        } catch (exception: Exception) {
            logger.warn("Could not load system prompt, reason: ${exception.message}")
            "You are a helpful AI assistant. You can't call any tool and report it"
        }

        // Tool surface and workflow guidance must match the active ToolMode —
        // the schema whitelist in SkillRegistry.getSchemas() hides to_do /
        // edit_file / save_file under the smaller modes, so the prompt has to
        // agree or the model will try to call tools that are no longer in
        // its tool list.
        val modeSectionPath: String = when (configuration.toolMode) {
            ToolMode.READ_ONLY -> "/mode_readonly.txt"
            ToolMode.SINGLE_STEP -> "/mode_single_step.txt"
            ToolMode.WRITE -> "/mode_write.txt"
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
        val substitutedContent: String = promptContent
            .replace("{{OS}}", "$osName $osVersion")
            .replace("{{MODE}}", modeSection)

        conversationHistory.add(0, mapOf("role" to "system", "content" to substitutedContent))
    }

    private fun processLlmTurn(toolSchemas: List<Map<String, Any>>): AgentTurnResult {
        val contentParts: MutableList<String> = mutableListOf()
        var toolCallsResult: List<ToolCallEntry>? = null
        var errorMessage: String? = null

        val responseFlow: Flow<LLMResponseChunk> = activeClient.sendChat(conversationHistory, toolSchemas)

        // TODO(tech-debt): migrate this to a `suspend fun` so the Netty event loop is not
        // blocked while streaming. Not safe to convert until `processLlmTurn` and all 9 skill
        // execute() paths are suspend-clean; tracked as the "Agent.runBlocking removal" item.
        runBlocking {
            responseFlow.collect { chunk ->
                when (chunk) {
                    is LLMResponseChunk.TextContent -> {
                        contentParts.add(chunk.text)
                        emitEvent("response", mapOf("content" to chunk.text))
                    }

                    is LLMResponseChunk.ToolCallBatch -> toolCallsResult = chunk.toolCalls
                    is LLMResponseChunk.ReasoningContent -> {
                        emitEvent("thinking", mapOf("content" to chunk.text))
                    }

                    is LLMResponseChunk.ErrorMessage -> errorMessage = chunk.description
                }
            }
        }

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

    private fun executeSingleTool(processedCall: ProcessedToolCall): Unit {
        if (sessionAborted) return

        val functionName: String = processedCall.callData.functionName
        val rawArguments: Map<String, JsonElement> = processedCall.callData.functionArguments

        val convertedArguments: MutableMap<String, Any> = mutableMapOf()
        for ((key: String, value: JsonElement) in rawArguments) {
            val converted: Any? = JsonUtil.fromJsonElement(value)
            if (converted != null)
                convertedArguments[key] = converted
        }

        // Strip any path/root keys the LLM may have slipped in despite the
        // schema not exposing them. `projectRoot` / `project_root` are
        // server-private — the LLM has no business setting them, and if it
        // does we MUST drop them before injecting our own, otherwise the
        // injection no-op `if (!containsKey)` below would leave the LLM's
        // value in place and the tool would resolve against the wrong tree.
        convertedArguments.remove("projectRoot")
        convertedArguments.remove("project_root")

        // Inject the session's project root into every tool call. The root
        // comes from the plugin (via the HTTP request body) — not from any
        // process-level or filesystem-level default — so the server is
        // always acting on exactly the project the IDE has open. Routes
        // validates that `projectRoot` is non-empty before constructing the
        // Agent, so this is never blank in practice.
        convertedArguments["projectRoot"] = configuration.projectRoot

        if (checkToolRunaway(functionName, convertedArguments)) {
            emitRevoked(
                "tool_runaway", mapOf(
                    "tool" to functionName,
                    "arguments" to convertedArguments,
                    "repeatedCount" to repeatedToolCallCount,
                )
            )
            abortSession(sessionStartTimeMillis)
            return
        }

        // Read-only mode guard: the schema whitelist in SkillRegistry hides
        // write tools, but run_cmd is still in the tool list and the model
        // could route a White through it ("touch foo", "rm bar", "git
        // commit"). Re-classify the command against the active toolMode
        // before it reaches RunCommandSkill so the agent loop sees a real
        // COMMAND_BLOCKED instead of a successful mutation.
        if (configuration.toolMode == ToolMode.READ_ONLY && functionName == "run_cmd") {
            val commandText: String = convertedArguments["command"] as? String ?: ""
            val verdict: gradum.utils.CommandVerdict =
                gradum.utils.classifyCommand(commandText, configuration.toolMode)
            if (verdict is gradum.utils.CommandVerdict.Blocked) {
                emitToolResult(
                    processedCall,
                    functionName,
                    convertedArguments,
                    skillInstance = null,
                    executionResult = mapOf(
                        "success" to false,
                        "error" to mapOf(
                            "code" to ErrorCode.COMMAND_BLOCKED.name,
                            "message" to verdict.description,
                            "rule" to verdict.ruleName,
                        ),
                    ),
                )
                return
            }
        }

        val skillInstance: Skill? = SkillRegistry.getSkill(functionName)
        val executionResult: Map<String, Any> = if (skillInstance == null) {
            mapOf(
                "success" to false,
                "error" to mapOf("code" to "SKILL_NOT_FOUND", "message" to "Skill '$functionName' not found"),
            )
        } else if (configuration.toolMode !in skillInstance.allowedToolModes) {
            // Mode gate: each skill declares the ToolMode values it is
            // allowed to run in. The schema whitelist in SkillRegistry
            // hides forbidden skills from the LLM's tool list, but the
            // model can still hallucinate a tool call (it has seen edit_file
            // in training data, and the system prompt mentions it). The
            // agent enforces the invariant here, BEFORE the skill's own
            // execute() runs, so a read-only session physically cannot
            // mutate the project regardless of what the LLM emits.
            val allowedNames: List<String> = skillInstance.allowedToolModes.map { it.name }
            mapOf(
                "success" to false,
                "error" to mapOf(
                    "code" to ErrorCode.TOOL_NOT_PERMITTED.name,
                    "message" to "Tool '${functionName}' is not permitted in ${configuration.toolMode} mode " +
                        "(allowed: ${allowedNames.joinToString(", ")})",
                    "toolMode" to configuration.toolMode.name,
                    "allowedModes" to allowedNames,
                ),
            )
        } else {
            when (val result: SkillResult = skillInstance.execute(convertedArguments)) {
                is SkillResult.Success -> mapOf("success" to true).plus(result.data)
                is SkillResult.Failure -> mapOf(
                    "success" to false,
                    "error" to mapOf("code" to result.code, "message" to result.message)
                )
            }
        }

        emitToolResult(processedCall, functionName, convertedArguments, skillInstance, executionResult)
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

        val todoReminder: String? = getTodoManagerInstance().getTaskReminder()
        val finalResult: String = todoReminder?.let { "$resultString\n\n$it" } ?: resultString

        val toolMessage: Map<String, Any> = if (configuration.provider == Provider.OPENAI) {
            mapOf("role" to "tool", "tool_call_id" to processedCall.callIdentifier, "content" to finalResult)
        } else {
            mapOf("role" to "tool", "content" to finalResult)
        }

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
        if (text.isNullOrBlank()) return emptyList()
        if (redLineKeywords.isEmpty()) return emptyList()
        val lowerText: String = text.lowercase()
        return redLineKeywords.filter { keyword: String ->
            lowerText.contains(keyword.lowercase())
        }
    }

    private fun checkToolRunaway(name: String, args: Map<String, Any>): Boolean {
        val key = "$name|${args.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
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
            if (sentences.size >= 3) {
                val sentenceFreq = sentences.groupingBy { it.lowercase() }.eachCount()
                if (sentenceFreq.values.any { it >= 3 }) return true
            }

            return false
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
