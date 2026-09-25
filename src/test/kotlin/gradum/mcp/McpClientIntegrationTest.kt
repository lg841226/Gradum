/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpClientIntegrationTest.kt  2026-09-25 18:00:00 Changed by gwy
 */

package gradum.mcp

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import gradum.mcp.transport.StdioMcpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end test of the hand-written stdio transport against a small Python
 * MCP server. Skips silently when no Python interpreter is on PATH, so the
 * suite stays cross-platform green.
 */
class McpClientIntegrationTest {

  @Test
  fun `initialize listTools callTool round trip over stdio`() {
    val python = findPython() ?: return

    val script = writeServerScript()
    try {
      val transport = StdioMcpClient(command = listOf(python, script.absolutePath))
      transport.start()
      val client = McpClient(transport)
      try {
        runBlocking {
          client.initialize()
          val tools = client.listTools()
          assertEquals(listOf("echo"), tools.map { it.name })
          assertEquals("Echo back the input", tools.single().description)

          val result = client.callTool("echo", buildJsonObject { put("text", "hi") })
          val echoed = result
            .jsonObject["content"]
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
          assertEquals("hi", echoed)
        }
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
    val file = File.createTempFile("gradum-mcp-test-server", ".py")
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
