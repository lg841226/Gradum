/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Agent.kt  2026-08-16 21:40:13 Changed by gwy
 */

@file:Suppress("RedundantUnitReturnType")

package gradum.agent

import gradum.*
import gradum.PromptVariant.*
import gradum.client.*
import gradum.debug.*
import gradum.skill.Skill
import gradum.skill.SkillContext
import gradum.skill.SkillRegistry
import gradum.skill.getTodoManagerInstance
import gradum.utils.ContextManager
import gradum.utils.JsonUtil
import gradum.utils.MAX_HISTORY_MESSAGES
import gradum.utils.takeLastTurns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("Agent")

private val SENTENCE_SPLIT_PATTERN: Regex = Regex("(?<=[.!?])\\s+")

/**
 * Resolves the ContextManager output directory for an agent run.
 *
 * Single source of truth for the `.gradum/sessions/<sessionId>/` layout:
 * a non-blank `sessionId` scopes this conversation's context file to its
 * own directory; blank falls back to the legacy single
 * `<projectRoot>/.gradum/context.json` directory.
 */
internal fun contextOutputDirectory(configuration: AgentConfiguration): Path {
  val sessionKey: String = configuration.sessionId?.trim().orEmpty()
  val projectRootPath: Path = Path.of(configuration.projectRoot)
  // Reject anything that could escape the sessions directory: path
  // separators (`a/b`, `..`) and the "." / ".." aliases. A malicious
  // sessionId would otherwise let the context file be written anywhere
  // under projectRoot (or outside it, via an absolute path).
  val isSafeSessionKey: Boolean = sessionKey.isNotEmpty() &&
    sessionKey != "." && sessionKey != ".." &&
    !sessionKey.contains('/') && !sessionKey.contains('\\') &&
    !sessionKey.startsWith("~")
  return if (!isSafeSessionKey) {
    projectRootPath.resolve(".gradum")
  } else {
    projectRootPath.resolve(".gradum").resolve("sessions").resolve(sessionKey)
  }
}

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
 * [#buildUserMessage] and from there to the multimodal rewriter
 * in [gradum.client.LlmClient]. This avoids a server → agent
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
   * When the plugin supplies a stable `sessionId`, context is written to
   * `<projectRoot>/.gradum/sessions/<sessionId>/context.json` so model
   * memory is isolated per conversation thread ("New Chat" forgets). A
   * blank sessionId falls back to the legacy single
   * `<projectRoot>/.gradum/context.json` for old-client compatibility.
   *
   * The plugin provides `projectRoot` over HTTP, and Routes validates it
   * before Agent construction, so this is never empty in production.
   *
   * Using ProjectPaths.outputDirectory() would write to the wrong project
   * if the server was launched from a developer workspace instead of the
   * user's target project.
   */
  private val contextManager: ContextManager = ContextManager(
    contextOutputDirectory(configuration)
  )

  /**
   * Per-session [SkillContext] shared by every [Skill.execute] call.
   *
   * Both `toolMode` and `projectRoot` are immutable for the duration of
   * a session, so passing the same instance down means every Skill sees
   * the same values.
   */
  private val skillContext: SkillContext = SkillContext(
    toolMode = configuration.toolMode,
    provider = configuration.provider,
    modelName = configuration.modelName,
    projectRoot = configuration.projectRoot
  )

  private var redLineKeywords: List<String> = emptyList()
  private var redLineKeywordsLowercase: List<String> = emptyList()
  private val redLineHitKeywords: MutableList<String> = mutableListOf()
  private val repeatedResponseTracker: MutableList<String> = mutableListOf()
  private val conversationHistory: MutableList<Map<String, Any>> = mutableListOf()

  private var lastToolCallKey: String? = null

  private var toolCallCounter: Int = 0
  private var redLineHitCounter: Int = 0
  private var repeatedToolCallCount: Int = 0
  private var sessionStartTimeMillis: Long = 0L

  private var sessionAborted: Boolean = false

  private val maxHistoryMessages: Int = MAX_HISTORY_MESSAGES

  init {
    activeClient = when (configuration.provider) {
      Provider.OPENAI -> openAiClient
      Provider.OLLAMA -> ollamaClient
    }
    redLineKeywords = loadRedLineKeywords()
    redLineKeywordsLowercase = redLineKeywords.map { it.lowercase() }
  }

  internal constructor(
    configuration: AgentConfiguration,
    emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit, llmClient: LlmClient,
    redLineKeywords: List<String> = emptyList()
  ) : this(configuration, emitEvent) {
    activeClient = llmClient
    this.redLineKeywords = redLineKeywords
    this.redLineKeywordsLowercase = redLineKeywords.map { it.lowercase() }
  }

  fun executeTask(
    userInput: String,
    loadPreviousContext: Boolean = false,
    toolCallXml: String? = null,
    attachments: List<AttachmentPayload> = emptyList()
  ): Unit {
    sessionStartTimeMillis = System.currentTimeMillis()

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

    if (toolCallXml != null) {
      playToolCallScenario(toolCallXml)
      finishSession()
      return
    }

    val toolSchemas: List<Map<String, Any>> = SkillRegistry.getSchemas(
      modelName = configuration.modelName,
      toolMode = configuration.toolMode,
      provider = configuration.provider
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
        recordGuardrail(
          type = "red_line_hit",
          guardrailDetails = mapOf("keywords" to matchedRedLineKeywords),
          hitCount = redLineHitCounter,
          maxAllowed = configuration.maxRedLineHits,
        )
        if (redLineHitCounter >= configuration.maxRedLineHits &&
          handleGuardrailExceeded(
            responseText = result.responseText,
            revokeReason = "red_line_violation",
            revokeDetails = mapOf(
              "hitCount" to redLineHitCounter,
              "keywords" to redLineHitKeywords.toList(),
            ),
          )
        ) {
          break
        }
      }

      val currentResponse: String = (result.responseText ?: "").trim()
      val isDuplicate: Boolean =
        repeatedResponseTracker.isNotEmpty() && currentResponse == repeatedResponseTracker.last()
      if (isDuplicate || isAbnormalResponse(result.responseText)) {
        repeatedResponseTracker.add(currentResponse)
        recordGuardrail(
          type = "repeated_response",
          guardrailDetails = mapOf("detail" to (result.responseText ?: "")),
          hitCount = repeatedResponseTracker.size,
          maxAllowed = configuration.maxRepeatedResponses,
        )
        if (repeatedResponseTracker.size >= configuration.maxRepeatedResponses &&
          handleGuardrailExceeded(
            responseText = result.responseText,
            revokeReason = "repetitive_loop",
            revokeDetails = mapOf(
              "repeatedCount" to repeatedResponseTracker.size,
              "responses" to repeatedResponseTracker.toList(),
            ),
          )
        ) {
          break
        }
      } else repeatedResponseTracker.clear()

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

    finishSession()
  }

  private fun loadSystemPrompt() {
    val resolvedVariant: PromptVariant = when (configuration.promptVariant) {
      CLOUD, LOCAL -> configuration.promptVariant
      AUTO -> PromptVariant.resolveAuto(configuration.provider)
    }
    val primaryPath: String = when (resolvedVariant) {
      CLOUD -> "/prompts/system/cloud.xml"
      // AUTO is fully resolved to CLOUD/LOCAL above, so this arm is unreachable.
      else -> "/prompts/system/local.xml"
    }

    val promptContent: String = try {
      Agent::class.java.getResourceAsStream(primaryPath)?.use { stream ->
        stream.reader(Charsets.UTF_8).readText()
      } ?: throw IllegalStateException("No system prompt found on classpath at $primaryPath")
    } catch (promptLoadException: Exception) {
      logger.warn("Could not load system prompt, reason: ${promptLoadException.message}", promptLoadException)
      "You are a helpful AI assistant. You can't call any tool and report it"
    }

    val modeSectionPath: String = when (configuration.toolMode) {
      ToolMode.READ_ONLY -> "/prompts/modes/read_only.xml"
      ToolMode.EDIT -> "/prompts/modes/edit.xml"
      ToolMode.AGENT -> "/prompts/modes/agent.xml"
    }
    val modeSection: String = try {
      Agent::class.java.getResourceAsStream(modeSectionPath)?.use { stream ->
        stream.reader(Charsets.UTF_8).readText()
      } ?: "You have no tools available in this session."
    } catch (modeSectionException: Exception) {
      logger.warn("Could not load mode section $modeSectionPath: ${modeSectionException.message}", modeSectionException)
      "You have no tools available in this session."
    }

    val osName: String = System.getProperty("os.name")
    val osVersion: String = System.getProperty("os.version")
    val schemaVariant: SchemaVariant = SchemaVariant.resolve(configuration.modelName)
    val substitutedContent: String = promptContent
      .replace("{{OS}}", "$osName $osVersion")
      .replace("{{MODE}}", modeSection)
      .replace("{{SCHEMA_VARIANT}}", schemaVariant.name)

    val filteredContent: String = filterConditionalSections(substitutedContent, schemaVariant)

    conversationHistory.add(0, mapOf("role" to "system", "content" to filteredContent))
  }

  /**
   * Filters conditional sections in the prompt based on [SchemaVariant].
   *
   * Sections wrapped in `<!-- if SIMPLE -->...<!-- endif -->` are kept
   * only for [SchemaVariant.SIMPLE]; `<!-- if FULL -->` only for
   * [SchemaVariant.FULL].
   *
   * Edge cases: nested conditionals are NOT supported (outer block wins);
   * malformed tags (missing endif) drop the section; empty sections are
   * preserved.
   */
  private fun filterConditionalSections(content: String, schemaVariant: SchemaVariant): String {
    if (content.isBlank()) return content

    val outputBuffer = StringBuilder()
    val contentLines = content.lines()
    var lineIndex = 0
    var insideConditional = false
    var skipUntilEndif = false

    while (lineIndex < contentLines.size) {
      val currentLine = contentLines[lineIndex]
      val trimmedLine = currentLine.trim()

      when {
        // Opening tag: <!-- if SIMPLE --> or <!-- if FULL -->
        !insideConditional && trimmedLine.startsWith("<!-- if ") && trimmedLine.endsWith(" -->") -> {
          val conditionName = trimmedLine
            .removePrefix("<!-- if ")
            .removeSuffix(" -->")
            .trim()

          val conditionVariant = try {
            SchemaVariant.valueOf(conditionName)
          } catch (variantException: IllegalArgumentException) {
            logger.debug("Unknown conditional variant '$conditionName': ${variantException.message}", variantException)
            null
          }

          insideConditional = true
          skipUntilEndif = conditionVariant != schemaVariant
          lineIndex++
        }

        // Closing tag: <!-- endif -->
        insideConditional && trimmedLine == "<!-- endif -->" -> {
          insideConditional = false
          skipUntilEndif = false
          lineIndex++
        }

        // Inside conditional block
        insideConditional -> {
          if (!skipUntilEndif)
            outputBuffer.appendLine(currentLine)
          lineIndex++
        }

        else -> {
          outputBuffer.appendLine(currentLine)
          lineIndex++
        }
      }
    }

    return outputBuffer.toString().trimEnd()
  }

  private fun processLlmTurn(toolSchemas: List<Map<String, Any>>): AgentTurnResult {
    val contentParts: MutableList<String> = mutableListOf()
    var toolCallsResult: List<ToolCallEntry>? = null
    var errorMessage: String? = null

    val thinkingBuffer = StringBuilder()
    val responseBuffer = StringBuilder()

    fun flushThinking() {
      if (thinkingBuffer.isNotEmpty()) {
        emitEvent("thinking", mapOf("content" to thinkingBuffer.toString()))
        thinkingBuffer.clear()
      }
    }

    fun flushResponse() {
      val content: String = responseBuffer.toString()
      responseBuffer.clear()
      val snapshot: TokenUsageSnapshot = activeClient.tokenUsage
      if (content.isNotEmpty() || snapshot.totalTokens > 0) {
        emitEvent(
          "response", mapOf(
            "content" to content,
            "promptTokens" to snapshot.promptTokens,
            "completionTokens" to snapshot.completionTokens,
            "totalTokens" to snapshot.totalTokens
          )
        )
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
            flushThinking()
            responseBuffer.append(chunk.text)
          }

          is LLMResponseChunk.ReasoningContent -> {
            flushResponse()
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
   * Build the user message appended to [conversationHistory].
   *
   * With attachments the `content` becomes an array whose `text` part
   * always comes first so the LLM prompt reads top-to-bottom; without
   * attachments it collapses to the legacy string shape for backwards
   * compatibility with persisted history and per-provider clients.
   */
  private fun buildUserMessage(text: String, attachments: List<AttachmentPayload>): Map<String, Any> {
    if (attachments.isEmpty()) {
      return mapOf("role" to "user", "content" to text)
    }
    val parts: MutableList<Map<String, Any>> = mutableListOf(
      mapOf("type" to "text", "text" to text)
    )
    for ((type, mime, data, filename) in attachments) {
      if (type != "image") {
        logger.warn("Ignoring unsupported attachment type '$type' (filename=$filename)")
        continue
      }
      parts.add(
        mapOf(
          "type" to "image",
          "data" to (data),
          "mime" to (mime ?: "image/jpeg"),
          "filename" to (filename ?: "")
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
    } else mapOf("role" to "assistant", "content" to content)

    conversationHistory.add(assistantMessage)
  }

  private fun executeSingleTool(
    processedCall: ProcessedToolCall,
    isLastToolCall: Boolean = true
  ): Map<String, Any> {
    if (sessionAborted) return emptyMap()

    val functionName: String = processedCall.callData.functionName
    val rawArguments: Map<String, JsonElement> = processedCall.callData.functionArguments

    val convertedArguments: MutableMap<String, Any> = mutableMapOf()
    for ((key: String, value: JsonElement) in rawArguments) {
      val converted: Any? = JsonUtil.fromJsonElement(value)
      if (converted != null) {
        convertedArguments[key] = converted
      }
    }

    // Remove any project root keys the LLM might have injected.
    // The server provides the actual root; the LLM must not override it.
    convertedArguments.remove("projectRoot")
    convertedArguments.remove("project_root")

    // Inject the session's project root (from IDE request) into every tool call.
    // Routes guarantee this is non-empty.
    convertedArguments["projectRoot"] = configuration.projectRoot

    logger.info("Skill: $functionName")
    if (logger.isDebugEnabled) logger.debug("Args: ${truncateToolArguments(convertedArguments)}")

    val executionResult: Map<String, Any>
    val skillInstance: Skill?

    if (checkToolRunaway(functionName, convertedArguments)) {
      logger.error("Result: TOOL_RUNAWAY — repeated call ($repeatedToolCallCount times), aborting")
      emitRevoked(
        "tool_runaway", mapOf(
          "tool" to functionName,
          "arguments" to convertedArguments,
          "repeatedCount" to repeatedToolCallCount,
        )
      )
      abortSession()
      return emptyMap()
    }

    if (configuration.toolMode == ToolMode.READ_ONLY && functionName == "run_cmd") {
      val commandText: String = convertedArguments["command"] as? String ?: ""
      val verdict: gradum.utils.CommandVerdict =
        gradum.utils.classifyCommand(commandText, configuration.toolMode)

      if (verdict is gradum.utils.CommandVerdict.Blocked) {
        logger.error("Result: COMMAND_BLOCKED — ${verdict.description} (rule: ${verdict.ruleName})")
        skillInstance = SkillRegistry.getSkill(functionName)
        executionResult = mapOf(
          "success" to false,
          "error" to mapOf(
            "code" to ErrorCode.COMMAND_BLOCKED.name,
            "message" to verdict.description,
            "rule" to verdict.ruleName,
          ),
        )
        emitToolResult(
          processedCall,
          functionName,
          convertedArguments,
          skillInstance,
          executionResult,
          isLastToolCall
        )
        logToolResult(executionResult)
        return executionResult
      }
    }

    skillInstance = SkillRegistry.getSkill(functionName)
    executionResult = executeSkill(skillInstance, functionName, convertedArguments)

    emitToolResult(
      processedCall,
      functionName,
      convertedArguments,
      skillInstance,
      executionResult,
      isLastToolCall
    )

    logToolResult(executionResult)
    return executionResult
  }

  /**
   * Debug tool-call playback mode.
   *
   * The developer authors a scenario as a short `<tls>` XML block —
   * playing the part of the model — instead of spending LLM tokens.
   * Every listed call runs through the real [executeSingleTool]
   * pipeline, streams the same `tool_call` NDJSON events, then writes a
   * machine-readable recording to
   * `<projectRoot>/.gradum/recordings/<scenario>.json` so results can
   * be diffed between runs.
   *
   * Each tool may carry `exp="success"|"error"` (default `success`);
   * a mismatch surfaces as a `tool_expect_mismatch` event. `<tt>`
   * narration is emitted as `response` events so a compiled `.md`
   * replays as a full agent turn.
   */
  private fun playToolCallScenario(toolCallXml: String): Unit {
    val scenario: ToolCallScenario = try {
      ToolCallScenarioParser.parse(toolCallXml)
    } catch (scenarioException: ToolCallScenarioParseException) {
      emitEvent(
        "error", mapOf(
          "code" to ErrorCode.INVALID_SCENARIO_XML.name,
          "message" to (scenarioException.message ?: "Failed to parse tool-call scenario"),
          "source" to "debug_playback"
        )
      )
      return
    }

    val scenarioName: String = scenario.scenarioName.ifBlank { "playback" }
    val toolCalls: List<ParsedToolCall> = scenario.toolCalls

    emitEvent(
      "playback_start", mapOf(
        "mode" to configuration.toolMode.name,
        "scenario" to scenarioName,
        "steps" to scenario.steps.size,
        "toolCalls" to toolCalls.size,
      )
    )

    val recordings: MutableList<Map<String, Any>> = mutableListOf()
    val mismatches: MutableList<Map<String, Any>> = mutableListOf()

    for ((stepIndex: Int, step: ScenarioStep) in scenario.steps.withIndex()) {
      if (sessionAborted) break

      when (step) {
        is ScenarioStep.AiReply -> {
          if (step.content.isNotBlank()) {
            emitEvent(
              "response", mapOf(
                "content" to step.content,
                "promptTokens" to 0,
                "completionTokens" to 0,
                "totalTokens" to 0
              )
            )
          }
        }

        is ParsedToolCall -> {
          val toolIndex: Int = recordings.size
          val callEntry = ToolCallEntry(
            functionName = step.functionName,
            callIdentifier = "playback_${toolIndex + 1}",
            functionArguments = step.functionArguments,
          )
          val processedCall: ProcessedToolCall = prepareToolCalls(listOf(callEntry)).first()
          val isLastToolCall: Boolean = recordings.size == toolCalls.lastIndex

          val startedAtMillis: Long = System.currentTimeMillis()
          val executionResult: Map<String, Any> = executeSingleTool(processedCall, isLastToolCall)
          val durationMillis: Long = System.currentTimeMillis() - startedAtMillis
          val actualSuccess: Boolean = executionResult["success"] as? Boolean ?: false

          val expectMatched: Boolean = actualSuccess == step.expectSuccess

          recordings.add(
            mapOf(
              "stepIndex" to (stepIndex + 1),
              "tool" to step.functionName,
              "arguments" to step.functionArguments,
              "expectSuccess" to step.expectSuccess,
              "success" to actualSuccess,
              "expectMatched" to expectMatched,
              "durationMs" to durationMillis,
              "result" to executionResult,
            )
          )

          if (!expectMatched) {
            @Suppress("UNCHECKED_CAST")
            val errorInfo: Map<String, Any> =
              executionResult["error"] as? Map<String, Any> ?: emptyMap()
            emitEvent(
              "tool_expect_mismatch", mapOf(
                "tool" to step.functionName,
                "index" to (stepIndex + 1),
                "expectSuccess" to step.expectSuccess,
                "actualSuccess" to actualSuccess,
                "result" to executionResult,
                "errorCode" to (errorInfo["code"] ?: ""),
                "errorMessage" to (errorInfo["message"] ?: ""),
              )
            )
            mismatches.add(
              mapOf(
                "index" to (stepIndex + 1),
                "tool" to step.functionName,
                "expectSuccess" to step.expectSuccess,
                "actualSuccess" to actualSuccess,
              )
            )
          }
        }
      }
    }

    val recordingSummary: Map<String, Any> = mapOf(
      "scenario" to scenarioName,
      "recordedAt" to java.time.LocalDateTime.now().toString(),
      "totalCalls" to toolCalls.size,
      "executedCalls" to recordings.size,
      "mismatchCount" to mismatches.size,
      "mismatches" to mismatches,
      "calls" to recordings,
    )

    savePlaybackRecording(scenarioName, recordingSummary)

    emitEvent(
      "playback_end", mapOf(
        "scenario" to scenarioName,
        "executedCalls" to recordings.size,
        "mismatchCount" to mismatches.size,
      )
    )
  }

  /**
   * Writes a debug playback recording to
   * `<project>/.gradum/recordings/<scenario>-<timestamp>.json`, inside
   * the user's project like [contextManager]'s output.
   */
  private fun savePlaybackRecording(scenarioName: String, summary: Map<String, Any>) {
    try {
      val recordingsDir: Path = Path.of(configuration.projectRoot, ".gradum", "recordings")
      Files.createDirectories(recordingsDir)
      val safeName: String = scenarioName.replace(Regex("[^A-Za-z0-9._-]"), "_")
      val recordingFile: Path = recordingsDir.resolve(
        "${safeName}-${System.currentTimeMillis()}.json"
      )
      Files.writeString(recordingFile, JsonUtil.encodeMap(summary, prettyPrint = true))
      logger.info("Playback recording written to $recordingFile")
    } catch (recordingException: Exception) {
      logger.error("Failed to write playback recording", recordingException)
    }
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
    if (!skillInstance.allows(configuration.toolMode)) {
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

  private fun logToolResult(executionResult: Map<String, Any>) {
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
   * `error`) and append the result to [conversationHistory]. Shared by
   * the normal skill path and the Read-only guard so both produce an
   * identical agent-loop trace.
   *
   * The result is routed through [Skill.recordAndCompactHistory], which
   * strips [Skill.historyVolatileKeys] from this skill's OLDER tool
   * messages while returning the current call's history-stamped version.
   */
  private fun emitToolResult(
    processedCall: ProcessedToolCall,
    functionName: String,
    convertedArguments: Map<String, Any>,
    skillInstance: Skill?,
    executionResult: Map<String, Any>,
    isLastToolCall: Boolean = true,
  ) {
    val toolAlias: String = skillInstance?.alias ?: functionName
    val ownMessageIndices: List<Int> = if (skillInstance == null) emptyList()
    else conversationHistory.withIndex()
      .filter { (_, entry: Map<String, Any>) ->
        (entry["role"] as? String) == "tool" && (entry["alias"] as? String) == toolAlias
      }.map { (index: Int, _: Map<String, Any>) -> index }

    val historyResult: Map<String, Any> = skillInstance?.recordAndCompactHistory(
      executionResult, conversationHistory, ownMessageIndices
    ) ?: executionResult
    val callSuccess: Boolean = executionResult["success"] as? Boolean ?: false

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

    // Tag tool messages with `alias` so subsequent calls can identify
    // this skill's prior messages for [Skill.compactHistory] filtering.
    val toolMessage: Map<String, Any> = buildMap {
      put("role", "tool")
      put("alias", toolAlias)
      put("content", finalResult)
      if (configuration.provider == Provider.OPENAI) {
        put("tool_call_id", processedCall.callIdentifier)
      }
    }

    conversationHistory.add(toolMessage)
  }

  /**
   * Truncates conversation history to [maxHistoryMessages] messages,
   * keeping the system prompt and the most recent messages.
   *
   * Truncation is **turn-aware**: a "turn" is a user message, OR an
   * assistant message together with all its following `role=tool`
   * results. Cuts only happen on turn boundaries so the truncated list
   * never contains an assistant message whose `tool_calls` reference
   * dropped tool results.
   *
   * If one whole turn is larger than the budget, the truncator falls
   * back to a raw tail cut within the most recent turn and logs a
   * warning so the operator can raise the budget.
   */
  private fun truncateHistory() {
    val systemPrompt: Map<String, Any>? =
      conversationHistory.firstOrNull { (it["role"] as? String) == "system" }
    val nonSystem: List<Map<String, Any>> =
      conversationHistory.filter { (it["role"] as? String) != "system" }

    if (nonSystem.size <= maxHistoryMessages) return

    val preSize: Int = nonSystem.size
    val keepFromEnd: List<Map<String, Any>> = takeLastTurns(nonSystem, maxHistoryMessages)
    if (keepFromEnd.size in (maxHistoryMessages + 1)..<preSize) {
      logger.warn(
        "Cannot fit conversation in $maxHistoryMessages messages " +
          "without breaking a turn; falling back to raw tail cut " +
          "(non-system size = $preSize)"
      )
    }

    conversationHistory.clear()
    if (systemPrompt != null) conversationHistory.add(systemPrompt)
    conversationHistory.addAll(keepFromEnd)
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
    } catch (keywordLoadException: Exception) {
      logger.warn("Failed to load red_line_keywords.txt: ${keywordLoadException.message}", keywordLoadException)
      emptyList()
    }
  }

  private fun checkRedLineKeywords(text: String?): List<String> {
    if (text.isNullOrBlank() || redLineKeywords.isEmpty()) {
      return emptyList()
    }

    val lowercasedText: String = text.lowercase()
    return redLineKeywords.filterIndexed { index: Int, _: String ->
      lowercasedText.contains(redLineKeywordsLowercase[index])
    }
  }

  private fun checkToolRunaway(toolName: String, toolArguments: Map<String, Any>): Boolean {
    val callSignature =
      "$toolName|${toolArguments.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
    repeatedToolCallCount = if (callSignature == lastToolCallKey) repeatedToolCallCount + 1 else 1
    lastToolCallKey = callSignature
    return repeatedToolCallCount >= configuration.maxRepeatedToolCalls
  }

  private fun truncateToolArguments(toolArguments: Map<String, Any>): String {
    val maxValueLength = 512
    val serialized: String = JsonUtil.encodeMap(toolArguments, prettyPrint = true)
    return if (serialized.length <= maxValueLength) serialized
    else serialized.take(maxValueLength) + "... [truncated, ${serialized.length} chars total]"
  }

  private fun emitRevoked(reason: String, details: Map<String, Any>): Unit {
    emitEvent(
      "mission_revoked", mapOf(
        "reason" to reason,
        "details" to details,
      )
    )
  }

  /**
   * Emits a `guardrail` event. Shared by the red-line and repeated-response
   * escalations so both hit the same wire shape.
   */
  private fun recordGuardrail(
    type: String,
    guardrailDetails: Map<String, Any>,
    hitCount: Int,
    maxAllowed: Int,
  ): Unit {
    emitEvent(
      "guardrail",
      mapOf(
        "type" to type,
        "hitCount" to hitCount,
        "maxAllowed" to maxAllowed,
      ) + guardrailDetails,
    )
  }

  /**
   * Escalates an exhausted guardrail: persist the offending assistant
   * message, emit `mission_revoked`, and abort the session. Returns true
   * so the caller can `break` out of the agent loop.
   */
  private fun handleGuardrailExceeded(
    responseText: String?,
    revokeReason: String,
    revokeDetails: Map<String, Any>,
  ): Boolean {
    responseText?.let { text -> appendAssistantMessage(text, null) }
    emitRevoked(revokeReason, revokeDetails)
    abortSession()
    return true
  }

  private fun abortSession(): Unit {
    sessionAborted = true
    val elapsedSeconds: Long = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000

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

  /** Aborts the session from outside (e.g. via POST /stop). */
  fun abort() {
    // Must emit session_end (with aborted=true) ourselves: finishSession()
    // skips its emission once sessionAborted is set, so without this the
    // plugin would wait forever for the stream terminator and stay stuck
    // in "sending…".
    abortSession()
  }

  companion object {
    private fun isAbnormalResponse(responseText: String?): Boolean {
      if (responseText.isNullOrBlank()) return false
      val trimmedText = responseText.trim()

      val sentenceList = trimmedText.split(SENTENCE_SPLIT_PATTERN)
        .map { it.trim() }
        .filter { it.isNotBlank() && it.length > 3 }

      return sentenceList.size >= 3 && sentenceList.groupingBy {
        it.lowercase()
      }.eachCount().values.any { it >= 3 }
    }
  }

  private fun finishSession(): Unit {
    if (!sessionAborted) {
      val elapsedSeconds: Long = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000

      emitEvent(
        "session_end", mapOf(
          "version" to Version.GRADUM_VERSION,
          "elapsedSeconds" to elapsedSeconds,
          "model" to configuration.modelName,
        )
      )
    }

    contextManager.saveContext(conversationHistory, configuration.modelName)
  }

  private data class AgentTurnResult(
    val responseText: String?,
    val toolCalls: List<ToolCallEntry>?,
    val errorMessage: String?
  )

  data class ProcessedToolCall(
    val callIdentifier: String, val callData: ToolCallEntry
  )
}
