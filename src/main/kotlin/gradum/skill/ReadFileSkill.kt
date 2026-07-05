/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadFileSkill.kt  2026-07-04 21:38:29 Changed by gwy
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
 */
class ReadFileSkill : Skill() {

    override val alias: String = "Read"
    override val skillName: String = "read_file"
    override val description: String = "Read file content. Use line_range to read a section."

    override val historyKeepCount: Int = 5
    override val historyVolatileKeys: List<String> = listOf("content")

    override fun getSchema(context: SkillContext?): Map<String, Any> {
        val useSimpleSchema = context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
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
        "Read the contents of a file. Pass the file path and get back the full content with line numbers. " +
        "Use this before edit_file to see the exact text you need to replace. " +
        "The content is returned as a map where each key is a line number and each value is the line content. " +
        "Use these line numbers when calling edit_file to specify what to change. " +
        "After editing a file, call read_file again to see the updated content and line numbers."

    private fun localProperties(): Map<String, Any> = mapOf(
        "path" to mapOf(
            "type" to "string",
            "description" to "The file path to read. Must be a path relative to the project root. " +
                "Do not use absolute paths. Examples: 'src/main.py', 'lib/utils.ts', 'README.md'. " +
                "If you are unsure of the file structure, call explore_project first to see the project layout."
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

        if (filePath.isBlank())
            return makeFailure(ErrorCode.INVALID_PARAMETER, buildXmlError(
                code = "INVALID_PARAMETER",
                message = "Missing 'path' parameter.",
                fixHint = "Provide a file path in the 'path' parameter."
            ))

        val resolvedPath: Path = if (projectRoot.isNotBlank() && !filePath.startsWith("/")) {
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

            val allLines: List<String> = targetFile.readLines(Charsets.UTF_8)
            val totalLines: Int = allLines.size

            if (totalLines > MAXIMUM_LINES && lineRange.isBlank()) {
                return makeFailure(
                    ErrorCode.FILE_TOO_LARGE,
                    buildXmlError(
                        code = "FILE_TOO_LARGE",
                        message = "File has $totalLines lines (max: $MAXIMUM_LINES).",
                        fixHint = "Use lineRange to read specific sections of the file."
                    ),
                    mapOf("path" to resolvedPath.toString(), "totalLines" to totalLines)
                )
            }

            val (startLineNumber: Int, endLineNumber: Int, selectedLines: List<String>) = if (lineRange.isBlank()) {
                Triple(1, totalLines, allLines)
            } else {
                val rangeParts: List<String> = lineRange.split("-")
                if (rangeParts.size != 2) {
                    return makeFailure(
                        ErrorCode.INVALID_PARAMETER, buildXmlError(
                            code = "INVALID_PARAMETER",
                            message = "Invalid lineRange format. Use 'start-end' (e.g., '12-22').",
                            fixHint = "Provide lineRange in the format 'start-end' with numeric values."
                        ),
                        mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange),
                    )
                }

                val rawStart: Int = parseRangeBound(rangeParts[0])
                    ?: return makeFailure(
                        ErrorCode.INVALID_PARAMETER, buildXmlError(
                            code = "INVALID_PARAMETER",
                            message = "Invalid lineRange start value: ${rangeParts[0]}",
                            fixHint = "Provide a valid integer for the start line number."
                        ),
                        mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange)
                    )

                val rawEnd: Int = parseRangeBound(rangeParts[1])
                    ?: return makeFailure(
                        ErrorCode.INVALID_PARAMETER, buildXmlError(
                            code = "INVALID_PARAMETER",
                            message = "Invalid lineRange end value: ${rangeParts[1]}",
                            fixHint = "Provide a valid integer for the end line number."
                        ),
                        mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange)
                    )

                val clampedStart: Int = rawStart.coerceAtLeast(1)
                val clampedEnd: Int = rawEnd.coerceAtMost(totalLines)
                val actualStart: Int = minOf(clampedStart, clampedEnd)
                val actualEnd: Int = maxOf(clampedStart, clampedEnd)

                Triple(actualStart, actualEnd, allLines.subList(actualStart - 1, actualEnd))
            }

            if (useSimpleOutput) {
                val numberedContent: Map<String, String> = selectedLines.mapIndexed { index: Int, line: String ->
                    (startLineNumber + index).toString() to line
                }.toMap()

                makeSuccess(
                    mapOf(
                        "path" to resolvedPath.toString(),
                        "content" to numberedContent,
                    ),
                )
            } else {
                val contentHash: String = MessageDigest.getInstance("MD5")
                    .digest(selectedLines.joinToString("\n").toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte: Byte -> "%02x".format(byte) }

                makeSuccess(
                    mapOf(
                        "path" to resolvedPath.toString(),
                        "lineRange" to "$startLineNumber-$endLineNumber",
                        "totalLines" to totalLines,
                        "contentHash" to contentHash,
                        "content" to selectedLines.joinToString("\n"),
                    ),
                )
            }
        } catch (_: FileNotFoundException) {
            makeFailure(ErrorCode.FILE_NOT_FOUND, buildXmlError(
                code = "FILE_NOT_FOUND",
                message = "File not found: $filePath",
                fixHint = "Check the file path. Use explore_project to find the correct path."
            ), mapOf("path" to resolvedPath.toString()))
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
