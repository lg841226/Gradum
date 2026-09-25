/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadFileSkill.kt  2026-09-24 23:50:38 Changed by gwy
 */

package gradum.skill

import gradum.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.security.MessageDigest

private const val MAXIMUM_LINES: Int = GradumConfig.READ_MAX_LINES
private const val MAXIMUM_FILE_SIZE: Int = GradumConfig.READ_MAX_FILE_SIZE

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

  override val simpleDescription: String =
    "Read file content. Returns content as a map of line numbers to line text." +
      " Use this before write_file to see the exact text to replace."

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "path",
      description = "File path (relative to project root, e.g. 'src/main.py', or an " +
        "absolute path). Absolute paths outside the project may prompt the user " +
        "for authorization before reading.",
      required = true,
    )
    cloudOnly {
      string(
        name = "lineRange",
        description = "Line range to read. Format: 'start-end' (e.g., '12-22').",
      )
      string(
        name = "line_range",
        description = "Alias for lineRange. Format: 'start-end' (e.g., '12-22').",
      )
    }
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val filePath: String = arguments["path"] as? String ?: ""
    val lineRange: String = (arguments["lineRange"] as? String ?: "")
      .ifBlank { arguments["line_range"] as? String ?: "" }

    val projectRoot: String = context.projectRoot
    val useSimpleOutput = context.isSimpleModel

    if (filePath.isBlank())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'path' parameter.",
          fixHint = "Provide a current file path in the 'path' parameter."
        )
      )

    val resolved: ResolvedProjectPath = resolveProjectPath(filePath, projectRoot)
    val pathRejection: String? = resolved.rejectionReason
    var targetPath: Path = resolved.resolved

    if (pathRejection != null) {
      val externalPath: Path = resolveProjectPath(filePath, projectRoot, requireWithinProject = false).resolved
      val alreadyAuthorized: Boolean = externalPath.toString() in context.authorizedReadPaths

      if (!alreadyAuthorized) {
        val askScope: AskScope = context.scope ?: return outsideDenied(path = filePath, reason = pathRejection)
        val pathDecision: AskResult = askScope.ask_interaction {
          title = l10n.raw("Allow reading this path outside the project root?", source = Lang.EN)
          details = l10n.raw(externalPath.toString(), source = Lang.EN)
          choices {
            item("once", Choice.Meaning.ALLOW_ONCE)
            item("always", Choice.Meaning.ALLOW_ALWAYS)
            item("no", Choice.Meaning.REJECT)
          }
          default = "no"
        }
        val allowedToRead: Boolean = when (pathDecision) {
          is AskResult.Case ->
            when (pathDecision.id) {
              "once" -> true
              "always" -> {
                context.authorizedReadPaths.add(externalPath.toString())
                true
              }

              else -> false
            }

          else -> false
        }
        if (!allowedToRead) {
          return outsideDenied(path = filePath, reason = pathRejection)
        }
      }

      targetPath = externalPath
    }

    val targetFile: File = targetPath.toFile()

    return try {
      val fileSize: Long = targetFile.length()
      if (fileSize > MAXIMUM_FILE_SIZE) {
        return makeFailure(
          code = ErrorCode.FILE_TOO_LARGE,
          message = buildXmlError(
            code = "FILE_TOO_LARGE",
            message = "File too large: $fileSize bytes (max: $MAXIMUM_FILE_SIZE bytes).",
            fixHint = "Use lineRange to read specific sections of the file."
          ),
          context = mapOf("path" to targetPath.toString(), "fileSize" to fileSize)
        )
      }

      val (startLineNumber, endLineNumber, selectedLines) = if (lineRange.isBlank()) {
        val allLines = targetFile.readLines(Charsets.UTF_8)
        if (allLines.size > MAXIMUM_LINES) {
          return makeFailure(
            code = ErrorCode.FILE_TOO_LARGE,
            message = buildXmlError(
              code = "FILE_TOO_LARGE",
              message = "File has ${allLines.size} lines (max: $MAXIMUM_LINES).",
              fixHint = "Use lineRange to read specific sections of the file."
            ),
            context = mapOf("path" to targetPath.toString(), "totalLines" to allLines.size)
          )
        }
        Triple(1, allLines.size, allLines)
      } else {
        val range: LineRange = parseLineRange(rawValue = lineRange) ?: return makeFailure(
          code = ErrorCode.INVALID_PARAMETER,
          message = buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Invalid lineRange format. Use 'start-end' (e.g., '12-22').",
            fixHint = "Provide lineRange in the format 'start-end' with numeric values."
          ),
          context = mapOf("path" to targetPath.toString(), "lineRange" to lineRange)
        )

        val lines = targetFile.useLines {
          it.drop(n = range.start - 1).take(n = range.end - range.start + 1).toList()
        }
        val actualEndLine = range.start + lines.size - 1

        Triple(range.start, actualEndLine, lines)
      }

      if (useSimpleOutput) {
        val numberedContent = selectedLines.mapIndexed { index, line ->
          (startLineNumber + index).toString() to line
        }.toMap()

        makeSuccess {
          string("path", targetPath.toString())
          set("content", numberedContent)
        }
      } else {
        val selectedContent: String = selectedLines.joinToString(separator = "\n")
        val contentHash = MessageDigest.getInstance("MD5")
          .digest(selectedContent.toByteArray(Charsets.UTF_8))
          .joinToString(separator = "") { "%02x".format(it) }

        makeSuccess {
          string("path", targetPath.toString())
          string("lineRange", "$startLineNumber-$endLineNumber")
          integer("totalLines", endLineNumber)
          string("contentHashShort", contentHash.take(n = 5))
          string("content", selectedContent)
        }
      }
    } catch (_: FileNotFoundException) {
      makeFailure(
        code = ErrorCode.FILE_NOT_FOUND,
        message = buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "File not found: $filePath",
          fixHint = "Check the file path. Use explore_project to find the correct path."
        ),
        context = mapOf("path" to targetPath.toString())
      )
    } catch (fileReadException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = fileReadException.message ?: "Unknown I/O error.",
          fixHint = "This is not your fault. Check file permissions and try again."
        ),
        context = mapOf("path" to targetPath.toString())
      )
    }
  }
}

