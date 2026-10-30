package gradum.acp

import gradum.AgentConfiguration
import gradum.ModelIdentity
import gradum.PromptVariant
import gradum.Provider
import gradum.agent.*
import gradum.client.ThinkingLevel
import gradum.mcp.McpServerConfig
import gradum.mcp.jsonrpc.RpcError
import gradum.mcp.jsonrpc.RpcResponse
import gradum.skill.Choice
import gradum.utils.ContextManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.io.BufferedWriter
import java.util.*
import java.util.concurrent.atomic.AtomicReference


private const val PROMPT_FAILED = -32603
private const val AUTH_REQUIRED = -32000

internal suspend fun AcpServer.handleSessionNew(requestId: JsonPrimitive, params: JsonObject?): RpcResponse {
  if (!runtime.settings.isConfigured && clientTerminalAuthEnabled) {
    return RpcResponse(
      id = requestId,
      error = RpcError(
        code = AUTH_REQUIRED,
        message = "Authentication required: run the Gradum setup wizard to pick a provider",
        data = buildJsonObject { putJsonArray("authMethods") { add(buildTerminalAuthMethod()) } }
      )
    )
  }

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

  val clientMcpServers: List<McpServerConfig> = parseAcpMcpServers(params, workingDir = cwd)
  if (clientMcpServers.isNotEmpty()) {
    logger.info(
      "Connecting {} client-provided MCP server(s) for session {}",
      clientMcpServers.size, newSessionId
    )
    val mcpFailures = runtime.mcpManager.connect(clientMcpServers)
    runtime.mcpManager.reportFailures(mcpFailures)
  }

  logger.info("ACP session created: sessionId=$newSessionId projectRoot=$cwd")
  return RpcResponse(
    id = requestId,
    result = acpSessionNew {
      sessionId = newSessionId
      configOptions = buildSessionConfigOptions(sessionState)
    }
  )
}

internal suspend fun AcpServer.handleSessionLoad(requestId: JsonPrimitive, params: JsonObject?): RpcResponse {
  val sessionId: String = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
    ?: return RpcResponse(
      id = requestId,
      error = RpcError(code = -32602, message = "Missing required parameter: sessionId")
    )

  val cwd: String = params["cwd"]?.jsonPrimitive?.contentOrNull
    ?: params["projectRoot"]?.jsonPrimitive?.contentOrNull
    ?: System.getProperty("user.dir")

  val isNewSession: Boolean = activeSessions[sessionId] == null
  val sessionState: AcpSessionState = activeSessions.computeIfAbsent(sessionId) {
    AcpSessionState(
      projectRoot = cwd,
      sessionId = sessionId,
      defaultModelName = runtime.settings.defaultModelName
    )
  }

  if (isNewSession) {
    val clientMcpServers: List<McpServerConfig> = parseAcpMcpServers(params, workingDir = cwd)
    if (clientMcpServers.isNotEmpty()) {
      logger.info(
        "Connecting {} client-provided MCP server(s) for loaded session {}",
        clientMcpServers.size, sessionId
      )
      val mcpFailures = runtime.mcpManager.connect(clientMcpServers)
      runtime.mcpManager.reportFailures(mcpFailures)
    }
  }

  val replayedCount: Int = replaySessionHistory(sessionState, getCurrentFrameWriter())
  logger.info("ACP session loaded: sessionId=$sessionId projectRoot=$cwd replayed=$replayedCount")

  return RpcResponse(
    id = requestId,
    result = acpConfigOptions(buildSessionConfigOptions(sessionState))
  )
}

// Streams a loaded session's persisted messages back to the client and returns
// how many were replayed. Only `user` and `assistant` text map onto ACP chunk
// updates; system prompts and raw tool messages are Gradum-internal and stay
// server-side.
private suspend fun AcpServer.replaySessionHistory(
  sessionState: AcpSessionState, frameWriter: BufferedWriter
): Int {
  val contextManager = ContextManager(
    outputDirectory = contextOutputDirectory(buildAgentConfiguration(sessionState))
  )
  var replayed = 0
  for (message in contextManager.loadContext()) {
    val discriminator: String =
      when (message) {
        is UserMessageRecord -> "user_message_chunk"
        is AssistantMessageRecord -> "agent_message_chunk"
        else -> continue
      }
    val text: String = replayableText(message) ?: continue
    sendNotification(
      frameWriter,
      "session/update",
      acpSessionUpdate(
        sessionId = sessionState.sessionId,
        update = acpAgentTextChunk(discriminator, text)
      )
    )
    replayed++
  }
  return replayed
}

