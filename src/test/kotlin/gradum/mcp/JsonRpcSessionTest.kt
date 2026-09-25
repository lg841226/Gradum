/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JsonRpcSessionTest.kt  2026-09-25 18:00:00 Changed by gwy
 */

package gradum.mcp

import gradum.mcp.jsonrpc.JsonRpcSession
import gradum.mcp.jsonrpc.RpcException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonRpcSessionTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `routes concurrent calls by id, not arrival order`() = runBlocking {
    val sentBodies = mutableListOf<String>()
    val session = JsonRpcSession(
      sendFrame = { frameBytes ->
        sentBodies += frameBytes.toString(Charsets.UTF_8).substringAfter("\r\n\r\n")
      }
    )

    val callA = async { session.call("tools/call", timeoutMillis = 5_000) }
    val callB = async { session.call("tools/call", timeoutMillis = 5_000) }
    yield()

    // sentBodies now carries both request bodies; resolve them in reverse order.
    val ids = sentBodies.map { body ->
      json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content.toLong()
    }
    session.handleFrame("""{"jsonrpc":"2.0","id":${ids[1]},"result":"B"}""")
    session.handleFrame("""{"jsonrpc":"2.0","id":${ids[0]},"result":"A"}""")

    val resultB = (callB.await() as JsonPrimitive).content
    val resultA = (callA.await() as JsonPrimitive).content
    assertTrue(resultA == "A" && resultB == "B")
  }

  @Test
  fun `surfaces a json rpc error as an exception`() = runBlocking {
    val session = JsonRpcSession(sendFrame = {})
    val call = async {
      runCatching { session.call("tools/list", timeoutMillis = 5_000) }
    }
    yield()
    session.handleFrame(
      """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}"""
    )
    val outcome = call.await()
    assertTrue(outcome.isFailure)
    assertTrue(outcome.exceptionOrNull() is RpcException)
  }

  @Test
  fun `times out when no response arrives`() = runBlocking {
    val session = JsonRpcSession(sendFrame = {})
    assertFailsWith<TimeoutCancellationException> {
      session.call("tools/list", timeoutMillis = 50)
    }
  }
}
