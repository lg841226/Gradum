/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SaveFileSkill.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum.skill

import gradum.*
import io.ktor.utils.io.charsets.*
import java.io.File
import java.nio.file.Path

private const val MAXIMUM_CONTENT_SIZE: Int = 512 * 1024

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
   * Keep only the current call's `content` in history. After the
   * first `save_file`, the model knows what it wrote (the call's
   * arguments are still in the preceding assistant message) and
   * can re-`read_file` the path on disk if it later needs the
   * bytes back — so the `content` field of older `save_file`
   * results is purely context bloat. Stripped from older tool
   * messages by [gradum.skill.Skill.compactHistory]'s default
   * implementation; the current call's `content` is always
   * returned to the LLM in full.
   */
  override val historyKeepCount: Int = 1
  override val historyVolatileKeys: List<String> = listOf("content")

  /**
   * Returns the OpenAI-style function schema for this skill.
   *
   * Defines four parameters:
   * - `path` (required): file path to write
   * - `content` (required): content to write
   * - `mode` (optional): `overwrite` or `append`
   * - `encoding` (optional): character encoding, defaults to `UTF-8`
   */
  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimple = context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to if (useSimple) "Create or overwrite a file" else description,
        "parameters" to mapOf(
          "type" to "object",
          "properties" to if (useSimple) simpleProperties() else cloudProperties(),
          "required" to listOf("path", "content"),
        ),
      ),
    )
  }

  private fun simpleProperties(): Map<String, Any> = mapOf(
    "path" to mapOf(
      "type" to "string",
      "description" to "File path to write. Relative paths are resolved from the project root. Parent directories are created automatically if they do not exist.",
    ),
    "content" to mapOf(
      "type" to "string",
      "description" to "Content to write to the file. This is the exact text that will be written. Must not be empty.",
    ),
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "path" to mapOf(
      "type" to "string",
      "description" to "File path to write. Parent directories are created if missing.",
    ),
    "content" to mapOf(
      "type" to "string",
      "description" to "Content to write to the file.",
    ),
    "mode" to mapOf(
      "type" to "string",
      "description" to "Write mode: 'overwrite' (default) replaces entire file, 'append' adds to end.",
      "enum" to listOf("overwrite", "append")
    ),
    "encoding" to mapOf(
      "type" to "string",
      "description" to "Character encoding for the file. Defaults to 'UTF-8'.",
      "enum" to listOf(
        "UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE",
        "ISO-8859-1", "GBK", "GB2312", "US-ASCII"
      )
    ),
  )

  /**
   * Writes content to a file at the given path.
   *
   * Validates the path, checks content size against [MAXIMUM_CONTENT_SIZE],
   * then writes according to writeMode. Creates parent directories
   * automatically if they don't exist.
   *
   * @param arguments Map containing `path`, `content`, and optional `mode`, `encoding`
   * @return [SkillResult.Success] with path, bytesWritten, totalLines, created, mode, encoding
   */
  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val fileContent: String = arguments["content"] as? String ?: ""
    val writeMode: String = arguments["mode"] as? String ?: "overwrite"
    val encodingName: String = arguments["encoding"] as? String ?: "UTF-8"
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE

    if (filePath.isBlank()) {
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a file path in the 'path' parameter."
        )
      )
    }

    val fileCharset: Charset = try {
      Charsets.forName(encodingName)
    } catch (_: Exception) {
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Unsupported encoding: $encodingName",
          fixHint = "Use a supported encoding: UTF-8, UTF-16, ISO-8859-1, GBK, US-ASCII."
        )
      )
    }

    if (fileContent.isBlank() && writeMode == "overwrite")
      return makeFailure(
        ErrorCode.EMPTY_RESULT,
        buildXmlError(
          code = "EMPTY_RESULT",
          message = "Content is blank. Use append mode or provide non-empty content.",
          fixHint = "Provide content to write, or set mode='append' to add to an existing file."
        )
      )

    val contentBytes: ByteArray = fileContent.toByteArray(fileCharset)
    if (contentBytes.size > MAXIMUM_CONTENT_SIZE)
      return makeFailure(
        ErrorCode.FILE_TOO_LARGE,
        buildXmlError(
          code = "FILE_TOO_LARGE",
          message = "Content too large: ${contentBytes.size} bytes (max: $MAXIMUM_CONTENT_SIZE bytes).",
          fixHint = "Reduce the content size or split it into smaller writes."
        ),
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
    val targetFile: File = resolvedPath.toFile()
    val wasCreated: Boolean = !targetFile.exists()
    val previousSize: Long = if (writeMode == "append" && !wasCreated) targetFile.length() else 0L

    return try {
      if (isBlockedPaths(resolvedPath)) {
        return makeFailure(
          ErrorCode.PERMISSION_DENIED,
          buildXmlError(
            code = "PERMISSION_DENIED",
            message = "Writing to '${resolvedPath}' is not allowed for security reasons.",
            fixHint = "Choose a different file path outside protected system directories."
          )
        )
      }

      targetFile.parentFile?.mkdirs()

      if (writeMode == "append") {
        targetFile.appendText(fileContent, fileCharset)
      } else targetFile.writeText(fileContent, fileCharset)

      val bytesWritten: Long = targetFile.length()

      val totalLines: Int = if (writeMode == "append") {
        targetFile.readLines(fileCharset).size
      } else fileContent.lines().size

      if (useSimpleOutput) {
        makeSuccess(
          mapOf(
            "path" to resolvedPath.toString(),
            "bytesWritten" to bytesWritten,
            "created" to wasCreated,
          )
        )
      } else {
        makeSuccess(
          buildMap {
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
        ErrorCode.IO_ERROR,
        buildXmlError(
          code = "IO_ERROR",
          message = fileWriteException.message ?: "Failed to write file.",
          fixHint = "This is not your fault. Check file permissions and disk space."
        ),
        mapOf("path" to resolvedPath.toString())
      )
    }
  }
}

private val blockedPathPrefixes: List<String> = listOf(
  "/etc", "/usr", "/var", "/boot", "/bin", "/sbin",
  "/lib", "/lib64", "/opt",
  "/System", "/Library", "/Applications", "/private"
)

private val blockedHomeSubdirectories: List<String> = listOf(
  ".ssh", ".gnupg", ".aws", ".kube", ".netrc",
  ".pypirc", ".npmrc", ".docker",
)

private val exactBlockedPaths: List<String> = listOf("/", "/dev", "/proc", "/sys")

private fun isBlockedPaths(targetPath: Path): Boolean {
  val absolutePath = targetPath.toAbsolutePath().normalize()
  val pathString = absolutePath.toString()

  if (pathString in exactBlockedPaths) return true

  for (blockedPrefix in blockedPathPrefixes) {
    if (pathString.startsWith(blockedPrefix)) return true
  }

  val homeDirectory: String = System.getProperty("user.home") ?: return false
  for (protectedSubdir in blockedHomeSubdirectories) {
    val protectedPath = "$homeDirectory/$protectedSubdir"
    if (pathString.startsWith(protectedPath)) return true
  }

  return false
}
