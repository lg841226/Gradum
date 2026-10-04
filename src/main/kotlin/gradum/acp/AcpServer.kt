package gradum.acp

import gradum.BuildConfig
import gradum.GradumRuntime
import gradum.ModelIdentity
import gradum.mcp.jsonrpc.RpcError
import gradum.mcp.jsonrpc.RpcNotification
import gradum.mcp.jsonrpc.RpcRequest
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 *
 * This file owns the transport layer only: the stdio read loop, frame
 * dispatch, and frame writing. Request handlers live in AcpSessionHandlers.kt
 * and AcpConfigHandlers.kt; session state lives in AcpSessionState.kt.
 */
class AcpServer(internal val runtime: GradumRuntime) {
  internal val logger: Logger = LoggerFactory.getLogger("AcpServer")
  internal val rpcJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
  }

  private val frameWriteMutex: Mutex = Mutex()
  internal val pendingQuestions: PendingQuestions = PendingQuestions()
  internal val activeSessions: ConcurrentHashMap<String, AcpSessionState> = ConcurrentHashMap()

  /**
   * In-flight agent→client requests (today: `session/request_permission`),
   * keyed by the outbound id. Outbound ids are negative so they can never
   * collide with the client's own request ids; [sendClientRequest] parks on
   * the matching deferred and [completeOutboundRequest] resolves it when the
   * client's response frame arrives on the read loop.
   */
  private val outboundRequests: ConcurrentHashMap<Long, CompletableDeferred<JsonObject>> =
    ConcurrentHashMap()
  private val outboundIdCounter: AtomicLong = AtomicLong(0L)

  fun runBlocking(): Unit = runBlocking(Dispatchers.IO) {
    val frameReader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val frameWriter = System.out.bufferedWriter()

    val probeJob: Job = launch { runConfigOptionProbe(frameWriter) }

    frameReader.lineSequence().forEach { frameLine ->
      val trimmedFrame: String = frameLine.trim()
      if (trimmedFrame.isEmpty()) return@forEach

      launch {
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

    // stdin closed: the client is gone, so stop probing before runBlocking waits on children.
    probeJob.cancel()
  }

  /**
   * Watches model availability on a fixed interval and, when the set of usable
   * models changes (e.g. a local server such as LM Studio was started or
   * stopped), pushes a `config_option_update` to every active session so ACP
   * clients refresh their model picker without polling.
   *
   * The first tick only records a baseline: nothing is pushed until a change is
   * actually observed. A probe that throws keeps the last snapshot, so a single
   * transient failure never looks like "all models disappeared".
   */
  private suspend fun runConfigOptionProbe(frameWriter: BufferedWriter) {
    var lastSnapshot: List<String>? = null
    while (currentCoroutineContext().isActive) {
      delay(MODEL_PROBE_INTERVAL_MS)
      val snapshot: List<String> = availableModelKeys() ?: continue
      if (lastSnapshot != null && snapshot != lastSnapshot) {
        logger.info("Model availability changed ({} -> {} models); notifying sessions", lastSnapshot.size, snapshot.size)
        broadcastConfigOptions(frameWriter)
      }
      lastSnapshot = snapshot
    }
  }

  /** Stable identity of the currently usable models, or null when the probe itself failed. */
  private fun availableModelKeys(): List<String>? =
    try {
      ModelIdentity.discoverModels()
        .filter { it.available }
        .map { "${it.providerType}|${it.serverName}|${it.modelName}" }
        .sorted()
    } catch (probeException: Exception) {
      logger.debug("Model availability probe failed; keeping last snapshot", probeException)
      null
    }

  private suspend fun broadcastConfigOptions(frameWriter: BufferedWriter) {
    for (sessionState in activeSessions.values) {
      try {
        sendNotification(
          frameWriter,
          "session/update",
          acpSessionUpdate(
            sessionId = sessionState.sessionId,
            update = acpConfigOptionUpdate(buildSessionConfigOptions(sessionState))
          )
        )
      } catch (sendException: Exception) {
        logger.warn("Failed to push config_option_update for session ${sessionState.sessionId}", sendException)
      }
    }
  }

  private suspend fun dispatchFrame(frameLine: String, frameWriter: BufferedWriter) {
    withContext(FrameWriterElement(frameWriter)) {
      val requestObject: JsonObject = rpcJson.parseToJsonElement(frameLine).jsonObject
      val requestId: Long? = requestObject["id"]?.jsonPrimitive?.longOrNull
      val method: String? = requestObject["method"]?.jsonPrimitive?.contentOrNull

      if (method == null) {
        if (requestId != null && completeOutboundRequest(requestObject)) {
          return@withContext
        }
        if (requestId != null) {
          logger.warn("Unmatched response frame (no pending outbound request): id=$requestId")
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
      "initialize" -> handleInitialize(requestId, params)
      "session/new" -> handleSessionNew(requestId, params)
      "session/load" -> handleSessionLoad(requestId, params)
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

  internal suspend fun sendNotification(frameWriter: BufferedWriter, method: String, params: JsonObject) {
    val notification = RpcNotification(method = method, params = params)
    writeFrame(frameWriter, rpcJson.encodeToString(notification))
  }

  /**
   * Sends an agent→client JSON-RPC request and parks until the client answers.
   *
   * There is deliberately **no timeout**: the caller blocks however long the
   * client takes (matching [PendingQuestions]' no-timeout ask channel). The
   * response frame is matched by its outbound id on the read loop and handed
   * back here via [completeOutboundRequest].
   */
  internal suspend fun sendClientRequest(
    frameWriter: BufferedWriter, method: String, params: JsonObject
  ): JsonObject {
    val outboundId: Long = outboundIdCounter.decrementAndGet()
    val response: CompletableDeferred<JsonObject> = CompletableDeferred()
    outboundRequests[outboundId] = response
    try {
      val request = RpcRequest(id = outboundId, method = method, params = params)
      writeFrame(frameWriter, rpcJson.encodeToString(request))
      return response.await()
    } finally {
      outboundRequests.remove(outboundId)
    }
  }

  /**
   * Routes a method-less frame to an in-flight outbound request. Returns true
   * when the id matched a live request (the deferred now holds the response),
   * false when the frame is an orphan response the server never asked for.
   */
  private fun completeOutboundRequest(responseObject: JsonObject): Boolean {
    val responseId: Long = responseObject["id"]?.jsonPrimitive?.longOrNull ?: return false
    val pending: CompletableDeferred<JsonObject> = outboundRequests.remove(responseId) ?: return false
    return pending.complete(responseObject)
  }

  private suspend fun writeFrame(frameWriter: BufferedWriter, jsonLine: String) {
    frameWriteMutex.withLock {
      frameWriter.write(FrameCodec.encode(jsonLine).decodeToString())
      frameWriter.flush()
    }
  }

  private fun handleInitialize(requestId: Long, params: JsonObject?): RpcResponse {
    val supportsTerminalAuth: Boolean = clientSupportsTerminalAuth(params)
    val initializeResult = acpInitialize {
      protocolVersion = 1
      agentInfo {
        name = "Gradum"
        version = BuildConfig.version
      }
      if (supportsTerminalAuth) {
        authMethod {
          id = "terminal"
          name = "Configure provider"
          description = "Run interactive setup in a terminal"
          type = "terminal"
          args = listOf("setup")
        }
      }
      agentCapabilities {
        loadSession = true
        promptCapabilities {
          image = true
          audio = false
          embeddedContext = false
        }
        mcpCapabilities { /* stdio only: http/sse stay false */ }
      }
    }
    return RpcResponse(id = requestId, result = initializeResult)
  }

  internal suspend fun getCurrentFrameWriter(): BufferedWriter {
    val element: FrameWriterElement = currentCoroutineContext()[FrameWriterElement]
      ?: error("ACP frame writer not available in current coroutine context")
    return element.writer
  }

  companion object {
    private const val METHOD_NOT_FOUND = -32601

    /** Model-availability probe cadence; 1s keeps the client's model picker near-live. */
    private const val MODEL_PROBE_INTERVAL_MS = 1_000L
  }
}

/**
 * The client's `clientCapabilities.auth.terminal` opt-in. ACP requires the
 * terminal auth method to be advertised only when the client reproduces the
 * agent invocation in an interactive terminal; an omitted flag means unsupported.
 */
internal fun clientSupportsTerminalAuth(params: JsonObject?): Boolean =
  params?.get("clientCapabilities")?.jsonObject
    ?.get("auth")?.jsonObject
    ?.get("terminal")?.jsonPrimitive?.booleanOrNull == true
