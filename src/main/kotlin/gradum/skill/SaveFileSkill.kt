/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SaveFileSkill.kt  2026-08-25 17:08:55 Changed by gwy
 */

package gradum.skill

import gradum.*
import gradum.utils.ProtectedPaths
import io.ktor.utils.io.charsets.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("SaveFileSkill")

private val MAXIMUM_CONTENT_SIZE: Int = GradumConfig.WRITE_MAX_FILE_SIZE

/**
 * Writes content to a file, creating parent directories as needed.
 *
 * Used by the agent to persist new files or fully overwrite existing ones.
 * For partial edits, prefer [EditFileSkill].
 *
 * Supports two modes via the `mode` parameter:
 * - `overwrite` (default): replaces the entire file content.
 * - `append`: appends content to the end of an existing file.
 *
 * Bounded by [MAXIMUM_CONTENT_SIZE] to prevent accidental huge writes.
 */
class SaveFileSkill : Skill() {
  override val skillName: String = "save_file"
  override val alias: String = "Saved"
  override val description: String =
    "Create or overwrite a file. mode='overwrite' (default) or 'append'. Creates parent directories."

  /**
   * save_file mutates the filesystem. The agent's mode gate rejects
   * this call in READ_ONLY mode. EDIT is allowed because the
   * user has explicitly opted into write access.
   */
  override val allowedToolModes: Set<ToolMode> = setOf(
    ToolMode.AGENT, ToolMode.EDIT
  )

  /**
   * Keep only the current call's `content` in history. The LLM already
   * holds the bytes it wrote (in the call's arguments) and can re-read
   * the path on disk, so older results' `content` is context bloat.
   */
  override val historyKeepCount: Int = 1
  override val historyVolatileKeys: List<String> = listOf("content")

  override val simpleDescription: String = "Create or overwrite a file"

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "path",
      description = "File path to write. Relative paths are resolved from the project root. Parent directories are created automatically if they do not exist.",
      required = true,
    )
    string(
      name = "content",
      description = "Content to write to the file. This is the exact text that will be written. Must not be empty.",
      required = true,
    )
    cloudOnly {
      string(
        name = "mode",
        description = "Write mode: 'overwrite' (default) replaces entire file, 'append' adds to end.",
        enumValues = listOf("overwrite", "append"),
      )
      string(
        name = "encoding",
        description = "Character encoding for the file. Defaults to 'UTF-8'.",
        enumValues = listOf(
          "UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE",
          "ISO-8859-1", "GBK", "GB2312", "US-ASCII"
        ),
      )
    }
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val fileContent: String = arguments["content"] as? String ?: ""
    val writeMode: String = arguments["mode"] as? String ?: "overwrite"
    val encodingName: String = arguments["encoding"] as? String ?: "UTF-8"
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = context.isSimpleModel

    if (filePath.isBlank()) {
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a file path in the 'path' parameter."
        )
      )
    }

    val fileCharset: Charset = try {
      Charsets.forName(encodingName)
    } catch (encodingException: Exception) {
      logger.warn("Unsupported encoding '$encodingName': ${encodingException.message}", encodingException)
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Unsupported encoding: $encodingName",
          fixHint = "Use a supported encoding: UTF-8, UTF-16, ISO-8859-1, GBK, US-ASCII."
        )
      )
    }

    if (fileContent.isBlank() && writeMode == "overwrite")
      return makeFailure(
        code = ErrorCode.EMPTY_RESULT,
        message = buildXmlError(
          code = "EMPTY_RESULT",
          message = "Content is blank. Use append mode or provide non-empty content.",
          fixHint = "Provide content to write, or set mode='append' to add to an existing file."
        )
      )

    val contentBytes: ByteArray = fileContent.toByteArray(fileCharset)
    if (contentBytes.size > MAXIMUM_CONTENT_SIZE)
      return makeFailure(
        code = ErrorCode.FILE_TOO_LARGE,
        message = buildXmlError(
          code = "FILE_TOO_LARGE",
          message = "Content too large: ${contentBytes.size} bytes (max: $MAXIMUM_CONTENT_SIZE bytes).",
          fixHint = "Reduce the content size or split it into smaller writes."
        ),
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
    if (resolved.rejectionReason != null) {
      return makeFailure(
        code = ErrorCode.PERMISSION_DENIED,
        message = buildXmlError(
          code = "PERMISSION_DENIED",
          message = "Path is outside the project root: ${resolved.rejectionReason}",
          fixHint = "Use a path relative to the project root."
        ),
        mapOf("path" to filePath)
      )
    }
    val targetFile: File = resolvedPath.toFile()
    val wasCreated: Boolean = !targetFile.exists()
    val previousSize: Long = if (writeMode == "append" && !wasCreated) targetFile.length() else 0L

    return try {
      if (isBlockedPaths(targetPath = resolvedPath)) {
        return makeFailure(
          code = ErrorCode.PERMISSION_DENIED,
          message = buildXmlError(
            code = "PERMISSION_DENIED",
            message = "Writing to '${resolvedPath}' is not allowed for security reasons.",
            fixHint = "Choose a different file path outside protected system directories."
          )
        )
      }

      targetFile.parentFile?.mkdirs()

      if (writeMode == "append")
        targetFile.appendText(fileContent, fileCharset)
      else
        targetFile.writeText(fileContent, fileCharset)

      val bytesWritten: Long = targetFile.length()

      val totalLines: Int =
        if (writeMode == "append")
          targetFile.readLines(fileCharset).size
        else
          fileContent.lines().size

      if (useSimpleOutput) {
        makeSuccess(
          data = mapOf(
            "created" to wasCreated,
            "bytesWritten" to bytesWritten,
            "path" to resolvedPath.toString()
          )
        )
      } else {
        makeSuccess(
          data = buildMap {
            put("path", resolvedPath.toString())
            put("bytesWritten", bytesWritten)
            put("totalLines", totalLines)
            put("created", wasCreated)
            put("mode", writeMode)
            put("encoding", fileCharset.name())
            if (writeMode == "append" && !wasCreated) {
              put("previousSize", previousSize)
            }
          },
        )
      }
    } catch (fileWriteException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = fileWriteException.message ?: "Failed to write file.",
          fixHint = "This is not your fault. Check file permissions and disk space."
        ),
        context = mapOf("path" to resolvedPath.toString())
      )
    }
  }
}

private fun isBlockedPaths(targetPath: Path): Boolean =
  ProtectedPaths.isProtected(targetPath.toString())
