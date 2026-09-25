/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * FrameCodecTest.kt  2026-09-25 18:00:00 Changed by gwy
 */

package gradum.mcp

import gradum.mcp.transport.FrameCodec
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FrameCodecTest {

  private val codec = FrameCodec()

  @Test
  fun `encodes a message with a trailing newline`() {
    val json = "{\"a\":1}"
    val expected = (json + "\n").toByteArray(Charsets.UTF_8)
    assertContentEquals(expected, FrameCodec.encode(json))
  }

  @Test
  fun `emits a complete message from a single chunk`() {
    val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
    val decoded = runBlocking { codec.frames(flow { emit(FrameCodec.encode(json)) }).toList() }
    assertEquals(listOf(json), decoded)
  }

  @Test
  fun `handles a half frame split across chunks`() {
    val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"ok\"}"
    val bytes = FrameCodec.encode(json)
    val decoded = runBlocking {
      codec.frames(
        flow {
          emit(bytes.copyOfRange(0, 5))
          emit(bytes.copyOfRange(5, bytes.size - 3))
          emit(bytes.copyOfRange(bytes.size - 3, bytes.size))
        }
      ).toList()
    }
    assertEquals(listOf(json), decoded)
  }

  @Test
  fun `handles two frames coalesced into one chunk`() {
    val a = "{\"jsonrpc\":\"2.0\",\"id\":1}"
    val b = "{\"jsonrpc\":\"2.0\",\"id\":2}"
    val decoded = runBlocking {
      codec.frames(flow { emit(FrameCodec.encode(a) + FrameCodec.encode(b)) }).toList()
    }
    assertEquals(listOf(a, b), decoded)
  }

  @Test
  fun `preserves order across many frames`() {
    val messages = (0..9).map { "{\"jsonrpc\":\"2.0\",\"id\":$it,\"method\":\"m\"}" }
    val decoded = runBlocking {
      codec.frames(flow { messages.forEach { emit(FrameCodec.encode(it)) } }).toList()
    }
    assertEquals(messages, decoded)
  }
}
