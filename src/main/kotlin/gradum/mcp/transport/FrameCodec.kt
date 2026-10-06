package gradum.mcp.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

/**
 * Frames MCP stdio messages with the newline-delimited framing from the
 * current MCP spec: every JSON-RPC message is a single line terminated by
 * `\n`, and messages MUST NOT contain embedded newlines.
 *
 * The reader accumulates raw bytes from a [Flow] of chunks and slices out one
 * complete message at a time, tolerating both half-frames (a message body
 * split across chunks) and coalesced frames (several messages arriving in one
 * chunk). Encoding appends a single `\n` terminator to a JSON string.
 *
 * frames(bytes) returns a flow of decoded JSON message bodies in frame
 * order: a message is emitted only once its full line is buffered, so the
 * consumer never sees a truncated message. takeNextMessage extracts one
 * newline-terminated message from the buffer, or null when no complete line
 * has arrived yet: it drops the consumed line while keeping any trailing
 * bytes for the next read, strips an optional CR so CRLF peers are handled,
 * and skips blank lines. The companion encode(json) wraps the JSON into a
 * newline-delimited message: a single `\n` terminator is appended, and the
 * JSON itself must be a single line without embedded newlines.
 */
internal class FrameCodec {

  private val buffer = ByteArrayOutputStream()

  fun frames(bytes: Flow<ByteArray>): Flow<String> = flow {
    bytes.collect { chunk ->
      buffer.write(chunk)
      while (true) {
        val message = takeNextMessage() ?: break
        emit(message)
      }
    }
  }

  private fun takeNextMessage(): String? {
    while (true) {
      val data = buffer.toByteArray()
      val newlineIndex = indexOf(data, LF)
      if (newlineIndex < 0) return null

      val rest = newlineIndex + 1
      buffer.reset()
      if (rest < data.size) {
        buffer.write(data, rest, data.size - rest)
      }

      var length = newlineIndex
      if (length > 0 && data[length - 1] == CR) length -= 1
      if (length > 0) return String(data, 0, length, Charsets.UTF_8)
    }
  }

  private fun indexOf(data: ByteArray, needle: Byte): Int {
    for (i in data.indices) {
      if (data[i] == needle) return i
    }
    return -1
  }

  companion object {
    private const val LF: Byte = '\n'.code.toByte()
    private const val CR: Byte = '\r'.code.toByte()
    fun encode(json: String): ByteArray {
      val body = json.toByteArray(Charsets.UTF_8)
      val framed = ByteArray(body.size + 1)
      System.arraycopy(body, 0, framed, 0, body.size)
      framed[body.size] = LF
      return framed
    }
  }
}
