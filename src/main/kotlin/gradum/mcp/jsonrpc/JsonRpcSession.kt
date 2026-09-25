/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JsonRpcSession.kt  2026-09-25 21:37:42 Changed by gwy
 */

package gradum.mcp.jsonrpc

import gradum.mcp.transport.FrameCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Concurrent request/response dispatcher for JSON-RPC 2.0.
 *
 * Each [call] assigns a fresh `id`, stashes a deferred for it, writes the
 * request frame, then suspends until [handleFrame] completes the matching
 * deferred. Because matching is by `id` — never by arrival order — many
 * calls can be in flight at once. Frames that carry no `id` are treated as
 * notifications and routed to [onNotification].
 */
internal class JsonRpcSession(
  private val sendFrame: suspend (ByteArray) -> Unit,
  private val onNotification: (RpcNotification) -> Unit = {},
) {
  // encodeDefaults so the mandatory `jsonrpc: "2.0"` member is always emitted;
  // explicitNulls off so absent params are omitted, not sent as `"params": null`
  // (JSON-RPC 2.0 params must be an object/array, and servers reject null).
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
  private val nextId = AtomicLong(0)

  /**
   * Sends a request and awaits its result. Throws [RpcException] on a
   * JSON-RPC error response and [kotlinx.coroutines.TimeoutCancellationException]
   * if no response arrives within [timeoutMillis].
   */
  suspend fun call(
    method: String, params: JsonObject? = null, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
  ): JsonElement {
    val requestId = nextId.incrementAndGet()
    val rpcRequest = RpcRequest(id = requestId, method = method, params = params)
    val responseDeferred = CompletableDeferred<JsonElement>()
    pending[requestId] = responseDeferred
    try {
      sendFrame(FrameCodec.encode(json.encodeToString(rpcRequest)))
      return withTimeout(timeoutMillis.milliseconds) { responseDeferred.await() }
    } finally {
      pending.remove(requestId)
    }
  }

  /** Sends a fire-and-forget notification (a request with no `id`). */
  suspend fun sendNotification(method: String, params: JsonObject? = null) {
    val notification = RpcNotification(method = method, params = params)
    sendFrame(FrameCodec.encode(json.encodeToString(notification)))
  }

  /**
   * Feeds one decoded message body from the transport. Non-suspending so a
   * background reader can dispatch responses as they arrive.
   */
  fun handleFrame(jsonString: String) {
    val message = json.parseToJsonElement(jsonString).jsonObject
    if (message.containsKey("id")) {
      handleResponse(message)
    } else {
      val notification = json.decodeFromJsonElement(RpcNotification.serializer(), message)
      onNotification(notification)
    }
  }

  private fun handleResponse(message: JsonObject) {
    val rpcResponse = json.decodeFromJsonElement(RpcResponse.serializer(), message)
    val responseId = rpcResponse.id ?: return
    val pendingDeferred = pending[responseId] ?: return
    val rpcError = rpcResponse.error
    if (rpcError != null) {
      pendingDeferred.completeExceptionally(RpcException(rpcError.code, rpcError.message, rpcError.data))
    } else {
      pendingDeferred.complete(rpcResponse.result ?: JsonNull)
    }
  }

  companion object {
    private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
  }
}

/**
 * Raised when the peer answers a request with a JSON-RPC error object.
 */
internal class RpcException(val code: Int, serverMessage: String, val data: JsonElement? = null) :
  Exception("JSON-RPC error $code: $serverMessage")
