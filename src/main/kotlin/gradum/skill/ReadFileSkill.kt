/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadFileSkill.kt  2026-08-11 23:36:09 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.security.MessageDigest

private const val MAXIMUM_LINES: Int = 10000
private const val MAXIMUM_FILE_SIZE: Int = 1 * 1024 * 1024

/**
 * Reads file content for the agent.
 *
 * Files exceeding [MAXIMUM_FILE_SIZE] or [MAXIMUM_LINES] return
 * `FILE_TOO_LARGE` to keep conversation context bounded.
 *
 * **History retention: `Int.MAX_VALUE` + `emptyList()` (never
 * strip).** Even though [gradum.skill.Skill.compactHistory] only
 * strips OLDER history (the current call's result is always
 * returned to the LLM in full), `read_file` is the one skill
 * whose `content` field the LLM routinely needs to refer back
 * to in subsequent turns — when planning an edit, when
 * verifying a previous edit, when answering questions about
 * the file. Stripping even a deeply-old `content` from a long
 * session can force the model to re-read the file from disk
 * and burn the same context the strip was trying to save.
 * Locked in by `gradum.skill.ReadFileSkillPrepareHistoryTest`.
 * Context-bloat control lives in [MAXIMUM_FILE_SIZE] /
 * [MAXIMUM_LINES] and in `lineRange`, not in history
 * stripping.
 */
class ReadFileSkill : Skill() {

  override val alias: String = "Read"
  override val skillName: String = "read_file"
  override val description: String = "Read file content. Use line_range to read a section."

  override val historyKeepCount: Int = Int.MAX_VALUE
  override val historyVolatileKeys: List<String> = emptyList()

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimpleSchema = context?.isSimpleModel == true

    return buildFunctionSchema(
      description = if (useSimpleSchema) localDescription() else description,
      properties = if (useSimpleSchema) localProperties() else cloudProperties(),
      required = listOf("path"),
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
    val useSimpleOutput = context.isSimpleModel

    if (filePath.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a current file path in the 'path' parameter."
        )
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val resolvedPath: Path = resolved.resolved
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
        val range: LineRange = parseLineRange(lineRange) ?: return makeFailure(
          ErrorCode.INVALID_PARAMETER,
          buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Invalid lineRange format. Use 'start-end' (e.g., '12-22').",
            fixHint = "Provide lineRange in the format 'start-end' with numeric values."
          ),
          mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange)
        )

        val lines = targetFile.useLines { it.drop(range.start - 1).take(range.end - range.start + 1).toList() }
        val actualEndLine = range.start + lines.size - 1

        Triple(range.start, actualEndLine, lines)
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
        val selectedContent: String = selectedLines.joinToString("\n")
        val contentHash = MessageDigest.getInstance("MD5")
          .digest(selectedContent.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

        makeSuccess(
          mapOf(
            "path" to resolvedPath.toString(),
            "lineRange" to "$startLineNumber-$endLineNumber",
            "totalLines" to endLineNumber,
            "contentHashShort" to contentHash.take(5),
            "content" to selectedContent,
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

/**
 * A validated, normalized `start..end` line range. Both bounds are
 * clamped to the document (start ≥ 1) and ordered so `start ≤ end`.
 */
private data class LineRange(
  val start: Int, val end: Int,
)

/**
 * Parses a `"start-end"` line-range string. Returns null when the format
 * is malformed or either bound is not a positive integer.
 */
private fun parseLineRange(rawValue: String): LineRange? {
  val rangeParts = rawValue.split("-")
  if (rangeParts.size != 2) return null

  val rawStart = rangeParts[0].trim().toIntOrNull() ?: return null
  val rawEnd = rangeParts[1].trim().toIntOrNull() ?: return null

  val actualStart = rawStart.coerceAtLeast(1)
  val actualEnd = rawEnd.coerceAtMost(Int.MAX_VALUE)
  return LineRange(
    start = minOf(actualStart, actualEnd),
    end = maxOf(actualStart, actualEnd),
  )
}
