@file:Suppress("RedundantUnitReturnType")

package gradum.agent

import gradum.AgentConfiguration
import gradum.ErrorCode
import gradum.Provider
import gradum.Version
import gradum.client.*
import gradum.debug.*
import gradum.mcp.McpToolCatalog
import gradum.skill.*
import gradum.skill.builtin.getTodoManagerInstance
import gradum.skill.dsl.buildXml
import gradum.task.BackgroundTaskManager
import gradum.task.JobOutcome
import gradum.task.JobStatus
import gradum.utils.ContextManager
import gradum.utils.JsonUtil
import gradum.utils.MAX_HISTORY_MESSAGES
import gradum.utils.trimmedOutputLines
import gradum.vcs.SessionSnapshotManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("Agent")

private const val REPEATED_LOOP_REASON: String = "repetitive_loop"

internal fun contextOutputDirectory(configuration: AgentConfiguration): Path {
  val sessionKey: String = configuration.sessionId?.trim().orEmpty()
  val projectRootPath: Path = Path.of(configuration.projectRoot)
  val isSafeSessionKey: Boolean = sessionKey.isNotEmpty() &&
    sessionKey != "." && sessionKey != ".." &&
    !sessionKey.contains(char = '/') && !sessionKey.contains(char = '\\') &&
    !sessionKey.startsWith(prefix = "~")
  return if (!isSafeSessionKey) {
    projectRootPath.resolve(".gradum")
  } else {
    projectRootPath.resolve(".gradum").resolve("sessions").resolve(sessionKey)
  }
}

/**
 * A single user-supplied attachment for a chat turn. For `file` attachments
 * the plugin renders the model-facing reference tag itself and ships it as
 * [attachmentText]; the server inserts it verbatim and only reads the
 * remaining fields to build display metadata. The server never parses or
 * formats the tag.
 */
data class AttachmentPayload(
  val type: String,
  val data: String,
  val mime: String?,
  val filename: String?,
  val path: String? = null,
  val endLine: Int? = null,
  val startLine: Int? = null,
  val errorCount: Int? = null,
  val symbolName: String? = null,
  val attachmentText: String? = null
)

/**
 * Orchestrates a single user request through one or more LLM turns.
 *
 * Delegates specialized concerns to dedicated managers:
 * - [ConversationHistory]: message list management
 * - [SessionManager]: session lifecycle and events
 * - [SystemPromptLoader]: prompt assembly
 * - [GuardrailManager]: content-safety and repetition detection
 * - [ToolExecutor]: tool call execution and result emission
 *
 * The [executeTask] method remains the single entry point; it drives the
 * agent loop, dispatching tool calls between turns until the model stops
 * requesting tools.
 */
