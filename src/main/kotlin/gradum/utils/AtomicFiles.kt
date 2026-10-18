package gradum.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger: Logger = LoggerFactory.getLogger("AtomicFiles")

object AtomicFiles {

  /**
   * Writes [content] to [targetFile] through a sibling temp file and an atomic
   * rename, so a crash mid-write never leaves a half-written file. Falls back
   * to a plain move where the filesystem cannot do `ATOMIC_MOVE`.
   */
  fun write(targetFile: Path, content: String) {
    Files.createDirectories(targetFile.parent)
    val tempFile: Path = targetFile.resolveSibling("${targetFile.fileName}.tmp-${System.nanoTime()}")
    Files.writeString(tempFile, content, Charsets.UTF_8)
    try {
      Files.move(
        tempFile,
        targetFile,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (moveException: AtomicMoveNotSupportedException) {
      logger.warn("ATOMIC_MOVE not supported on this filesystem; falling back: ${moveException.message}")
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