private fun replayableText(message: MessageRecord): String? {
  val content: String? = when (message) {
    is UserMessageRecord -> message.userText()
    is AssistantMessageRecord -> message.content
    else -> null
  }
  return content?.takeIf { it.isNotBlank() }
}

private fun AcpServer.parseAcpMcpServers(params: JsonObject?, workingDir: String): List<McpServerConfig> {
  val servers: JsonArray = params?.get("mcpServers") as? JsonArray ?: return emptyList()

  return servers.mapNotNull { element ->
    val server: JsonObject = element as? JsonObject ?: return@mapNotNull null
    val serverName: String = (server["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    val command: String? = (server["command"] as? JsonPrimitive)?.contentOrNull
    if (command == null) {
      logger.warn("Skipping MCP server '$serverName': only stdio transports are supported")
      return@mapNotNull null
    }

    val args: List<String> = (server["args"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?: emptyList()
    val env: Map<String, String> = (server["env"] as? JsonObject)
      ?.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
      }
      ?.toMap()
      ?: emptyMap()

    McpServerConfig(
      env = env,
      name = serverName,
      workingDir = workingDir,
      command = listOf(command) + args
    )
  }
}

internal suspend fun AcpServer.handleSessionPrompt(requestId: JsonPrimitive, params: JsonObject?): RpcResponse {
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

  val promptScope = CoroutineScope(Dispatchers.IO)
  val updateChannel: Channel<JsonObject> = Channel(Channel.UNLIMITED)
  val updateWriter: Job = promptScope.launch {
    for (update in updateChannel)
      sendNotification(frameWriter, "session/update", update)
  }

  val turnErrorMessage = AtomicReference<String?>(null)

  val emitEvent: (String, Map<String, Any>) -> Unit = { eventType, eventData ->
    if (eventType == GradumEventType.ASK_INTERACTION.wireName) {
      promptScope.launch {
        bridgeAskInteractionToPermission(sessionId, frameWriter, eventData)
      }
    } else {
      if (eventType == GradumEventType.ERROR.wireName) {
        // Only turn-fatal errors may fail the whole prompt. A per-tool failure
        // is recoverable (the model can retry) and is already surfaced to the
        // client as a failed tool_call_update, so it must not poison the turn.
        if (eventData[ERROR_SCOPE_KEY] == ErrorScope.TURN.wireName) {
          eventData["message"]?.toString()?.takeIf { it.isNotBlank() }?.let { message ->
            turnErrorMessage.compareAndSet(null, message)
          }
        }
      }
      val update: JsonObject? = mapGradumEventToAcpUpdate(sessionId, eventType, eventData)
      if (update != null) updateChannel.trySend(update)
    }
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
    agent.executeTask(userInput = messageContent, loadPreviousContext = true)
  } catch (_: InterruptedException) {
    logger.info("ACP session/prompt aborted: sessionId=$sessionId")
  } finally {
    updateChannel.close()
    updateWriter.join()
    promptScope.cancel()
  }

  sessionState.currentAgent = null

  val endReason: String? = agent.getSessionEndReason()
  val userCancelled: Boolean = agent.isSessionAborted() && endReason == USER_ABORT_REASON

  val failureMessage: String? = turnErrorMessage.get()
    ?: if (agent.isSessionAborted() && !userCancelled)
      "Agent stopped without producing a result ($endReason)"
    else null

  if (!userCancelled && failureMessage != null) {
    logger.info("ACP session/prompt failed: sessionId=$sessionId message=$failureMessage")
    return RpcResponse(
      id = requestId,
      error = RpcError(code = PROMPT_FAILED, message = failureMessage)
    )
  }

  val stopReason: String =
    if (userCancelled) "cancelled"
    else "end_turn"

  return RpcResponse(
    id = requestId,
    result = acpPromptResult(stopReason)
  )
}

private suspend fun AcpServer.bridgeAskInteractionToPermission(
  sessionId: String, frameWriter: BufferedWriter, eventData: Map<String, Any>
) {
  val requestId: String = eventData["requestId"]?.toString() ?: return
  val choiceItems: List<Map<*, *>> = (eventData["choices"] as? List<*>)
    ?.filterIsInstance<Map<*, *>>()
    ?: emptyList()
  val permissionOptions: List<JsonObject> = choiceItems.mapNotNull { choiceItem: Map<*, *> ->
    choiceItem["semantics"]?.toString()?.let { buildPermissionOption(it) }
  }
  if (permissionOptions.isEmpty()) {
    logger.info("ask_interaction $requestId exposes no ACP-mappable choices; cancelling")
    pendingQuestions.completeCancelled(sessionId, requestId)
    return
  }

  val askTitle: String = askDisplayTitle(eventData)
  sendNotification(
    frameWriter,
    "session/update",
    acpSessionUpdate(
      sessionId = sessionId,
      update = acpToolCallStart {
        toolCallId = requestId
        name = "permission"
        title = askTitle
      }
    )
  )

  val clientResponse: JsonObject = sendClientRequest(
    frameWriter = frameWriter,
    method = "session/request_permission",
    params = acpRequestPermissionParams(
      sessionId = sessionId,
      toolCallId = requestId,
      title = askTitle,
      options = permissionOptions
    )
  )
  logger.info("Permission response for ask $requestId: $clientResponse")

  val responseOutcome: JsonObject? = clientResponse["result"]
    ?.jsonObject?.get("outcome")?.jsonObject
  val outcomeKind: String? = responseOutcome?.get("outcome")?.jsonPrimitive?.contentOrNull
  val selectedOptionId: String? = responseOutcome?.get("optionId")?.jsonPrimitive?.contentOrNull
  sendNotification(
    frameWriter,
    "session/update",
    acpSessionUpdate(
      sessionId = sessionId,
      update = acpToolCallUpdate {
        toolCallId = requestId
        status = "completed"
      }
    )
  )
  if (outcomeKind == "selected" && selectedOptionId != null) {
    pendingQuestions.completeChoice(sessionId, requestId, selectedOptionId)
  } else {
    pendingQuestions.completeCancelled(sessionId, requestId)
  }
}

private fun askDisplayTitle(eventData: Map<String, Any>): String =
  l10nWireToText(eventData["details"])
    ?: l10nWireToText(eventData["title"])
    ?: "Permission required"

private fun l10nWireToText(wireValue: Any?): String? {
  val wireMap: Map<*, *> = wireValue as? Map<*, *> ?: return null
  if (wireMap["kind"] != "raw") return null
  return wireMap["text"]?.toString()?.takeIf { it.isNotBlank() }
}

private fun buildPermissionOption(semantics: String): JsonObject? {
  val meaning: Choice.Meaning = Choice.Meaning.entries.firstOrNull { it.wire == semantics }
    ?: return null
  val (optionName, optionKind) =
    when (meaning) {
      Choice.Meaning.ALLOW_ONCE -> "Allow this one time, ask again next time" to "allow_once"
      Choice.Meaning.ALLOW_ALWAYS -> "Always allow for this session" to "allow_always"
      Choice.Meaning.REJECT -> "Reject this request" to "reject_once"
    }
  return acpPermissionOption(optionId = meaning.wire, name = optionName, kind = optionKind)
}

internal fun AcpServer.handleSessionCancelRequest(requestId: JsonPrimitive, params: JsonObject?): RpcResponse {
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
  val cancelledAsks: Int = pendingQuestions.cancelSession(sessionId)
  logger.info("ACP session cancelled: sessionId=$sessionId cancelledAsks=$cancelledAsks")
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
  val settings = runtime.settings

  // Shared with the HTTP `/events` route so ACP and the plugin always pick
  // the same provider / base URL / key for the same model.
  val resolvedTarget: ModelIdentity.ResolvedModelTarget = ModelIdentity.resolveModelTarget(
    modelName = sessionState.selectedModelName,
    fallbackProvider = Provider.OLLAMA.wireType,
    fallbackBaseUrl = settings.defaultBaseUrl,
    fallbackApiKey = runtime.resolvedApiKey
  )

  val provider: Provider = Provider.fromStringOrDefault(resolvedTarget.providerType)
  val promptVariant: PromptVariant = PromptVariant.resolveAuto(provider)

  return AgentConfiguration(
    apiKey = resolvedTarget.apiKey,
    baseUrl = resolvedTarget.baseUrl,
    provider = provider,
    taskDescription = null,
    promptVariant = promptVariant,
    sessionId = sessionState.sessionId,
    projectRoot = sessionState.projectRoot,
    toolMode = sessionState.selectedToolMode,
    modelName = sessionState.selectedModelName,
    enableThinking = settings.defaultThinkEnabled,
    // No per-turn level on the ACP surface: keep the legacy "think on"
    // intent as a balanced effort, off otherwise.
    thinkingLevel = if (settings.defaultThinkEnabled) ThinkingLevel.MEDIUM else ThinkingLevel.OFF,
    keepAliveMinutes = settings.defaultKeepAliveMinutes
  )
}
