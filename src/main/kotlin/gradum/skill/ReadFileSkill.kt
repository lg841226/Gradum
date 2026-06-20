package gradum.skill

import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.security.MessageDigest

private const val MAXIMUM_FILE_SIZE: Int = 1 * 1024 * 1024
private const val MAXIMUM_LINES: Int = 10000

private fun readLinesInRange(sourceLines: List<String>, startLine: Int, endLine: Int): String {
    return sourceLines.subList(startLine - 1, endLine).joinToString("\n")
}

/**
 * Reads file content for the agent.
 *
 * Files exceeding [MAXIMUM_FILE_SIZE] or [MAXIMUM_LINES] return
 * `FILE_TOO_LARGE` to keep conversation context bounded.
 */
class ReadFileSkill : Skill() {

    override val skillName: String = "read_file"
    override val alias: String = "Read"
    override val description: String = "Read file content (entire file or specific line range)"

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "File path to read. Use relative path from current directory.",
                        ),
                        "lineRange" to mapOf(
                            "type" to "string",
                            "description" to "Specific line range. Format: 'start-end' (e.g., '12-22').",
                        ),
                        "show" to mapOf(
                            "type" to "string",
                            "description" to "Keyword to highlight in yellow background.",
                        ),
                    ),
                    "required" to listOf("path"),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""
        val lineRange: String = arguments["lineRange"] as? String ?: ""
        val show: String = arguments["show"] as? String ?: ""

        if (filePath.isBlank()) {
            return makeFailure("INVALID_PARAMETER", "Missing 'path' parameter")
        }

        val resolvedPath: Path = Path.of(filePath).toAbsolutePath().normalize()
        val targetFile: File = resolvedPath.toFile()

        return try {
            val fileSize: Long = targetFile.length()
            if (fileSize > MAXIMUM_FILE_SIZE) {
                return makeFailure(
                    "FILE_TOO_LARGE",
                    "File too large: $fileSize bytes (max: $MAXIMUM_FILE_SIZE bytes). Use lineRange to read specific sections.",
                    mapOf("path" to resolvedPath.toString(), "fileSize" to fileSize),
                )
            }

            val allLines: List<String> = targetFile.readLines(Charsets.UTF_8)
            val totalLines: Int = allLines.size

            if (totalLines > MAXIMUM_LINES && lineRange.isBlank()) {
                return makeFailure(
                    "FILE_TOO_LARGE",
                    "File has $totalLines lines (max: $MAXIMUM_LINES). Use lineRange to read specific sections.",
                    mapOf("path" to resolvedPath.toString(), "totalLines" to totalLines),
                )
            }

            val (startLineNumber: Int, endLineNumber: Int, fileContent: String) = if (lineRange.isBlank()) {
                Triple(1, totalLines, allLines.joinToString("\n"))
            } else {
                val rangeParts: List<String> = lineRange.split("-")
                if (rangeParts.size != 2) {
                    return makeFailure(
                        "INVALID_PARAMETER",
                        "Invalid lineRange format. Use 'start-end' (e.g., '12-22')",
                        mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange),
                    )
                }

                val rawStart: Int = rangeParts[0].trim().toIntOrNull()
                    ?: return makeFailure(
                        "INVALID_PARAMETER",
                        "Invalid lineRange start value: ${rangeParts[0]}",
                        mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange),
                    )

                val rawEnd: Int = rangeParts[1].trim().toIntOrNull()
                    ?: return makeFailure(
                        "INVALID_PARAMETER",
                        "Invalid lineRange end value: ${rangeParts[1]}",
                        mapOf("path" to resolvedPath.toString(), "lineRange" to lineRange),
                    )

                val clampedStart: Int = rawStart.coerceAtLeast(1)
                val clampedEnd: Int = rawEnd.coerceAtMost(totalLines)
                val actualStart: Int = minOf(clampedStart, clampedEnd)
                val actualEnd: Int = maxOf(clampedStart, clampedEnd)

                val selectedContent: String = readLinesInRange(allLines, actualStart, actualEnd)
                Triple(actualStart, actualEnd, selectedContent)
            }

            val contentHash: String = MessageDigest
                .getInstance("MD5")
                .digest(fileContent.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte: Byte -> "%02x".format(byte) }

            makeSuccess(
                mapOf(
                    "path" to resolvedPath.toString(),
                    "lineRange" to "$startLineNumber-$endLineNumber",
                    "totalLines" to totalLines,
                    "contentHash" to contentHash,
                    "content" to fileContent,
                ),
            )
        } catch (e: FileNotFoundException) {
            makeFailure("FILE_NOT_FOUND", "File not found: $filePath", mapOf("path" to resolvedPath.toString()))
        } catch (e: Exception) {
            makeFailure("IO_ERROR", e.message ?: "Unknown I/O error", mapOf("path" to resolvedPath.toString()))
        }
    }
}
