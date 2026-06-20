/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SaveFileSkill.kt  2026-06-20 Created by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.io.File
import java.nio.file.Path

/**
 * Writes content to a file, creating parent directories as needed.
 *
 * Used by the agent to persist new files or fully overwrite existing ones.
 * For partial edits, prefer [EditFileSkill].
 */
class SaveFileSkill : Skill() {

    override val skillName: String = "save_file"
    override val alias: String = "Saved"
    override val description: String = "Write content to a file"

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "File path to write"),
                        "content" to mapOf("type" to "string", "description" to "Content to write"),
                    ),
                    "required" to listOf("path", "content"),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""
        val fileContent: String = arguments["content"] as? String ?: ""

        if (filePath.isBlank()) {
            return makeFailure("INVALID_PARAMETER", "Missing 'path' parameter")
        }

        val resolvedPath: Path = Path.of(filePath).toAbsolutePath().normalize()
        val targetFile: File = resolvedPath.toFile()
        val wasCreated: Boolean = !targetFile.exists()

        return try {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(fileContent, Charsets.UTF_8)
            val bytesWritten: Long = targetFile.length()

            makeSuccess(
                mapOf(
                    "path" to resolvedPath.toString(),
                    "bytesWritten" to bytesWritten,
                    "created" to wasCreated,
                ),
            )
        } catch (e: Exception) {
            makeFailure("IO_ERROR", e.message ?: "Failed to write file", mapOf("path" to resolvedPath.toString()))
        }
    }
}
