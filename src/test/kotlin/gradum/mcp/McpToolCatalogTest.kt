/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpToolCatalogTest.kt  2026-09-26 Changed by gwy
 */

package gradum.mcp

import gradum.SkillResult
import gradum.ToolMode
import gradum.mcp.transport.StdioMcpClient
import gradum.skill.SkillContext
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies the on-demand materialization flow behind the `mcp_tools` directory
 * skill: tools are grouped by function, found by search, and a found tool's
 * full schema is injected for the session ([SkillContext.materializedMcpTools])
 * while READ_ONLY mode stays gated. Uses the same tiny Python MCP echo server
 * as [McpSkillAdapterTest]; skips when no Python interpreter is on PATH.
 */
class McpToolCatalogTest {

  @BeforeTest
  fun setUp() {
    McpToolCatalog.clear()
  }

  @AfterTest
  fun tearDown() {
    McpToolCatalog.clear()
  }

  @Test
  fun `groups, searches, and materializes tools by function`() {
    val python = findPython() ?: return

    val script = writeServerScript()
    try {
      val transport = StdioMcpClient(command = listOf(python, script.absolutePath))
      transport.start()
      val client = McpClient(transport)
      try {
        runBlocking { client.initialize() }
        val tools = runBlocking { client.listTools() }
        tools.forEach { tool -> McpToolCatalog.register(tool, client) }

        assertEquals(8, McpToolCatalog.toolCount())

        // Functional grouping from tool names.
        assertEquals("Navigation", McpToolCatalog.groupOf("browser_navigate"))
        assertEquals("Interaction", McpToolCatalog.groupOf("browser_click"))
        assertEquals("Typing", McpToolCatalog.groupOf("browser_type"))
        assertEquals("Snapshot & Visual", McpToolCatalog.groupOf("browser_snapshot"))
        assertEquals("Network & Requests", McpToolCatalog.groupOf("browser_network_requests"))
        assertEquals("Forms", McpToolCatalog.groupOf("browser_fill"))
        assertEquals("Other", McpToolCatalog.groupOf("echo"))

        // Directory listing exposes every tool once, grouped.
        val listing = McpToolCatalog.groupedListing()
        val names = listing.flatMap { it.tools }.map { it.name }
        assertEquals(tools.size, names.size)

        // Keyword search finds the network tool (and only it).
        val networkMatch = McpToolCatalog.search("network")
        assertEquals(listOf("browser_network_requests"), networkMatch.map { it.name })

        // Blank query returns every tool.
        assertEquals(8, McpToolCatalog.search("").size)

        // Search drives materialization: the skill adds matched names to the session set.
        val context = SkillContext(toolMode = ToolMode.AGENT, projectRoot = "")
        val skill = McpToolsSkill()
        val result: SkillResult = skill.execute(mapOf("query" to "snapshot"), context)
        assertTrue(result is SkillResult.Success)
        assertTrue("browser_snapshot" in context.materializedMcpTools)
        assertEquals(1, (result.data["matched"] as? Number)?.toInt())

        // The Agent's per-turn build surfaces the materialized tool's full schema.
        val schemas = McpToolCatalog.fullSchemas(context.materializedMcpTools.names, ToolMode.AGENT)
        assertEquals(listOf("browser_snapshot"), schemas.map { (it["function"] as Map<*, *>)["name"] })

        // READ_ONLY mode is gated out: MCP tools may mutate state.
        assertTrue(McpToolCatalog.fullSchemas(context.materializedMcpTools.names, ToolMode.READ_ONLY).isEmpty())

        // No-argument listing succeeds and is non-empty.
        val listingResult = skill.execute(emptyMap(), SkillContext(toolMode = ToolMode.AGENT, projectRoot = ""))
        assertTrue(listingResult is SkillResult.Success)
        val groups = listingResult.data["groups"] as List<*>
        assertTrue(groups.isNotEmpty())

        // A no-match query reports zero and does not materialize anything.
        val missContext = SkillContext(toolMode = ToolMode.AGENT, projectRoot = "")
        val missResult = skill.execute(mapOf("query" to "zztop-nonexistent"), missContext)
        assertTrue(missResult is SkillResult.Success)
        assertEquals(0, (missResult.data["matched"] as? Number)?.toInt())
        assertTrue(missContext.materializedMcpTools.isEmpty)
      } finally {
        client.close()
      }
    } finally {
      script.delete()
    }
  }

  private fun findPython(): String? {
    for (candidate in listOf("python3", "python")) {
      try {
        val probe = ProcessBuilder(candidate, "--version").start()
        probe.waitFor()
        if (probe.exitValue() == 0) return candidate
      } catch (_: IOException) {
        // not on PATH; try the next candidate
      }
    }
    return null
  }

  private fun writeServerScript(): File {
    val file = File.createTempFile("gradum-mcp-catalog-server", ".py")
    file.deleteOnExit()
    file.writeText(SERVER_SCRIPT)
    return file
  }

  private companion object {
    const val SERVER_SCRIPT = """
import sys
import json


def read_frame():
    line = sys.stdin.buffer.readline()
    if not line:
        return None
    line = line.strip()
    if not line:
        return read_frame()
    return json.loads(line.decode("utf-8"))


def send(obj):
    sys.stdout.buffer.write((json.dumps(obj) + "\n").encode("utf-8"))
    sys.stdout.buffer.flush()


def tool(name, description, required, props):
    return {"name": name, "description": description,
            "inputSchema": {"type": "object",
                            "properties": {k: {"type": v, "description": k}
                                           for k, v in props.items()},
                            "required": required}}


TOOLS = [
    tool("browser_navigate", "Navigate the browser to a URL", ["url"], {"url": "string"}),
    tool("browser_click", "Click an element", ["selector"], {"selector": "string"}),
    tool("browser_type", "Type text into a field", ["selector", "text"], {"selector": "string", "text": "string"}),
    tool("browser_snapshot", "Capture an accessible snapshot of the page", [], {}),
    tool("browser_network_requests", "List recent network requests", [], {}),
    tool("browser_fill", "Fill a form field", ["selector", "value"], {"selector": "string", "value": "string"}),
    tool("browser_close", "Close the browser", [], {}),
    tool("echo", "Echo back the input", ["text"], {"text": "string"}),
]


while True:
    msg = read_frame()
    if msg is None:
        break
    method = msg.get("method")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {
            "protocolVersion": "2025-03-26",
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "test-server", "version": "1.0"}}})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": TOOLS}})
    elif method == "tools/call":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {
            "content": [{"type": "text", "text": "ok"}]}})
"""
  }
}
