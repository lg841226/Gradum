package gradum.mcp

import gradum.mcp.transport.StdioMcpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

private val logger: Logger = LoggerFactory.getLogger("McpConnectionManager")

data class McpConnectionFailure(
  val serverName: String,
  val reason: String
)

/**
 * A configured MCP stdio server the user declared in settings.json.
 * [command] is the executable and arguments that spawn the server process;
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
 * registered as a [gradum.skill.Skill] at startup; tools become visible and callable only
 * after the model searches for them and the directory skill materializes them
 * into the current session. A server that fails to connect is collected and
 * returned instead of logged, so the caller can decide when to report it;
 * the rest still register. [close] tears down every connection.
 *
 * connect(configs) starts each configured server, performs the initialize
 * handshake, lists every advertised tool in [McpToolCatalog], and returns the
 * failures for the caller to report with [reportFailures]. close() closes every
 * live connection and drops the catalog: it is safe to call multiple times and
 * when nothing connected.
 */
class McpConnectionManager {
  private val clients = mutableListOf<McpClient>()

  suspend fun connect(configs: List<McpServerConfig>): List<McpConnectionFailure> {
    val failures = mutableListOf<McpConnectionFailure>()
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
        logger.debug("Connected MCP server '{}': {} tool(s)", name, tools.size)
      } catch (connectionException: Exception) {
        val reason: String = connectionException.message
          ?: connectionException::class.simpleName
          ?: "unknown error"
        failures.add(McpConnectionFailure(serverName = name, reason = reason))
      }
    }
    return failures
  }

  fun reportFailures(failures: List<McpConnectionFailure>) {
    failures.forEach { failure ->
      logger.warn("Failed to connect MCP server '{}': {}", failure.serverName, failure.reason)
    }
  }

  fun close() {
    clients.forEach { client -> runCatching { client.close() } }
    clients.clear()
    McpToolCatalog.clear()
  }
}
