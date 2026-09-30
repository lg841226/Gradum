package gradum.mcp

import gradum.BuildConfig
import gradum.mcp.transport.StdioMcpClient
import kotlinx.serialization.json.*

/**
 * A server tool as advertised by MCP `tools/list`. [inputSchema] is a
 * standard JSON Schema the caller may surface to the model unchanged.
 */
data class McpTool(
  val name: String,
  val description: String?,
  val inputSchema: JsonObject
)

/**
 * High-level client for the Model Context Protocol over a stdio transport.
 * Owns the initialize handshake and the three calls that matter to an agent
 * tool integration: [initialize], [listTools], and [callTool].
 *
 * start() spawns the underlying stdio child process. Safe to call once.
 * initialize(protocolVersion) performs the MCP initialize handshake: sends
 * `initialize`, waits for the `InitializeResult`, then emits the
 * `notifications/initialized` notification so the server will start serving
 * tool requests. listTools() lists the tools the server advertises via
 * `tools/list`, and callTool(name, arguments) invokes a tool and returns the
 * raw `tools/call` result element. The companion's TOOLS_CALL_TIMEOUT_MILLIS
 * is a longer budget for a `tools/call`: an MCP command may take a while to
 * finish.
 */
internal class McpClient(private val transport: StdioMcpClient) : AutoCloseable {

  fun start() = transport.start()

  suspend fun initialize(protocolVersion: String = PROTOCOL_VERSION) {
    transport.session.call(
      method = "initialize",
      params = buildJsonObject {
        put("protocolVersion", protocolVersion)
        put("capabilities", buildJsonObject {})
        put("clientInfo", buildJsonObject {
          put("name", "Gradum")
          put("version", BuildConfig.version)
        })
      },
    )
    transport.session.sendNotification("notifications/initialized")
  }

  suspend fun listTools(): List<McpTool> {
    val listToolsResult = transport.session.call("tools/list")
    val toolsArray = listToolsResult.jsonObject["tools"]?.jsonArray ?: return emptyList()
    return toolsArray.mapNotNull { eltoolElementment ->
      val toolObject = eltoolElementment.jsonObject
      val toolName = (toolObject["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
      val description = (toolObject["description"] as? JsonPrimitive)?.content
      val inputSchema = toolObject["inputSchema"]?.jsonObject ?: buildJsonObject {}
      McpTool(name = toolName, description = description, inputSchema = inputSchema)
    }
  }

  suspend fun callTool(name: String, arguments: JsonObject): JsonElement =
    transport.session.call(
      method = "tools/call",
      params = buildJsonObject {
        put("name", name)
        put("arguments", arguments)
      },
      timeoutMillis = TOOLS_CALL_TIMEOUT_MILLIS,
    )

  override fun close() = transport.close()

  companion object {
    const val PROTOCOL_VERSION = "2025-03-26"
    const val TOOLS_CALL_TIMEOUT_MILLIS = 180_000L
  }
}
