/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpSkillAdapterTest.kt  2026-09-25 Changed by gwy
 */

package gradum.mcp

import gradum.SkillResult
import gradum.ToolMode
import gradum.mcp.transport.StdioMcpClient
import gradum.skill.SkillContext
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies that a single MCP tool is wrapped into a [Skill]: its schema is
 * trimmed to the required parameters and [Skill.execute] forwards the caller's
 * arguments to the server and returns the rendered content. Uses the same tiny
 * Python echo server as [McpClientIntegrationTest]; skips when no Python
 * interpreter is on PATH.
 */
class McpSkillAdapterTest {

  @Test
  fun `wraps a tool as a skill with schema and working execute`() {
    val python = findPython() ?: return

    val script = writeServerScript()
    try {
      val transport = StdioMcpClient(command = listOf(python, script.absolutePath))
      transport.start()
      val client = McpClient(transport)
      try {
        runBlocking { client.initialize() }
        val tool = runBlocking { client.listTools() }.single()
        val adapter = McpSkillAdapter(tool, client)

        assertEquals("echo", adapter.skillName)
        assertEquals("Echo back the input", adapter.description)

        val schema = adapter.getSchema()
        val function = schema["function"] as Map<*, *>
        val parameters = function["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>
        assertEquals(listOf("text"), parameters["required"])
        assertEquals("string", (properties["text"] as Map<*, *>)["type"])

        val context = SkillContext(toolMode = ToolMode.AGENT, projectRoot = "")
        val result: SkillResult = adapter.execute(mapOf("text" to "hi"), context)
        assertTrue(result is SkillResult.Success)
        assertEquals("hi", result.data["content"])
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
    val file = File.createTempFile("gradum-mcp-adapter-server", ".py")
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
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [
            {"name": "echo", "description": "Echo back the input",
             "inputSchema": {"type": "object",
                             "properties": {"text": {"type": "string"}},
                             "required": ["text"]}}]}})
    elif method == "tools/call":
        text = msg.get("params", {}).get("arguments", {}).get("text", "")
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {
            "content": [{"type": "text", "text": text}]}})
"""
  }
}
