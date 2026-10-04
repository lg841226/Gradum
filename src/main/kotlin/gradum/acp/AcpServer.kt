package gradum.acp

import gradum.*
import gradum.agent.Agent
import gradum.agent.GradumEventType
import gradum.mcp.jsonrpc.RpcError
import gradum.mcp.jsonrpc.RpcNotification
import gradum.mcp.jsonrpc.RpcResponse
import gradum.mcp.transport.FrameCodec
import gradum.skill.PendingQuestions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * ACP v1 server running over stdio as a JSON-RPC 2.0 agent.
 *
 * GradumRuntime (settings, MCP manager, skill watcher, resolved API key)
 * is injected from the process entry point so both HTTP and ACP transports
 * share the same long-lived runtime components. Each prompt creates a fresh
 * Agent instance (matching HTTP mode), so emitEvent callbacks from concurrent
 * sessions never share state.
 *
 * Framing is newline-delimited (one JSON-RPC message per line), the same
 * convention the MCP stdio transport uses via [FrameCodec].
 */
class AcpServer(private val runtime: GradumRuntime) {
  private val logger: Logger = LoggerFactory.getLogger("AcpServer")
  private val rpcJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
  }

  private val frameWriteMutex: Mutex = Mutex()
  private val pendingQuestions: PendingQuestions = PendingQuestions()
  private val activeSessions: ConcurrentHashMap<String, AcpSessionState> = ConcurrentHashMap()

  fun runBlocking(): Unit = runBlocking(Dispatchers.IO) {
    val frameReader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val frameWriter = System.out.bufferedWriter()

    frameReader.lineSequence().forEach { frameLine ->
      val trimmedFrame: String = frameLine.trim()
      if (trimmedFrame.isEmpty()) return@forEach

      try {
        dispatchFrame(trimmedFrame, frameWriter)
      } catch (parseException: Exception) {
        logger.warn("Failed to parse incoming ACP frame", parseException)
        writeRpcResponse(
          frameWriter,
          RpcResponse(
            id = null,
            error = RpcError(code = -32700, message = "Parse error: ${parseException.message}")
          )
        )
      }
    }
  }

  private suspend fun dispatchFrame(frameLine: String, frameWriter: BufferedWriter) {
    withContext(FrameWriterElement(frameWriter)) {
      val requestObject: JsonObject = rpcJson.parseToJsonElement(frameLine).jsonObject
      val requestId: Long? = requestObject["id"]?.jsonPrimitive?.longOrNull
      val method: String? = requestObject["method"]?.jsonPrimitive?.contentOrNull

      if (method == null) {
        if (requestId != null) {
          writeRpcResponse(
            frameWriter,
            RpcResponse(id = requestId, error = RpcError(code = -32600, message = "Invalid Request"))
          )
        }
        return@withContext
      }

      if (requestId == null) {
        handleNotification(method, requestObject["params"]?.jsonObject)
        return@withContext
      }
      val response: RpcResponse = handleRequest(requestId, method, requestObject["params"]?.jsonObject)
      writeRpcResponse(frameWriter, response)
    }
  }

  private suspend fun handleRequest(requestId: Long, method: String, params: JsonObject?): RpcResponse {
    return when (method) {
      "initialize" -> handleInitialize(requestId)
      "session/new" -> handleSessionNew(requestId, params)
      "session/prompt" -> handleSessionPrompt(requestId, params)
      "session/cancel" -> handleSessionCancelRequest(requestId, params)
      "session/set_config_option" -> handleSetConfigOption(requestId, params)
      "authenticate" -> RpcResponse(
        id = requestId,
        error = RpcError(code = -32601, message = "Method not found: authenticate (Gradum needs no auth)")
      )

      else -> RpcResponse(
        id = requestId,
        error = RpcError(code = METHOD_NOT_FOUND, message = "Method not found: $method")
      )
    }
  }

  private fun handleNotification(method: String, params: JsonObject?) {
    if (method == "session/cancel") handleSessionCancel(params)
    else logger.debug("Ignoring unhandled notification: $method")
  }

  private suspend fun writeRpcResponse(frameWriter: BufferedWriter, response: RpcResponse) {
    writeFrame(frameWriter, rpcJson.encodeToString(response))
  }

  private suspend fun sendNotification(frameWriter: BufferedWriter, method: String, params: JsonObject) {
    val notification = RpcNotification(method = method, params = params)
    writeFrame(frameWriter, rpcJson.encodeToString(notification))
  }

  private suspend fun writeFrame(frameWriter: BufferedWriter, jsonLine: String) {
    frameWriteMutex.withLock {
      frameWriter.write(FrameCodec.encode(jsonLine).decodeToString())
      frameWriter.flush()
    }
  }

  private fun handleInitialize(requestId: Long): RpcResponse {
    val initializeResult = acpInitialize {
      protocolVersion = 1
      agentInfo {
        name = "Gradum"
        version = BuildConfig.version
      }
      authMethods = emptyList()
      agentCapabilities {
        loadSession = false
        prompt {
          image = true
          audio = false
          embeddedContext = false
        }
        modes = emptyList()
        tools {
          requestPermission = true
          fs {
            readTextFile = false
            writeTextFile = false
          }
          terminal = false
          preview = emptyList()
        }
        mcpCapabilities { enabled = true }
      }
    }
    return RpcResponse(id = requestId, result = initializeResult)
  }

  private fun handleSessionNew(requestId: Long, params: JsonObject?): RpcResponse {
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
        configOptions = listOf(buildModelConfigOption(sessionState.selectedModelName))
      }
    )
  }

  private fun buildModelConfigOption(selectedModel: String): JsonObject = acpModelConfigOption {
    id = "model"
    name = "Model"
    category = "model"
    type = "select"
    currentValue = selectedModel
    options = buildConfigOptionValues()
  }

  private fun buildConfigOptionValues(): List<JsonObject> {
    val models: List<ModelEntry> = ModelIdentity.discoverModels().filter { it.available }
    return models.map { entry ->
      buildOptionValue(entry.modelName, entry.serverName, entry.providerType)
    }
  }

  private fun buildOptionValue(modelName: String, serverName: String, providerType: String): JsonObject {
    val description: String? = serverName.takeIf { it.isNotBlank() }?.let { "$providerType @ $it" }
    return acpOptionValue(modelName, description)
  }

  private fun handleSetConfigOption(requestId: Long, params: JsonObject?): RpcResponse {
    val sessionId: String = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Missing required parameter: sessionId")
      )

    val configId: String = params["configId"]?.jsonPrimitive?.contentOrNull
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Missing required parameter: configId")
      )

    val value: String = params["value"]?.jsonPrimitive?.contentOrNull
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Missing required parameter: value")
      )

    val sessionState: AcpSessionState = activeSessions[sessionId]
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Unknown sessionId: $sessionId")
      )

    when (configId) {
      "model" -> {
        sessionState.selectedModelName = value
        logger.info("ACP config changed: sessionId=$sessionId model=$value")
      }

      else -> logger.warn("Unknown config option: $configId")
    }

    return RpcResponse(
      id = requestId,
      result = acpConfigOptions(listOf(buildModelConfigOption(sessionState.selectedModelName)))
    )
  }

  private suspend fun handleSessionPrompt(requestId: Long, params: JsonObject?): RpcResponse {
    val sessionId: String = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Missing required parameter: sessionId")
      )

    val sessionState: AcpSessionState = activeSessions[sessionId]
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Unknown sessionId: $sessionId")
      )

    val messageContent: String = extractMessageContent(params)
      ?: return RpcResponse(
        id = requestId,
        error = RpcError(code = -32602, message = "Missing required parameter: message.content")
      )

    val frameWriter = getCurrentFrameWriter()
    val agentConfiguration: AgentConfiguration = buildAgentConfiguration(sessionState)

    val emitEvent: (String, Map<String, Any>) -> Unit = { eventType, eventData ->
      CoroutineScope(Dispatchers.IO).launch {
        val update: JsonObject? = mapGradumEventToAcpUpdate(sessionId, eventType, eventData)
        if (update != null) {
          sendNotification(frameWriter, "session/update", update)
        }
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
      agent.executeTask(userInput = messageContent)
    } catch (_: InterruptedException) {
      logger.info("ACP session/prompt aborted: sessionId=$sessionId")
    }

    sessionState.currentAgent = null

    return RpcResponse(
      id = requestId,
      result = acpPromptResult("end_turn")
    )
  }

  private fun handleSessionCancelRequest(requestId: Long, params: JsonObject?): RpcResponse {
    handleSessionCancel(params)
    return RpcResponse(id = requestId, result = acpEmptyResult())
  }

  private fun handleSessionCancel(params: JsonObject?) {
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

  private suspend fun getCurrentFrameWriter(): BufferedWriter {
    val element: FrameWriterElement = currentCoroutineContext()[FrameWriterElement]
      ?: error("ACP frame writer not available in current coroutine context")
    return element.writer
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

  private fun buildAgentConfiguration(sessionState: AcpSessionState): AgentConfiguration {
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
      modelName = sessionState.selectedModelName,
      projectRoot = sessionState.projectRoot,
      enableThinking = settings.defaultThinkEnabled,
      keepAliveMinutes = settings.defaultKeepAliveMinutes
    )
  }

  private fun mapGradumEventToAcpUpdate(
    sessionId: String, eventType: String, eventData: Map<String, Any>
  ): JsonObject? {
    val update: JsonObject =
      when (eventType) {
        GradumEventType.RESPONSE.wireName,
        GradumEventType.THINKING.wireName -> {
          val content: String? = eventData["content"] as? String
          if (content.isNullOrBlank()) return null
          val discriminator =
            if (eventType == GradumEventType.RESPONSE.wireName) "agent_message_chunk"
            else "agent_thought_chunk"
          acpAgentTextChunk(discriminator, content)
        }

        GradumEventType.TOOL_CALL_START.wireName -> {
          val callId: String = eventData["toolCallId"] as? String ?: return null
          val toolName: String = eventData["tool"] as? String ?: "tool"
          acpToolCallStart {
            toolCallId = callId
            name = toolName
            rawInput = eventData["arguments"]
          }
        }

        GradumEventType.TOOL_CALL.wireName -> {
          val callId: String = eventData["toolCallId"] as? String ?: return null
          val success = eventData["success"] as? Boolean
          acpToolCallUpdate {
            toolCallId = callId
            status = if (success == true) "completed" else "failed"
            rawOutput = eventData["result"]
          }
        }

        else -> return null
      }

    return acpSessionUpdate(sessionId, update)
  }

  companion object {
    private const val METHOD_NOT_FOUND = -32601
  }
}

private class AcpSessionState(
  val sessionId: String,
  val projectRoot: String,
  defaultModelName: String
) {
  var currentAgent: Agent? = null
  var selectedModelName: String = defaultModelName
}

private data class FrameWriterElement(val writer: BufferedWriter) : CoroutineContext.Element {
  override val key: CoroutineContext.Key<*> = Key

  companion object Key : CoroutineContext.Key<FrameWriterElement>
}