class Agent(
  private val configuration: AgentConfiguration,
  private val emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit,
  private val unregisterChildSession: ((childSessionId: String) -> Unit)? = null,
  private val registerChildSession: ((childSessionId: String, agent: Agent) -> Unit)? = null,
  /**
   * Registry that backs the ask_interaction channel. When non-null the
   * session's [SkillContext.scope] is wired so skills can pause for a
   * user decision; when null (tests, isolated sub-agents) `scope` stays
   * null and asking skills must fall back rather than block forever.
   */
  askScopeHolder: PendingQuestions? = null,
  /**
   * The user's per-skill plugin config sections (`plugins.<skillName>`),
   * threaded from the server's settings. A skill reads its own slice through
   * the `gradum.skill.dsl` settings DSL; empty when nothing is configured.
   */
  plugins: Map<String, Map<String, Any?>> = emptyMap(),
) {
  private val ollamaClient: OllamaClient = OllamaClient(configuration)
  private val openAiClient: OpenAICompatibleClient = OpenAICompatibleClient(configuration)
  private var activeClient: LlmClient

  private val contextManager: ContextManager = ContextManager(
    outputDirectory = contextOutputDirectory(configuration)
  )

  private val conversationHistory: ConversationHistory = ConversationHistory()
  private val sessionManager: SessionManager = SessionManager(configuration, emitEvent)
  private val promptLoader: SystemPromptLoader = SystemPromptLoader(configuration)
  private val guardrailManager: GuardrailManager = GuardrailManager(configuration)

  // Per-session context shared with [toolExecutor]. Carries the session-scoped
  // [SkillContext.materializedMcpTools] set that drives which MCP tools are
  // injected into the model's tool list this session (see [buildToolSchemas]).
  private val skillContext: SkillContext = SkillContext(
    emitEvent = emitEvent,
    toolMode = configuration.toolMode,
    provider = configuration.provider,
    agentConfiguration = configuration,
    modelName = configuration.modelName,
    projectRoot = configuration.projectRoot,
    conversationHistory = { conversationHistory.toList() },
    registerChildSession = registerChildSession,
    unregisterChildSession = unregisterChildSession,
    scope = askScopeHolder?.let { holder ->
      AskScope(
        sessionId = configuration.sessionId.orEmpty(),
        pendingQuestions = holder,
        emitEvent = emitEvent,
      )
    },
    serverUrl = configuration.serverUrl,
    serverApiKey = configuration.serverApiKey,
    plugins = plugins,
  )

  private val toolExecutor: ToolExecutor = ToolExecutor(
    skillContext = skillContext, sessionManager, configuration,
    conversationHistory,
    emitEvent = emitEvent
  )

  private val maxHistoryMessages: Int = MAX_HISTORY_MESSAGES

  init {
    activeClient =
      when (configuration.provider) {
        Provider.OLLAMA -> ollamaClient
        else -> openAiClient
      }
  }

  internal constructor(
    llmClient: LlmClient,
    configuration: AgentConfiguration,
    emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit
  ) : this(configuration, emitEvent) {
    activeClient = llmClient
  }

  fun executeTask(
    userInput: String,
    displayText: String = "",
    messageId: String? = null,
    toolCallXml: String? = null,
    loadPreviousContext: Boolean = false,
    attachments: List<AttachmentPayload> = emptyList()
  ): Unit {
    sessionManager.reset()
    guardrailManager.reset()
    toolExecutor.reset()

    val sessionKey: String = configuration.sessionId?.trim().orEmpty()
    val resolvedMessageId: String =
      messageId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
    if (sessionKey.isNotEmpty() && registerChildSession != null)
      SessionSnapshotManager.createSnapshot(
        sessionId = sessionKey,
        projectRoot = configuration.projectRoot,
        turnMessageId = resolvedMessageId,
      )

    val contextLoaded: Boolean =
      if (loadPreviousContext) {
        val loadedMessages: List<Map<String, Any>> = contextManager.loadContext()
        conversationHistory.addAll(msgs = loadedMessages)
        loadedMessages.isNotEmpty()
      } else false

    conversationHistory.addSystemMessage(content = promptLoader.load(configuration.taskDescription))

    for (skill: Skill in SkillRegistry.getAllSkills())
      skill.resetHistoryCount()

    getTodoManagerInstance().resetTaskList()

    emitEvent(
      GradumEventType.SESSION_START.wireName,
      mapOf(
        "contextLoaded" to contextLoaded,
        "model" to configuration.modelName,
        "version" to Version.GRADUM_VERSION,
        "think" to configuration.enableThinking,
        "contextMessages" to conversationHistory.size,
        "sessionId" to sessionKey,
        "messageId" to resolvedMessageId
      )
    )

    injectPendingBackgroundResults()

    conversationHistory.addUserMessage(
      text = userInput,
      attachments = attachments,
      displayText = displayText,
      messageId = resolvedMessageId,
      projectRoot = configuration.projectRoot
    )

    if (toolCallXml != null) {
      playToolCallScenario(toolCallXml)
      sessionManager.finish()
      contextManager.saveContext(messages = conversationHistory.toList(), configuration.modelName)
      return
    }

    while (true) {
      if (sessionManager.isAborted) break

      conversationHistory.truncate(maxHistoryMessages)

      injectPendingBackgroundResults()

      val result: AgentTurnResult = processLlmTurn(buildToolSchemas())
      result.errorMessage?.let { error ->
        val turnErrorCode: ErrorCode =
          when (result.apiStatusCode) {
            401, 403 -> ErrorCode.LLM_AUTH_ERROR
            null -> ErrorCode.CLIENT_ERROR
            else -> ErrorCode.LLM_UPSTREAM_ERROR
          }
        emitEvent(
          GradumEventType.ERROR.wireName,
          mapOf(
            "code" to turnErrorCode,
            "message" to error.trim(),
            "source" to "${configuration.provider.name.lowercase()}_client",
            ERROR_SCOPE_KEY to ErrorScope.TURN.wireName
          )
        )
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
            thinkingText = result.thinkingText,
            revokeDetails = mapOf(
              "repeatedCount" to guardrailManager.repeatedResponseCount,
              "responses" to guardrailManager.repeatedResponses,
            ),
            turnTokenUsage = result.tokenUsage,
          )
        ) {
          break
        }
      }

      if (result.toolCalls.isNullOrEmpty()) {
        result.responseText?.let { text ->
          conversationHistory.addAssistantMessage(
            content = text, configuration.modelName, configuration.provider, thinking = result.thinkingText,
            tokenUsage = result.tokenUsage, processedCalls = null
          )
        }
        break
      }

      val processedCalls: List<ProcessedToolCall> = toolExecutor.prepareToolCalls(rawCalls = result.toolCalls)
      conversationHistory.addAssistantMessage(
        content = result.responseText ?: "",
        configuration.modelName,
        configuration.provider,
        thinking = result.thinkingText,
        tokenUsage = result.tokenUsage,
        processedCalls
      )

      for ((index: Int, processedCall: ProcessedToolCall) in processedCalls.withIndex())
        toolExecutor.executeSingleTool(
          processedCall,
          isLastToolCall = index == processedCalls.lastIndex
        )

      if (sessionManager.isAborted) break
    }

    if (configuration.taskDescription == null) {
      sessionManager.finish()
      contextManager.saveContext(messages = conversationHistory.toList(), configuration.modelName)
    }
  }

  fun getResult(): String? = conversationHistory.getResult()

  fun getConversationHistory(): List<Map<String, Any>> = conversationHistory.toList()

  fun isSessionAborted(): Boolean = sessionManager.isAborted

  fun getSessionEndReason(): String? = sessionManager.endReason

  fun abort(reason: String = USER_ABORT_REASON) {
    val tokenUsage = mapOf(
      "promptTokens" to activeClient.tokenUsage.promptTokens,
      "completionTokens" to activeClient.tokenUsage.completionTokens,
      "totalTokens" to activeClient.tokenUsage.totalTokens,
      "contextTokens" to activeClient.tokenUsage.lastPromptTokens
    )
    sessionManager.abort(reason, tokenUsage)
  }

  // Builds the tool list for the current LLM turn: the globally registered
  // skills (including the `mcp_tools` directory skill) plus the full schemas of
  // any MCP tools this session has materialized. Rebuilt every turn so tools
  // materialized mid-conversation appear in the model's tool list immediately.
  private fun buildToolSchemas(): List<Map<String, Any>> {
    val base: List<Map<String, Any>> = SkillRegistry.getSchemas(
      toolMode = configuration.toolMode,
      provider = configuration.provider,
      modelName = configuration.modelName
    )
    val materialized: List<Map<String, Any>> =
      McpToolCatalog.fullSchemas(skillContext.materializedMcpTools.names, configuration.toolMode)
    return base + materialized
  }

  private fun processLlmTurn(toolSchemas: List<Map<String, Any>>): AgentTurnResult {
    val contentParts: MutableList<String> = mutableListOf()
    val thinkingParts: MutableList<String> = mutableListOf()
    var toolCallsResult: List<ToolCallEntry>? = null
    var errorMessage: String? = null
    var apiStatusCode: Int? = null

    var turnUsage = TokenUsageSnapshot()
    val responseBuffer = StringBuilder()

    fun flushResponse() {
      val content: String = responseBuffer.toString()
      responseBuffer.clear()
      val snapshot: TokenUsageSnapshot = activeClient.tokenUsage
      turnUsage = snapshot
      if (content.isNotEmpty() || snapshot.totalTokens > 0) {
        emitEvent(
          GradumEventType.RESPONSE.wireName,
          mapOf(
            "content" to content,
            "totalTokens" to snapshot.totalTokens,
            "promptTokens" to snapshot.promptTokens,
            "contextTokens" to snapshot.lastPromptTokens,
            "completionTokens" to snapshot.completionTokens
          )
        )
      }
    }

    val responseFlow: Flow<LLMResponseChunk> =
      activeClient.sendChat(messageHistory = conversationHistory.toList(), toolDefinitions = toolSchemas)

    // TODO(tech-debt): migrate this to a `suspend fun` so the Netty event loop is not
    // blocked while streaming. Not safe to convert until `processLlmTurn` and all 9 skill
    // execute() paths are suspend-clean; tracked as the "Agent.runBlocking removal" item.
    runBlocking {
      responseFlow.takeWhile { !sessionManager.isAborted }.collect { chunk ->
        when (chunk) {
          is LLMResponseChunk.TextContent -> {
            contentParts.add(chunk.text)
            responseBuffer.append(chunk.text)
            flushResponse()
          }

          is LLMResponseChunk.ReasoningContent -> {
            thinkingParts.add(chunk.text)
            flushResponse()
            emitEvent(
              GradumEventType.THINKING.wireName,
              mapOf("content" to chunk.text)
            )
          }

          is LLMResponseChunk.ToolCallBatch -> {
            flushResponse()
            toolCallsResult = chunk.toolCalls
          }

          is LLMResponseChunk.ErrorMessage -> {
            flushResponse()
            errorMessage = chunk.description
            apiStatusCode = chunk.apiStatusCode
          }
        }
      }
    }

    flushResponse()

    val usageSnapshot: TokenUsageSnapshot = turnUsage
    return AgentTurnResult(
      errorMessage = errorMessage,
      responseText = contentParts.joinToString(separator = ""),
      thinkingText = thinkingParts.joinToString(separator = ""),
      apiStatusCode = apiStatusCode,
      toolCalls = toolCallsResult,
      tokenUsage = if (usageSnapshot.totalTokens > 0) {
        mapOf(
          "promptTokens" to usageSnapshot.promptTokens,
          "completionTokens" to usageSnapshot.completionTokens,
          "totalTokens" to usageSnapshot.totalTokens,
          "contextTokens" to usageSnapshot.lastPromptTokens
        )
      } else null,
    )
  }

  private fun handleGuardrailExceeded(
    responseText: String?,
    thinkingText: String = "",
    revokeDetails: Map<String, Any>,
    turnTokenUsage: Map<String, Int>? = null
  ): Boolean {
    responseText?.let { text ->
      conversationHistory.addAssistantMessage(
        content = text, configuration.modelName, configuration.provider, thinking = thinkingText,
        tokenUsage = turnTokenUsage, processedCalls = null
      )
    }
    val revokeReason: String = REPEATED_LOOP_REASON
    sessionManager.emitRevoked(revokeReason, revokeDetails)
    val tokenUsage = mapOf(
      "totalTokens" to activeClient.tokenUsage.totalTokens,
      "promptTokens" to activeClient.tokenUsage.promptTokens,
      "contextTokens" to activeClient.tokenUsage.lastPromptTokens,
      "completionTokens" to activeClient.tokenUsage.completionTokens
    )
    sessionManager.abort(revokeReason, tokenUsage)
    return true
  }

  // Debug tool-call playback mode. The developer authors a scenario as
  // a short <tls> XML block, playing the part of the model, instead
  // of spending LLM tokens. Every listed call runs through the real
  // [toolExecutor] pipeline, streams the same tool_call NDJSON events,
  // then writes a machine-readable recording to
  // `<projectRoot>/.gradum/recordings/<scenario>.json`.
  private fun playToolCallScenario(toolCallXml: String): Unit {
    val scenario: ToolCallScenario =
      try {
        ToolCallScenarioParser.parse(rawXml = toolCallXml)
      } catch (scenarioException: ToolCallScenarioParseException) {
        emitEvent(
          GradumEventType.ERROR.wireName,
          mapOf(
            "code" to ErrorCode.INVALID_SCENARIO_XML.name,
            "message" to (scenarioException.message ?: "Failed to parse tool-call scenario"),
            "source" to "debug_playback",
            ERROR_SCOPE_KEY to ErrorScope.TURN.wireName
          )
        )
        return
      }

    val scenarioName: String = scenario.scenarioName.ifBlank { "playback" }
    val toolCalls: List<ParsedToolCall> = scenario.toolCalls

    emitEvent(
      GradumEventType.PLAYBACK_START.wireName,
      mapOf(
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
              GradumEventType.RESPONSE.wireName,
              mapOf(
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
          val processedCall: ProcessedToolCall = toolExecutor.prepareToolCalls(rawCalls = listOf(callEntry)).first()
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
              "tool_expect_mismatch",
              mapOf(
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
      GradumEventType.PLAYBACK_END.wireName,
      mapOf(
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
      val safeName: String = scenarioName.replace(Regex(pattern = "[^A-Za-z0-9._-]"), replacement = "_")
      val recordingFile: Path = recordingsDir.resolve(
        "${safeName}-${System.currentTimeMillis()}.json"
      )
      Files.writeString(recordingFile, JsonUtil.encodeMap(input = summary, prettyPrint = true))
      logger.info("Playback recording written to $recordingFile")
    } catch (recordingException: Exception) {
      logger.error("Failed to write playback recording: ${recordingException::class.simpleName}: ${recordingException.message}")
    }
  }

  // Drains completed background task outcomes and injects them into the
  // conversation history as tool messages. Called before each model turn to
  // ensure background results are included in the next LLM request.
  private fun injectPendingBackgroundResults() {
    val manager = BackgroundTaskManager.getInstance() ?: return
    val sessionId = configuration.sessionId ?: return
    val allOutcomes = manager.drainCompletedOutcomes()

    for ((job) in allOutcomes.filter { it.job.sessionId != sessionId })
      logger.warn("Dropped background task outcome {} for foreign session {}", job.taskId, job.sessionId)

    val completedOutcomes = allOutcomes.filter { it.job.sessionId == sessionId }

    for (outcome in completedOutcomes) {
      val content: String = buildXml {
        element("BackgroundTaskResult") {
          element("TaskId") { text(outcome.job.taskId) }
          element("Command") { text(outcome.job.command) }
          element("Status") { text(backgroundStatusText(outcome)) }
          element("ExitCode") { text(outcome.exitCode.toString()) }
          element("DurationMs") { text(outcome.durationMs.toString()) }
          element("Output") {
            val cleanedLines: List<String> = trimmedOutputLines(outcome.output)
            if (cleanedLines.isEmpty()) {
              text("(no output)")
            } else {
              cleanedLines.forEach { line -> element("Line") { text(line) } }
            }
          }
        }
      }

      conversationHistory.appendSystemMessage(content)

      logger.info("Injected background task {} into session {}", outcome.job.taskId, sessionId)
      emitEvent(
        GradumEventType.BACKGROUND_RESULT.wireName,
        mapOf<String, Any>(
          "sessionId" to sessionId,
          "taskId" to outcome.job.taskId,
          "exitCode" to outcome.exitCode,
          "durationMs" to outcome.durationMs,
          "status" to outcome.job.status.name.lowercase()
        )
      )
    }
  }

  private fun backgroundStatusText(outcome: JobOutcome): String =
    when (outcome.job.status) {
      JobStatus.COMPLETED -> if (outcome.exitCode == 0) "completed successfully" else "failed"
      JobStatus.FAILED -> "failed"
      JobStatus.TIMEOUT -> "timed out and was terminated (output is partial)"
      JobStatus.CANCELLED -> "cancelled before completion"
      JobStatus.RUNNING -> "still running"
    }

  private data class AgentTurnResult(
    val errorMessage: String?,
    val responseText: String?,
    val thinkingText: String = "",
    val apiStatusCode: Int? = null,
    val toolCalls: List<ToolCallEntry>?,
    val tokenUsage: Map<String, Int>? = null
  )
}
