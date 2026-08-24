/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Agent.kt  2026-08-24 14:42:39 Changed by gwy
 */

@file:Suppress("RedundantUnitReturnType")

package gradum.agent

import gradum.AgentConfiguration
import gradum.ErrorCode
import gradum.Provider
import gradum.Version
import gradum.client.*
import gradum.debug.*
import gradum.skill.Skill
import gradum.skill.SkillContext
import gradum.skill.SkillRegistry
import gradum.skill.getTodoManagerInstance
import gradum.utils.ContextManager
import gradum.utils.JsonUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("Agent")

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
 */
data class AttachmentPayload(
  val type: String,
  val mime: String?,
  val data: String,
  val filename: String?
)

/**
 * Orchestrates a single user request through one or more LLM turns.
 *
 * Delegates specialized concerns to dedicated managers:
 * - [ConversationHistory] — message list management
 * - [SessionManager] — session lifecycle and events
 * - [SystemPromptLoader] — prompt assembly
 * - [GuardrailManager] — content-safety and repetition detection
 * - [ToolExecutor] — tool call execution and result emission
 *
 * The [executeTask] method remains the single entry point; it drives the
 * agent loop, dispatching tool calls between turns until the model stops
 * requesting tools.
 */
class Agent(
  private val configuration: AgentConfiguration,
  private val emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit,
  private val registerChildSession: ((childSessionId: String, agent: Agent) -> Unit)? = null,
  /**
   * Paired with [registerChildSession]; called when a sub-agent completes
   * or errors so the session hierarchy stays clean.
   */
  private val unregisterChildSession: ((childSessionId: String) -> Unit)? = null,
) {
  private val ollamaClient: OllamaClient = OllamaClient(configuration)
  private val openAiClient: OpenAICompatibleClient = OpenAICompatibleClient(configuration)
  private var activeClient: LlmClient

  private val contextManager: ContextManager = ContextManager(
    contextOutputDirectory(configuration)
  )

  private val conversationHistory: ConversationHistory = ConversationHistory()
  private val sessionManager: SessionManager = SessionManager(configuration, emitEvent)
  private val promptLoader: SystemPromptLoader = SystemPromptLoader(configuration)
  private val guardrailManager: GuardrailManager = GuardrailManager(configuration)
  private val toolExecutor: ToolExecutor = ToolExecutor(
    skillContext = SkillContext(
      emitEvent = emitEvent,
      toolMode = configuration.toolMode,
      provider = configuration.provider,
      agentConfiguration = configuration,
      modelName = configuration.modelName,
      projectRoot = configuration.projectRoot,
      registerChildSession = registerChildSession,
      unregisterChildSession = unregisterChildSession,
      conversationHistory = { conversationHistory.toList() },
    ), sessionManager, configuration,
    conversationHistory,
    emitEvent = emitEvent
  )

  private val maxHistoryMessages: Int = gradum.utils.MAX_HISTORY_MESSAGES

  init {
    activeClient = when (configuration.provider) {
      Provider.OPENAI -> openAiClient
      Provider.OLLAMA -> ollamaClient
    }
    guardrailManager.initializeRedLineKeywords(promptLoader.loadRedLineKeywords())
  }

  internal constructor(
    llmClient: LlmClient,
    configuration: AgentConfiguration,
    redLineKeywords: List<String> = emptyList(),
    emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit,
  ) : this(configuration, emitEvent) {
    activeClient = llmClient
    guardrailManager.initializeRedLineKeywords(redLineKeywords)
  }

  fun executeTask(
    userInput: String,
    toolCallXml: String? = null,
    loadPreviousContext: Boolean = false,
    attachments: List<AttachmentPayload> = emptyList()
  ): Unit {
    sessionManager.reset()
    guardrailManager.reset()
    toolExecutor.reset()

    val contextLoaded: Boolean =
      if (loadPreviousContext) {
        val loadedMessages: List<Map<String, Any>> = contextManager.loadContext()
        conversationHistory.addAll(loadedMessages)
        loadedMessages.isNotEmpty()
      } else false

    conversationHistory.addSystemMessage(promptLoader.load(configuration.taskDescription))

    for (skill: Skill in SkillRegistry.getAllSkills())
      skill.resetHistoryCount()

    getTodoManagerInstance().resetTaskList()

    emitEvent(
      "session_start", mapOf(
        "contextLoaded" to contextLoaded,
        "model" to configuration.modelName,
        "version" to Version.GRADUM_VERSION,
        "think" to configuration.enableThinking,
        "contextMessages" to conversationHistory.size
      )
    )

    conversationHistory.addUserMessage(userInput, attachments)

    if (toolCallXml != null) {
      playToolCallScenario(toolCallXml)
      sessionManager.finish()
      contextManager.saveContext(conversationHistory.toList(), configuration.modelName)
      return
    }

    val toolSchemas: List<Map<String, Any>> = SkillRegistry.getSchemas(
      toolMode = configuration.toolMode,
      provider = configuration.provider,
      modelName = configuration.modelName
    )

    while (true) {
      if (sessionManager.isAborted) break

      conversationHistory.truncate(maxHistoryMessages)

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

      val matchedRedLineKeywords: List<String> = guardrailManager.checkRedLineKeywords(result.responseText)
      if (matchedRedLineKeywords.isNotEmpty()) {
        guardrailManager.recordRedLineHit(matchedRedLineKeywords)
        sessionManager.recordGuardrail(
          type = "red_line_hit",
          hitCount = guardrailManager.redLineHitCount,
          maxAllowed = configuration.maxRedLineHits,
          guardrailDetails = mapOf("keywords" to matchedRedLineKeywords),
        )
        if (guardrailManager.redLineHitCount >= configuration.maxRedLineHits &&
          handleGuardrailExceeded(
            responseText = result.responseText,
            revokeReason = "red_line_violation",
            revokeDetails = mapOf(
              "hitCount" to guardrailManager.redLineHitCount,
              "keywords" to guardrailManager.matchedRedLineKeywords,
            ),
          )
        ) {
          break
        }
      }

      if (guardrailManager.trackRepeatedResponse(result.responseText)) {
        sessionManager.recordGuardrail(
          type = "repeated_response",
          hitCount = guardrailManager.repeatedResponseCount,
          maxAllowed = configuration.maxRepeatedResponses,
          guardrailDetails = mapOf("detail" to (result.responseText ?: "")),
        )
        if (handleGuardrailExceeded(
            responseText = result.responseText,
            revokeReason = "repetitive_loop",
            revokeDetails = mapOf(
              "repeatedCount" to guardrailManager.repeatedResponseCount,
              "responses" to guardrailManager.repeatedResponses,
            ),
          )
        ) {
          break
        }
      }

      if (result.toolCalls.isNullOrEmpty()) {
        result.responseText?.let { text ->
          conversationHistory.addAssistantMessage(text, configuration.modelName, configuration.provider, null)
        }
        break
      }

      val processedCalls: List<ProcessedToolCall> = toolExecutor.prepareToolCalls(result.toolCalls)
      conversationHistory.addAssistantMessage(
        result.responseText ?: "", configuration.modelName, configuration.provider, processedCalls
      )

      for ((index: Int, processedCall: ProcessedToolCall) in processedCalls.withIndex())
        toolExecutor.executeSingleTool(processedCall, isLastToolCall = index == processedCalls.lastIndex)

      if (sessionManager.isAborted) break
    }

    if (configuration.taskDescription == null) {
      sessionManager.finish()
      contextManager.saveContext(conversationHistory.toList(), configuration.modelName)
    }
  }

  fun getResult(): String? = conversationHistory.getResult()

  fun getConversationHistory(): List<Map<String, Any>> = conversationHistory.toList()

  fun isSessionAborted(): Boolean = sessionManager.isAborted

  fun getSessionEndReason(): String? = sessionManager.endReason

  fun abort(reason: String = "user_abort") {
    val tokenUsage = mapOf(
      "promptTokens" to activeClient.tokenUsage.promptTokens,
      "completionTokens" to activeClient.tokenUsage.completionTokens,
      "totalTokens" to activeClient.tokenUsage.totalTokens
    )
    sessionManager.abort(reason, tokenUsage)
  }

  private fun processLlmTurn(toolSchemas: List<Map<String, Any>>): AgentTurnResult {
    val contentParts: MutableList<String> = mutableListOf()
    var toolCallsResult: List<ToolCallEntry>? = null
    var errorMessage: String? = null

    val responseBuffer = StringBuilder()

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
      activeClient.sendChat(conversationHistory.toList(), toolSchemas)

    // TODO(tech-debt): migrate this to a `suspend fun` so the Netty event loop is not
    // blocked while streaming. Not safe to convert until `processLlmTurn` and all 9 skill
    // execute() paths are suspend-clean; tracked as the "Agent.runBlocking removal" item.
    runBlocking {
      responseFlow.collect { chunk ->
        when (chunk) {
          is LLMResponseChunk.TextContent -> {
            contentParts.add(chunk.text)
            responseBuffer.append(chunk.text)
            flushResponse()
          }

          is LLMResponseChunk.ReasoningContent -> {
            flushResponse()
            logger.info(
              "Thinking chunk: len=${chunk.text.length}, preview=${chunk.text.take(50).replace("\n", "\\n")}"
            )
            emitEvent("thinking", mapOf("content" to chunk.text))
          }

          is LLMResponseChunk.ToolCallBatch -> {
            flushResponse()
            toolCallsResult = chunk.toolCalls
          }

          is LLMResponseChunk.ErrorMessage -> {
            flushResponse()
            errorMessage = chunk.description
          }
        }
      }
    }

    flushResponse()

    return AgentTurnResult(
      errorMessage = errorMessage,
      responseText = contentParts.joinToString(""),
      toolCalls = toolCallsResult,
    )
  }

  private fun handleGuardrailExceeded(
    responseText: String?, revokeReason: String, revokeDetails: Map<String, Any>
  ): Boolean {
    responseText?.let { text ->
      conversationHistory.addAssistantMessage(text, configuration.modelName, configuration.provider, null)
    }
    sessionManager.emitRevoked(revokeReason, revokeDetails)
    val tokenUsage = mapOf(
      "promptTokens" to activeClient.tokenUsage.promptTokens,
      "completionTokens" to activeClient.tokenUsage.completionTokens,
      "totalTokens" to activeClient.tokenUsage.totalTokens
    )
    sessionManager.abort(revokeReason, tokenUsage)
    return true
  }

  /**
   * Debug tool-call playback mode. The developer authors a scenario as
   * a short `<tls>` XML block — playing the part of the model — instead
   * of spending LLM tokens. Every listed call runs through the real
   * [toolExecutor] pipeline, streams the same `tool_call` NDJSON events,
   * then writes a machine-readable recording to
   * `<projectRoot>/.gradum/recordings/<scenario>.json`.
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
      if (sessionManager.isAborted) break

      when (step) {
        is ScenarioStep.AiReply -> {
          if (step.content.isNotBlank()) {
            emitEvent(
              "response", mapOf(
                "content" to step.content,
                "totalTokens" to 0,
                "promptTokens" to 0,
                "completionTokens" to 0,
              )
            )
          }
        }

        is ParsedToolCall -> {
          val toolIndex: Int = recordings.size
          val callEntry = ToolCallEntry(
            functionName = step.functionName,
            functionArguments = step.functionArguments,
            callIdentifier = "playback_${toolIndex + 1}"
          )
          val processedCall: ProcessedToolCall = toolExecutor.prepareToolCalls(listOf(callEntry)).first()
          val isLastToolCall: Boolean = recordings.size == toolCalls.lastIndex

          val executionResult: Map<String, Any> = toolExecutor.executeSingleTool(processedCall, isLastToolCall)
          val startedAtMillis: Long = System.currentTimeMillis()
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
            val errorInfo: Map<String, Any> = executionResult["error"] as? Map<String, Any> ?: emptyMap()
            emitEvent(
              "tool_expect_mismatch", mapOf(
                "index" to (stepIndex + 1),
                "tool" to step.functionName,
                "result" to executionResult,
                "actualSuccess" to actualSuccess,
                "expectSuccess" to step.expectSuccess,
                "errorCode" to (errorInfo["code"] ?: ""),
                "errorMessage" to (errorInfo["message"] ?: "")
              )
            )
            mismatches.add(
              mapOf(
                "index" to (stepIndex + 1),
                "tool" to step.functionName,
                "actualSuccess" to actualSuccess,
                "expectSuccess" to step.expectSuccess
              )
            )
          }
        }
      }
    }

    val recordingSummary: Map<String, Any> = mapOf(
      "calls" to recordings,
      "mismatches" to mismatches,
      "scenario" to scenarioName,
      "totalCalls" to toolCalls.size,
      "executedCalls" to recordings.size,
      "mismatchCount" to mismatches.size,
      "recordedAt" to java.time.LocalDateTime.now().toString()
    )

    savePlaybackRecording(scenarioName, recordingSummary)

    emitEvent(
      "playback_end", mapOf(
        "scenario" to scenarioName,
        "executedCalls" to recordings.size,
        "mismatchCount" to mismatches.size
      )
    )
  }

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

  private data class AgentTurnResult(
    val errorMessage: String?,
    val responseText: String?,
    val toolCalls: List<ToolCallEntry>?
  )
}
