/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpConnectionManager.kt  2026-09-25 23:13:09 Changed by gwy
 */

package gradum.mcp

import gradum.mcp.transport.StdioMcpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

private val logger: Logger = LoggerFactory.getLogger("McpConnectionManager")

/**
 * A configured MCP stdio server the user declared in `settings.json`.
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
 * every tool (with its full schema) in [McpToolCatalog] — the single source of
 * truth the `mcp_tools` directory skill reads from. No individual tool is
 * registered as a [Skill] at startup; tools become visible and callable only
 * after the model searches for them and the directory skill materializes them
 * into the current session. A server that fails to connect is logged and
 * skipped — the rest still register. [close] tears down every connection.
 */
class McpConnectionManager {
  private val clients = mutableListOf<McpClient>()

  /** Connects to each [configs] entry and registers every advertised tool in [McpToolCatalog]. */
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

  /** Closes every live connection and drops the catalog. Safe to call multiple times and when nothing connected. */
  fun close() {
    clients.forEach { client -> runCatching { client.close() } }
    clients.clear()
    McpToolCatalog.clear()
  }
}
