/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JsonRpcSessionTest.kt  2026-09-26 Changed by gwy
 */
package gradum.mcp.jsonrpc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the client's tolerance of MCP stdio servers that violate the
 * framing contract by writing non-JSON lines to stdout.
 */
class JsonRpcSessionTest {

  @Test
  fun `non-JSON frames from the server are dropped without crashing`() {
    val methods = mutableListOf<String>()
    val session = JsonRpcSession(
      sendFrame = { },
      onNotification = { methods.add(it.method) }
    )

    // location-mcp prints this banner to stdout on startup — it is not JSON.
    session.handleFrame("Starting Location MCP server...")
    // A well-formed notification after the banner must still be delivered.
    session.handleFrame("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")

    assertEquals(listOf("notifications/tools/list_changed"), methods)
  }
}
