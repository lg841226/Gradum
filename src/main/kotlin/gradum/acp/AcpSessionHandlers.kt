package gradum.acp

import gradum.AgentConfiguration
import gradum.PromptVariant
import gradum.Provider
import gradum.agent.Agent
import gradum.mcp.jsonrpc.RpcError
import gradum.mcp.jsonrpc.RpcResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.*

/**
 * `session` request handlers plus the helpers they depend on. These are
 * AcpServer extension functions so the transport class in AcpServer.kt stays
 * a thin stdio loop while session lifecycle, prompt streaming, and
 * cancellation live together here.
 */

internal fun AcpServer.handleSessionNew(requestId: Long, params: JsonObject?): RpcResponse {
  val cwd: String = params?.get("projectRoot")?.jsonPrimitive?.contentOrNull
    ?: params?.get("cwd")?.jsonPrimitive?.contentOrNull
    ?: System.getProperty("user.dir")

  val newSessionId = UUID.randomUUID().toString()
  val sessionState = AcpSessionState(
    projectRoot = cwd,
    sessionId = newSessionId,
    defaultModelName = runtime.settings.defaultModelName
  )
  activeSessions[newSessionId] = sessionState

  logger.info("ACP session created: sessionId=$newSessionId projectRoot=$cwd")
  return RpcResponse(
    id = requestId,
    result = acpSessionNew {
      sessionId = newSessionId
      configOptions = buildSessionConfigOptions(sessionState)
    }
  )
}

internal suspend fun AcpServer.handleSessionPrompt(requestId: Long, params: JsonObject?): RpcResponse {
  val sessionId: String = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Missing required parameter: sessionId"
      )
    )

  val sessionState: AcpSessionState = activeSessions[sessionId]
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Unknown sessionId: $sessionId"
      )
    )

  val messageContent: String = extractMessageContent(params)
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(
        code = -32602, message = "Missing required parameter: message.content"
      )
    )

  val frameWriter = getCurrentFrameWriter()
  val agentConfiguration: AgentConfiguration = buildAgentConfiguration(sessionState)

  val updateChannel: Channel<JsonObject> = Channel(Channel.UNLIMITED)
  val updateWriter: Job = CoroutineScope(Dispatchers.IO).launch {
    for (update in updateChannel)
      sendNotification(frameWriter, "session/update", update)
  }

  val emitEvent: (String, Map<String, Any>) -> Unit = { eventType, eventData ->
    val update: JsonObject? = mapGradumEventToAcpUpdate(sessionId, eventType, eventData)
    if (update != null) updateChannel.trySend(update)
  }

  val agent = Agent(
    emitEvent = emitEvent,
    configuration = agentConfiguration,
    askScopeHolder = pendingQuestions,
    plugins = runtime.settings.plugins
  )
  sessionState.currentAgent = agent

  logger.info("ACP session/prompt: sessionId=$sessionId input=${messageContent.take(40)}...")

  try {
    agent.executeTask(userInput = messageContent)
  } catch (_: InterruptedException) {
    logger.info("ACP session/prompt aborted: sessionId=$sessionId")
  } finally {
    updateChannel.close()
    updateWriter.join()
  }

  sessionState.currentAgent = null

  return RpcResponse(
    id = requestId,
    result = acpPromptResult("end_turn")
  )
}

internal fun AcpServer.handleSessionCancelRequest(requestId: Long, params: JsonObject?): RpcResponse {
  handleSessionCancel(params)
  return RpcResponse(id = requestId, result = acpEmptyResult())
}

internal fun AcpServer.handleSessionCancel(params: JsonObject?) {
  val sessionId: String? = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
  val sessionState: AcpSessionState? = activeSessions[sessionId]

  if (sessionId == null) {
    logger.warn("session/cancel missing sessionId")
    return
  }

  if (sessionState == null) {
    logger.warn("session/cancel for unknown sessionId=$sessionId")
    return
  }

  sessionState.currentAgent?.abort()
  logger.info("ACP session cancelled: sessionId=$sessionId")
}

private fun extractMessageContent(params: JsonObject?): String? {
  val promptArray: JsonArray = params?.get("prompt") as? JsonArray ?: return null
  val texts: List<String> = promptArray.mapNotNull { block ->
    val jsonObject = block as? JsonObject ?: return@mapNotNull null
    val type = (jsonObject["type"] as? JsonPrimitive)?.contentOrNull

    if (type == "text") (jsonObject["text"] as? JsonPrimitive)?.contentOrNull
    else null
  }
  return texts.joinToString("\n").ifBlank { null }
}

private fun AcpServer.buildAgentConfiguration(sessionState: AcpSessionState): AgentConfiguration {
  val provider: Provider = Provider.fromStringOrDefault(null, Provider.OLLAMA)
  val promptVariant: PromptVariant = PromptVariant.resolveAuto(provider)
  val settings = runtime.settings

  return AgentConfiguration(
    provider = provider,
    taskDescription = null,
    promptVariant = promptVariant,
    apiKey = runtime.resolvedApiKey,
    baseUrl = settings.defaultBaseUrl,
    sessionId = sessionState.sessionId,
    projectRoot = sessionState.projectRoot,
    toolMode = sessionState.selectedToolMode,
    modelName = sessionState.selectedModelName,
    enableThinking = settings.defaultThinkEnabled,
    keepAliveMinutes = settings.defaultKeepAliveMinutes
  )
}
