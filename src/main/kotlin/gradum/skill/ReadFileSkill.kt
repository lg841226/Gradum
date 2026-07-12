/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadFileSkill.kt  2026-07-05 23:13:14 Changed by gwy
 */

package gradum.skill

import gradum.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.security.MessageDigest

private const val MAXIMUM_FILE_SIZE: Int = 1 * 1024 * 1024
private const val MAXIMUM_LINES: Int = 10000

/**
 * Reads file content for the agent.
 *
 * Files exceeding [MAXIMUM_FILE_SIZE] or [MAXIMUM_LINES] return
 * `FILE_TOO_LARGE` to keep conversation context bounded.
 *
 * **History retention is INT_MAX, not the default-strip pattern.**
 * `prepareHistoryResult` is allowed to drop `historyVolatileKeys`
 * from results beyond `historyKeepCount`, so stripping `content`
 * here would blind the agent from the third read onward in a
 * session — the result map would carry `path` (and maybe a few
 * metadata fields) but no body, so the LLM would have no way to
 * plan or verify subsequent edits against the file it had just
 * opened. The fix that set `historyKeepCount = Int.MAX_VALUE`
 * and `historyVolatileKeys = emptyList()` is locked in by
 * [gradum.skill.ReadFileSkillPrepareHistoryTest]. Do not
 * re-introduce a low `historyKeepCount` or add `content` to
 * `historyVolatileKeys` — context-window bloat is a separate
 * problem (truncation, summarization) and is not solved by
 * silently stripping the data the LLM needs.
 */
class ReadFileSkill : Skill() {

  override val alias: String = "Read"
  override val skillName: String = "read_file"
  override val description: String = "Read file content. Use line_range to read a section."

