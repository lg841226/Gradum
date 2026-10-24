package gradum.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger: Logger = LoggerFactory.getLogger("EventStreamDump")

private const val DUMP_FILE_NAME: String = "gradum-events.log"

object EventStreamDump {

  val file: Path = Path.of(System.getProperty("java.io.tmpdir") ?: ".", DUMP_FILE_NAME)

  @Volatile
  private var enabled: Boolean = false

  fun configure(enabled: Boolean) {
    this.enabled = enabled
    if (enabled) logger.debug("Event stream dump enabled -> ${file.toAbsolutePath()}")
  }

  fun append(ndjsonLine: String) {
    if (!enabled) return
    try {
      synchronized(this) {
        Files.writeString(
          file,
          ndjsonLine,
          Charsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      }
    } catch (writeFailure: Exception) {
      logger.warn("Event dump append failed: ${writeFailure.message}")
    }
  }
}
