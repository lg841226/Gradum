/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SaveFileSkill.kt  2026-06-23 08:42:51 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import io.ktor.utils.io.charsets.*
import java.io.File
import java.nio.file.Path

private const val MAXIMUM_CONTENT_SIZE: Int = 512 * 1024

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

private fun isBlockedPaths(path: Path): Boolean {
    val absolutePath = path.toAbsolutePath().normalize()
    val pathString = absolutePath.toString()

    if (pathString in exactBlockedPaths) return true

    for (prefix in blockedPathPrefixes) {
        if (pathString.startsWith(prefix)) return true
    }

    val homeDirectory: String = System.getProperty("user.home") ?: return false
    for (subdir in blockedHomeSubdirectories) {
        val protectedPath = "$homeDirectory/$subdir"
        if (pathString.startsWith(protectedPath)) return true
    }

    return false
}

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
     * this call in READ_ONLY mode. SINGLE_STEP is allowed because the
     * user has explicitly opted into write access.
     */
    override val allowedToolModes: Set<gradum.ToolMode> = setOf(
        gradum.ToolMode.WRITE,
        gradum.ToolMode.SINGLE_STEP,
    )
    override val mutatesProject: Boolean = true

    override val historyKeepCount: Int = 2
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
                                "UTF-8",
                                "UTF-16",
                                "UTF-16LE",
                                "UTF-16BE",
                                "ISO-8859-1",
                                "GBK",
                                "GB2312",
                                "US-ASCII"
                            )
                        )
                    ),
                    "required" to listOf("path", "content")
                )
            )
        )
    }

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

        if (filePath.isBlank())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Missing 'path' parameter")

        val charset: Charset = try {
            Charsets.forName(encodingName)
        } catch (_: Exception) {
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Unsupported encoding: $encodingName")
        }

        if (fileContent.isBlank() && writeMode == "overwrite")
            return makeFailure(
                ErrorCode.EMPTY_RESULT,
                "Content is blank. Use append mode or provide non-empty content."
            )

        val contentBytes: ByteArray = fileContent.toByteArray(charset)
        if (contentBytes.size > MAXIMUM_CONTENT_SIZE)
            return makeFailure(
                ErrorCode.FILE_TOO_LARGE,
                "Content too large: ${contentBytes.size} bytes (max: $MAXIMUM_CONTENT_SIZE bytes).",
            )

        val resolvedPath: Path = if (projectRoot.isNotBlank() && !filePath.startsWith("/")) {
            Path.of(projectRoot, filePath).toAbsolutePath().normalize()
        } else {
            Path.of(filePath).toAbsolutePath().normalize()
        }
        val targetFile: File = resolvedPath.toFile()
        val wasCreated: Boolean = !targetFile.exists()
        val previousSize: Long = if (writeMode == "append" && !wasCreated) targetFile.length() else 0L

        return try {

            if (isBlockedPaths(resolvedPath))
                return makeFailure(
                    ErrorCode.PERMISSION_DENIED,
                    "Writing to '${resolvedPath}' is not allowed for security reasons."
                )

            targetFile.parentFile?.mkdirs()

            if (writeMode == "append")
                targetFile.appendText(fileContent, charset)
            else
                targetFile.writeText(fileContent, charset)

            val bytesWritten: Long = targetFile.length()

            val totalLines: Int = if (writeMode == "append")
                targetFile.readLines(charset).size
            else
                fileContent.lines().size

            makeSuccess(
                buildMap {
                    put("path", resolvedPath.toString())
                    put("bytesWritten", bytesWritten)
                    put("totalLines", totalLines)
                    put("created", wasCreated)
                    put("mode", writeMode)
                    put("encoding", charset.name())
                    if (writeMode == "append" && !wasCreated) {
                        put("previousSize", previousSize)
                    }
                },
            )
        } catch (exception: Exception) {
            makeFailure(
                ErrorCode.IO_ERROR,
                exception.message ?: "Failed to write file",
                mapOf("path" to resolvedPath.toString())
            )
        }
    }
}
