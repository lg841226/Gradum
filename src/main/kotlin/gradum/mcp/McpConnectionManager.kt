/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpConnectionManager.kt  2026-09-25 23:13:09 Changed by gwy
 */

package gradum.mcp

import gradum.mcp.transport.StdioMcpClient
import gradum.skill.Skill
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
 * each server, performs the initialize handshake, lists its tools, and wraps
 * every tool in a [McpSkillAdapter] ready for [gradum.skill.SkillRegistry].
 * A server that fails to connect is logged and skipped — the rest still
 * register. [close] tears down every connection.
 */
class McpConnectionManager {
  private val clients = mutableListOf<McpClient>()

  /** Connects to each [configs] entry and returns one adapter per advertised tool. */
  suspend fun connect(configs: List<McpServerConfig>): List<Skill> {
    val adapters = mutableListOf<McpSkillAdapter>()
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
        tools.forEach { tool -> adapters.add(McpSkillAdapter(tool, client)) }
        logger.info("Connected MCP server '{}': {} tool(s)", name, tools.size)
      } catch (connectionException: Exception) {
        logger.warn("Failed to connect MCP server '$name': ${connectionException.message}")
      }
    }
    return adapters
  }

  /** Closes every live connection. Safe to call multiple times and when nothing connected. */
  fun close() {
    clients.forEach { client -> runCatching { client.close() } }
    clients.clear()
  }
}
