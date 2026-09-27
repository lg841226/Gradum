package gradum.mcp.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON-RPC 2.0 wire messages for the stdio MCP transport. These mirror the
 * spec shapes (a response carries exactly one of `result` or `error`, and a
 * notification has a `method` but no `id`); the JsonRpcSession decides which
 * subtype a frame maps to by inspecting the presence of an `id` field.
 */
@Serializable
internal data class RpcRequest(
  val id: Long,
  val method: String,
  val jsonrpc: String = "2.0",
  val params: JsonObject? = null
)

@Serializable
internal data class RpcResponse(
  val jsonrpc: String = "2.0",
  val id: Long? = null,
  val error: RpcError? = null,
  val result: JsonElement? = null
)

@Serializable
internal data class RpcError(
  val code: Int,
  val message: String,
  val data: JsonElement? = null
)

@Serializable
internal data class RpcNotification(
  val method: String,
  val jsonrpc: String = "2.0",
  val params: JsonObject? = null
)
