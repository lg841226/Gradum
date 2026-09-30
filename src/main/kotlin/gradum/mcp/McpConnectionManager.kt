package gradum.mcp

import gradum.mcp.transport.StdioMcpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

private val logger: Logger = LoggerFactory.getLogger("McpConnectionManager")

/**
 * A configured MCP stdio server the user declared in settings.json.
 * [command] is the executable + arguments that spawn the server process;
 * [workingDir] and [env] are passed through to [ProcessBuilder].
 */
data class McpServerConfig(
  val name: String,
  val command: List<String>,
  val workingDir: String? = null,
  val env: Map<String, String> = emptyMap()
)

/**
 * Owns the live connections to all configured MCP servers. [connect] starts
 * each server, performs the initialize handshake, lists its tools, and registers
 * every tool (with its full schema) in [McpToolCatalog]: the single source of
 * truth the `mcp_tools` directory skill reads from. No individual tool is
 * registered as a [Skill] at startup; tools become visible and callable only
 * after the model searches for them and the directory skill materializes them
 * into the current session. A server that fails to connect is logged and
 * skipped: the rest still register. [close] tears down every connection.
 *
 * connect(configs) starts each configured server, performs the initialize
 * handshake, lists its tools, and registers every advertised tool in
 * [McpToolCatalog]. close() closes every live connection and drops the
 * catalog: it is safe to call multiple times and when nothing connected.
 */
class McpConnectionManager {
  private val clients = mutableListOf<McpClient>()

  suspend fun connect(configs: List<McpServerConfig>) {
    for ((name, command, workingDir, env) in configs) {
      try {
        val client = McpClient(
          StdioMcpClient(
            env = env,
            command = command,
            workingDir = workingDir?.let(::File)
          )
        )
        clients.add(client)
        client.start()
        client.initialize()
        val tools = client.listTools()
        tools.forEach { tool -> McpToolCatalog.register(tool, client) }
        logger.info("Connected MCP server '{}': {} tool(s)", name, tools.size)
      } catch (connectionException: Exception) {
        logger.warn("Failed to connect MCP server '$name': ${connectionException.message}")
      }
    }
  }

  fun close() {
    clients.forEach { client -> runCatching { client.close() } }
    clients.clear()
    McpToolCatalog.clear()
  }
}