/**
 * Builds the `PERMISSION_DENIED` failure for an out-of-project read target.
 * Used both when no ask capability is wired and when the user rejects.
 */
private fun outsideDenied(path: String, reason: String): SkillResult = makeFailure(
  code = ErrorCode.PERMISSION_DENIED,
  message = buildXmlError(
    code = "PERMISSION_DENIED",
    message = "Path is outside the project root: $reason",
    fixHint = "Use a path relative to the project root. " +
      "If the file lives outside the project, copy it into the project first."
  ),
  context = mapOf("path" to path)
)

/**
 * A validated, normalized `start..end` line range. Both bounds are
 * clamped to the document (start ≥ 1) and ordered so `start <= end`.
 */
private data class LineRange(val start: Int, val end: Int)

/**
 * Parses a `"start-end"` line-range string. Returns null when the format
 * is malformed or either bound is not a positive integer.
 */
private fun parseLineRange(rawValue: String): LineRange? {
  val rangeParts = rawValue.split("-")
  if (rangeParts.size != 2) return null

  val rawStart = rangeParts[0].trim().toIntOrNull() ?: return null
  val rawEnd = rangeParts[1].trim().toIntOrNull() ?: return null

  val actualStart = rawStart.coerceAtLeast(minimumValue = 1)
  val actualEnd = rawEnd.coerceAtMost(maximumValue = Int.MAX_VALUE)
  return LineRange(
    start = minOf(actualStart, b = actualEnd),
    end = maxOf(actualStart, b = actualEnd)
  )
}
