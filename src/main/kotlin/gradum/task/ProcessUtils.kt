package gradum.task

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private val logger: Logger = LoggerFactory.getLogger("ProcessUtils")

// Shared reader pool for stdout/stderr draining that happens before waitFor.
// Virtual threads: the pool tasks block on child-process pipes, so each drain
// gets a cheap virtual thread instead of a pooled platform thread.
private val processStreamReaderPool: ExecutorService =
  Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("gradum-process-stream-", 0).factory()
  )

/**
 * Enumerates all descendants of [process] and returns them. This must be
 * called BEFORE sending any signal to the parent, because once the parent
 * dies the children become orphans (adopted by init) and are no longer
 * findable via [ProcessHandle.descendants].
 */
fun captureDescendants(process: Process): List<ProcessHandle> {
  return try {
    process.toHandle().descendants().toList()
  } catch (processHandleException: Exception) {
    logger.debug("Failed to capture descendants for process {}: {}", process.pid(), processHandleException.message)
    emptyList()
  }
}

/**
 * Kills the entire process tree rooted at [process], not just the immediate
 * child. Without this, `destroyForcibly()` on a `sh -c "command"` parent
 * only kills the shell: the actual command becomes an orphan and continues
 * running.
 *
 * The caller must pass pre-enumerated [descendants] (captured before any
 * signal was sent) to avoid the orphan race.
 */
fun killProcessTree(process: Process, descendants: List<ProcessHandle>) {
  for (descendant in descendants) {
    try {
      descendant.destroyForcibly()
    } catch (destroyException: Exception) {
      logger.debug("Failed to destroy descendant {}: {}", descendant.pid(), destroyException.message)
    }
  }
  process.destroyForcibly()
}

/**
 * Kicks off a background drain of [inputStream] and returns a Future that
 * resolves to the captured text once the stream is exhausted. Must be
 * called for BOTH stdout and stderr BEFORE `waitFor`, otherwise a
 * large-output child blocks on a full pipe and deadlocks the wait.
 */
fun startStreamReader(inputStream: InputStream, label: String, maxChars: Int): Future<String> {
  return processStreamReaderPool.submit<String> {
    readStreamOutput(inputStream, label, maxChars)
  }
}

/**
 * Reads [inputStream] until EOF or until [maxChars] characters have been
 * accumulated. On truncation, appends a marker and discards the remaining
 * stream without buffering it.
 */
fun readStreamOutput(inputStream: InputStream, label: String, maxChars: Int): String {
  return try {
    val outputBuilder = StringBuilder()
    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { bufferedReader ->
      val charBuffer = CharArray(size = 4096)
      while (true) {
        val charsRead: Int = bufferedReader.read(charBuffer, 0, charBuffer.size)
        if (charsRead < 0) break
        outputBuilder.appendRange(value = charBuffer, startIndex = 0, endIndex = charsRead)
        if (outputBuilder.length >= maxChars) {
          outputBuilder.append(
            "\n(output truncated at ${maxChars / 1024} KiB, the full $label is not shown)"
          ).also {
            while (bufferedReader.read(charBuffer, 0, charBuffer.size) >= 0) {
              // Discard remaining output to free the pipe
            }
          }
          logger.debug("Discarded remaining output for $label after truncation")
          break
        }
      }
    }
    outputBuilder.toString()
  } catch (streamReadException: Exception) {
    val reason: String = streamReadException.message
      ?: streamReadException::class.simpleName ?: "unknown I/O error"
    logger.warn("Failed to read {} stream: {}", label, reason)
    "(stream read failed for $label: $reason)"
  }
}
