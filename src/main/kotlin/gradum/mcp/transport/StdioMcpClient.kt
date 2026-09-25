/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * StdioMcpClient.kt  2026-09-25 18:00:00 Changed by gwy
 */

package gradum.mcp.transport

import gradum.mcp.jsonrpc.JsonRpcSession
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Spawns an MCP server as a child process and bridges its stdin/stdout to a
 * [JsonRpcSession]. This is the stdio transport: the reader coroutine drains
 * the child's stdout bytes, frames them, and feeds each decoded message to
 * the session; [session] writes request frames back to the child's stdin.
 *
 * The child's stderr is deliberately kept separate (never merged into
 * stdout) so it cannot corrupt the newline-delimited frame stream.
 */
internal class StdioMcpClient(
  private val command: List<String>,
  private val workingDir: File? = null,
  private val env: Map<String, String> = emptyMap(),
) : AutoCloseable {

  private val frameCodec = FrameCodec()
  private var process: Process? = null
  private var scope: CoroutineScope? = null

  lateinit var session: JsonRpcSession
    private set

  /** Starts the child process and the background reader. Safe to call once. */
  fun start() {
    check(process == null) { "MCP transport already started" }

    val builder = ProcessBuilder(command)
    builder.directory(workingDir)
    builder.environment().putAll(env)

    val proc = builder.start()
    process = proc

    session = JsonRpcSession(
      sendFrame = { bytes ->
        proc.outputStream.write(bytes)
        proc.outputStream.flush()
      },
    )

    val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope = jobScope
    jobScope.launch {
      frameCodec.frames(stdoutBytes(proc)).collect { message ->
        session.handleFrame(message)
      }
    }
  }

  private fun stdoutBytes(proc: Process): Flow<ByteArray> = flow {
    val input = proc.inputStream
    val chunk = ByteArray(8192)
    while (true) {
      val read = input.read(chunk)
      if (read == -1) break
      emit(chunk.copyOf(read))
    }
  }

  override fun close() {
    scope?.cancel()
    process?.let { proc ->
      proc.destroy()
      proc.waitFor(2, TimeUnit.SECONDS)
      proc.destroyForcibly()
    }
    process = null
  }
}
