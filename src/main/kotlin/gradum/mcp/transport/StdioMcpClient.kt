package gradum.mcp.transport

import gradum.jsonrpc.FrameCodec
import gradum.jsonrpc.JsonRpcSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.TimeUnit


internal class StdioMcpClient(
  private val command: List<String>,
  private val workingDir: File? = null,
  private val env: Map<String, String> = emptyMap()
) : AutoCloseable {

  private val frameCodec = FrameCodec()
  private var process: Process? = null
  private var scope: CoroutineScope? = null

  lateinit var session: JsonRpcSession
    private set

  fun start() {
    check(this@StdioMcpClient.process == null) { "MCP transport already started" }

    val builder = ProcessBuilder(command)
    builder.directory(workingDir)
    builder.environment().putAll(env)

    val process = builder.start()
    this@StdioMcpClient.process = process

    session = JsonRpcSession(
      sendFrame = { bytes ->
        process.outputStream.write(bytes)
        process.outputStream.flush()
      },
    )

    val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope = jobScope
    jobScope.launch {
      frameCodec.frames(stdoutBytes(process)).collect { message ->
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
  }.flowOn(Dispatchers.IO)

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