  override val historyKeepCount: Int = Int.MAX_VALUE
  override val historyVolatileKeys: List<String> = emptyList()

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimpleSchema =
      context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE

    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to if (useSimpleSchema) localDescription() else description,
        "parameters" to mapOf(
          "type" to "object",
          "properties" to if (useSimpleSchema) localProperties() else cloudProperties(),
          "required" to listOf("path"),
        ),
      ),
    )
  }

  private fun localDescription(): String =
    "Read file content. Returns content as a map of line numbers to line text." +
      " Use this before edit_file to see the exact text to replace."

  private fun localProperties(): Map<String, Any> = mapOf(
    "path" to mapOf(
      "type" to "string",
      "description" to "File path relative to project root, e.g. 'src/main.py'."
    ),
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "path" to mapOf(
      "type" to "string",
      "description" to "File path to read. Use relative path from current directory.",
    ),
    "lineRange" to mapOf(
      "type" to "string",
      "description" to "Line range to read. Format: 'start-end' (e.g., '12-22').",
    ),
    "line_range" to mapOf(
      "type" to "string",
      "description" to "Alias for lineRange. Format: 'start-end' (e.g., '12-22').",
    ),
  )

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val lineRange: String = (arguments["lineRange"] as? String ?: "")
      .ifBlank { arguments["line_range"] as? String ?: "" }
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    // NOTE: historyKeepCount is Int.MAX_VALUE and
    // historyVolatileKeys is empty so `prepareHistoryResult`
    // never strips the `content` field. Stripping content
    // would defeat the entire purpose of read_file — after
    // two reads the LLM would see a result with `path` but
    // no body, lose access to anything it had just opened,
    // and be unable to plan or verify edits against it. The
    // session-level `resetHistoryCount()` call in Agent keeps
    // the counter from leaking across sessions, so the
    // singleton skill instance is safe to reuse.

    if (filePath.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a current file path in the 'path' parameter."
        )
      )

    val resolvedPath: Path =
      if (projectRoot.isNotBlank() && !filePath.startsWith("/")) {
        Path.of(projectRoot, filePath).toAbsolutePath().normalize()
      } else
        Path.of(filePath).toAbsolutePath().normalize()

    val targetFile: File = resolvedPath.toFile()

    return try {
      val fileSize: Long = targetFile.length()
      if (fileSize > MAXIMUM_FILE_SIZE) {
        return makeFailure(
          ErrorCode.FILE_TOO_LARGE,
          buildXmlError(
            code = "FILE_TOO_LARGE",
            message = "File too large: $fileSize bytes (max: $MAXIMUM_FILE_SIZE bytes).",
            fixHint = "Use lineRange to read specific sections of the file."
          ),
          mapOf("path" to resolvedPath.toString(), "fileSize" to fileSize)
        )
      }

      val (startLineNumber, endLineNumber, selectedLines) = if (lineRange.isBlank()) {
        val allLines = targetFile.readLines(Charsets.UTF_8)
        if (allLines.size > MAXIMUM_LINES) {
          return makeFailure(
            ErrorCode.FILE_TOO_LARGE,
            buildXmlError(
              code = "FILE_TOO_LARGE",
              message = "File has ${allLines.size} lines (max: $MAXIMUM_LINES).",
              fixHint = "Use lineRange to read specific sections of the file."
            ),
            mapOf("path" to resolvedPath.toString(), "totalLines" to allLines.size)
          )
        }
        Triple(1, allLines.size, allLines)
      } else {
        val rangeParts = lineRange.split("-")
        if (rangeParts.size != 2) {
          return makeFailure(
            ErrorCode.INVALID_PARAMETER,
            buildXmlError(
              code = "INVALID_PARAMETER",
              message = "Invalid lineRange format. Use 'start-end' (e.g., '12-22').",
              fixHint = "Provide lineRange in the format 'start-end' with numeric values."
            ),
            mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange)
          )
        }

        val rawStart = parseRangeBound(rangeParts[0])
          ?: return makeFailure(
            ErrorCode.INVALID_PARAMETER,
            buildXmlError(
              code = "INVALID_PARAMETER",
              message = "Invalid lineRange start value: ${rangeParts[0]}",
              fixHint = "Provide a valid integer for the start line number."
            ),
            mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange)
          )

        val rawEnd = parseRangeBound(rangeParts[1])
          ?: return makeFailure(
            ErrorCode.INVALID_PARAMETER,
            buildXmlError(
              code = "INVALID_PARAMETER",
              message = "Invalid lineRange end value: ${rangeParts[1]}",
              fixHint = "Provide a valid integer for the end line number."
            ),
            mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange)
          )

        val actualStart = rawStart.coerceAtLeast(1)
        val actualEnd = rawEnd.coerceAtMost(Int.MAX_VALUE)
        val start = minOf(actualStart, actualEnd)
        val end = maxOf(actualStart, actualEnd)

        val lines = targetFile.useLines { it.drop(start - 1).take(end - start + 1).toList() }
        val actualEndLine = start + lines.size - 1

        Triple(start, actualEndLine, lines)
      }

      if (useSimpleOutput) {
        val numberedContent = selectedLines.mapIndexed { index, line ->
          (startLineNumber + index).toString() to line
        }.toMap()

        makeSuccess(
          mapOf(
            "path" to resolvedPath.toString(),
            "content" to numberedContent,
          )
        )
      } else {
        val contentHash = MessageDigest.getInstance("MD5")
          .digest(selectedLines.joinToString("\n").toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

        makeSuccess(
          mapOf(
            "path" to resolvedPath.toString(),
            "lineRange" to "$startLineNumber-$endLineNumber",
            "totalLines" to endLineNumber,
            "contentHashShort" to contentHash.take(5),
            "content" to selectedLines.joinToString("\n"),
          )
        )
      }
    } catch (_: FileNotFoundException) {
      makeFailure(
        ErrorCode.FILE_NOT_FOUND,
        buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "File not found: $filePath",
          fixHint = "Check the file path. Use explore_project to find the correct path."
        ),
        mapOf("path" to resolvedPath.toString())
      )
    } catch (fileReadException: Exception) {
      makeFailure(
        ErrorCode.IO_ERROR,
        buildXmlError(
          code = "IO_ERROR",
          message = fileReadException.message ?: "Unknown I/O error.",
          fixHint = "This is not your fault. Check file permissions and try again."
        ),
        mapOf("path" to resolvedPath.toString())
      )
    }
  }
}

private fun parseRangeBound(rawValue: String): Int? = rawValue.trim().toIntOrNull()
