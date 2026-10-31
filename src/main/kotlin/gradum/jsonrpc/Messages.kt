package gradum.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// JSON-RPC 2.0 wire messages for the stdio MCP transport. These mirror the
// spec shapes (a response carries exactly one of `result` or `error`, and a
// notification has a `method` but no `id`); the JsonRpcSession decides which
// subtype a frame maps to by inspecting the presence of an `id` field.
//
// The response `id` is a [JsonPrimitive] rather than a Long because JSON-RPC
// allows both number and string ids, and the spec requires echoing the id
// back exactly as received: ACP clients mix both (Zed sends strings, IDEA
// sends numbers). A Long-only id silently dropped string ids, which the
// client reads as "no response to my request".
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
  val id: JsonPrimitive? = null,
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
