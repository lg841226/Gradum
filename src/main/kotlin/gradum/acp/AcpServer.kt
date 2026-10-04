package gradum.acp

import gradum.BuildConfig
import gradum.GradumRuntime
import gradum.mcp.jsonrpc.RpcError
import gradum.mcp.jsonrpc.RpcNotification
import gradum.mcp.jsonrpc.RpcResponse
import gradum.mcp.transport.FrameCodec
import gradum.skill.PendingQuestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

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

  internal suspend fun sendNotification(frameWriter: BufferedWriter, method: String, params: JsonObject) {
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

  internal suspend fun getCurrentFrameWriter(): BufferedWriter {
    val element: FrameWriterElement = currentCoroutineContext()[FrameWriterElement]
      ?: error("ACP frame writer not available in current coroutine context")
    return element.writer
  }

  companion object {
    private const val METHOD_NOT_FOUND = -32601
  }
}
