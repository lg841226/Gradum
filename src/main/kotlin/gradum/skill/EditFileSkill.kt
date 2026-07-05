/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditFileSkill.kt  2026-07-05 10:03:38 Changed by gwy
 */

@file:Suppress("RedundantExplicitType")

package gradum.skill

import gradum.*
import gradum.utils.SyntaxChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path

/**
 * Matching strategy used by the matching process.
 */
private enum class MatchStrategy {
    EXACT, NORMALIZED, NONE
}

/**
 * Result of a single match.
 */
private data class MatchResult(
    val startIndex: Int,
    val endIndex: Int,
    val strategy: MatchStrategy
)

/**
 * Result of the matching process.
 */
private sealed class FindResult {
    data class Found(val matches: List<MatchResult>) : FindResult()
    data class NotFound(
        val failedAtStep: MatchStrategy,
        val searchPreview: List<String>
    ) : FindResult()
}

/**
 * Matching for search blocks.
 *
 * Step 1: EXACT - trimEnd() comparison (handles \r\n vs \n)
 * Step 2: NORMALIZED - trim() comparison (ignore all whitespace)
 * Step 3: NONE - return detailed error
 */
private fun findMatchesWithFallback(
    fileLines: List<String>,
    searchLines: List<String>
): FindResult {
    if (searchLines.isEmpty()) return FindResult.NotFound(
        MatchStrategy.NONE, emptyList()
    )

    val searchNonBlank = searchLines.filter { it.isNotBlank() }
    if (searchNonBlank.isEmpty()) return FindResult.NotFound(
        MatchStrategy.NONE, searchLines.take(3)
    )

    val windowSize = searchLines.size
    if (windowSize > fileLines.size) return FindResult.NotFound(
        MatchStrategy.NONE, searchNonBlank.take(3)
    )

    val exactMatches = findMatchesByStrategy(fileLines, searchNonBlank, windowSize) { fileLine, searchLine ->
        fileLine.trimEnd('\r', ' ') == searchLine.trimEnd('\r', ' ')
    }
    if (exactMatches.isNotEmpty()) return FindResult.Found(exactMatches)

    val normalizedMatches = findMatchesByStrategy(fileLines, searchNonBlank, windowSize) { fileLine, searchLine ->
        fileLine.trim() == searchLine.trim()
    }
    if (normalizedMatches.isNotEmpty()) return FindResult.Found(normalizedMatches)

    return FindResult.NotFound(
        failedAtStep = MatchStrategy.NORMALIZED,
        searchPreview = searchNonBlank.take(3)
    )
}

/**
 * Find matches using a custom comparison function.
 */
private fun findMatchesByStrategy(
    fileLines: List<String>, searchLines: List<String>,
    windowSize: Int, comparator: (String, String) -> Boolean
): List<MatchResult> {
    val matches = mutableListOf<MatchResult>()
    val maxStartIndex = fileLines.size - windowSize

    for (startIndex in 0..maxStartIndex) {
        val window = fileLines.subList(startIndex, startIndex + windowSize)
        val windowContent = window.filter { it.isNotBlank() }
        val searchContent = searchLines.filter { it.isNotBlank() }

        if (windowContent.size != searchContent.size) continue

        val isMatch = windowContent.zip(searchContent).all { (fileLine, searchLine) ->
            comparator(fileLine, searchLine)
        }

        if (isMatch) {
            matches.add(MatchResult(startIndex, startIndex + windowSize, MatchStrategy.EXACT))
        }
    }
    return matches
}

/**
 * Search-and-replace file editing. Local models edit one file at a time with
 * a single oldString/newString pair. Cloud models can batch multiple edits.
 */
class EditFileSkill : Skill() {

    override val skillName: String = "edit_file"
    override val alias: String = "Edited"
    override val description: String = "Replace text in a file. Provide the exact text to find (oldString) and the replacement (newString)."

    override val allowedToolModes: Set<ToolMode> = setOf(
        ToolMode.AGENT,
        ToolMode.EDIT,
    )

    override val historyKeepCount: Int = 2
    override val historyVolatileKeys: List<String> =
        listOf("syntaxErrors", "linesAdded", "linesRemoved", "totalEdits", "path")

    override fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
        val withoutDiffPayload: Map<String, Any> = result
            .filterKeys { it != "originalContent" && it != "modifiedContent" }
        return super.prepareHistoryResult(withoutDiffPayload)
    }

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
                    "required" to listOf("path") + if (useSimpleSchema) listOf("oldString", "newString") else listOf("edits"),
                ),
            ),
        )
    }

    private fun localDescription(): String =
        "Replace one block of text in a file. " +
        "Step 1: Call read_file to see the file content and line numbers. " +
        "Step 2: Find the exact text you want to replace in the content. " +
        "Step 3: Call edit_file with the file path, the original text as oldString, and your new text as newString. " +
        "oldString must match the file content exactly, including all whitespace, indentation, and line breaks. " +
        "Include 2-3 lines of surrounding context in oldString to help find a unique match. " +
        "You can only edit one location per call. To edit multiple places in the same file, make multiple calls. " +
        "After editing, call read_file again to verify the change was applied correctly."

    private fun localProperties(): Map<String, Any> = mapOf(
        "path" to mapOf(
            "type" to "string",
            "description" to "The file to edit. Must be a path relative to the project root. " +
                "Do not use absolute paths. Examples: 'src/main.py', 'lib/utils.ts', 'README.md'. " +
                "Make sure the file exists before calling edit_file. Use read_file first to confirm."
        ),
        "oldString" to mapOf(
            "type" to "string",
            "description" to "The exact text to find in the file. Must match the file content character-by-character, " +
                "including all whitespace, indentation, tabs, and line breaks. " +
                "Include 2-3 lines of surrounding context to help find a unique match. " +
                "Trailing whitespace on each line is ignored. Line endings (\\r\\n vs \\n) are handled automatically. " +
                "If the text appears multiple times, the tool will return an error asking you to provide more context. " +
                "Example: if you want to change a function, include the function definition line and a few lines inside. " +
                "For example: 'def greet(name):\\n    return f\"Hello, {name}\"' would match that specific function."
        ),
        "newString" to mapOf(
            "type" to "string",
            "description" to "The replacement text that replaces oldString in the file. " +
                "Can be longer, shorter, or the same length as oldString. " +
                "To delete text, pass an empty string as newString. " +
                "To add new text, include it here with proper indentation. " +
                "The replacement preserves the indentation of the first line of oldString. " +
                "Example: 'def greet(name, title=\"Mr\"):\\n    return f\"Hello, {title} {name}\"' " +
                "replaces the original function with a new version that has an extra parameter."
        ),
    )

    private fun cloudProperties(): Map<String, Any> = mapOf(
        "path" to mapOf(
            "type" to "string",
            "description" to "The file to edit. Use a path relative to the project root."
        ),
        "edits" to mapOf(
            "type" to "array",
            "description" to "List of edits to apply. Each edit has oldString (text to find) and newString (replacement). " +
                "Edits are applied in order. You can batch multiple edits to the same file in one call.",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "oldString" to mapOf("type" to "string", "description" to "Exact text to find. Include 2-3 lines of context for uniqueness."),
                    "newString" to mapOf("type" to "string", "description" to "Replacement text. Can be empty to delete lines."),
                ),
                "required" to listOf("oldString", "newString"),
            ),
        ),
    )

    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val useSimpleSchema = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
        return if (useSimpleSchema) executeLocal(arguments, context) else executeCloud(arguments, context)
    }

    /**
     * Local model execution: single search/replace edit.
     * Simplified schema with flat parameters for small models.
     */
    private fun executeLocal(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""
        val oldString: String = arguments["oldString"] as? String ?: ""
        val newString: String = arguments["newString"] as? String ?: ""
        val projectRoot: String = context.projectRoot

        if (filePath.isBlank())
            return makeFailure(
                ErrorCode.INVALID_PARAMETER, buildXmlError(
                    code = "INVALID_PARAMETER",
                    message = "Missing 'path' parameter.",
                    fixHint = "Provide a valid file path in the 'path' parameter."
                )
            )

        if (oldString.isBlank())
            return makeFailure(
                ErrorCode.INVALID_PARAMETER, buildXmlError(
                    code = "INVALID_PARAMETER",
                    message = "Missing 'oldString' parameter.",
                    fixHint = "Provide the text to find in the 'oldString' parameter."
                )
            )

        val resolvedPath: Path = if (projectRoot.isNotBlank() && !filePath.startsWith("/")) {
            Path.of(projectRoot, filePath).toAbsolutePath().normalize()
        } else {
            Path.of(filePath).toAbsolutePath().normalize()
        }
        val targetFile: File = resolvedPath.toFile()

        return try {
            val originalContent: String = targetFile.readText(Charsets.UTF_8)
            val singleEdit = listOf(EditOperation(oldString, newString, 0))
            applySequentialEdits(resolvedPath, targetFile, originalContent, singleEdit)
        } catch (_: FileNotFoundException) {
            makeFailure(
                ErrorCode.FILE_NOT_FOUND, buildXmlError(
                    code = "FILE_NOT_FOUND",
                    message = "File not found: $filePath",
                    fixHint = "Check the file path. Use explore_project to find the correct path."
                ), mapOf("path" to resolvedPath.toString())
            )
        } catch (exception: Exception) {
            makeFailure(
                ErrorCode.IO_ERROR, buildXmlError(
                    code = "IO_ERROR",
                    message = exception.message ?: "Unknown error during edit.",
                    fixHint = "This is not your fault. Check file permissions and disk space."
                ), mapOf("path" to resolvedPath.toString())
            )
        }
    }

    /**
     * Cloud model execution: multiple search/replace edits via array.
     */
    private fun executeCloud(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""
        val rawEdits: List<Map<String, Any>> = parseEdits(arguments["edits"])
        val projectRoot: String = context.projectRoot

        if (filePath.isBlank())
            return makeFailure(
                ErrorCode.INVALID_PARAMETER, buildXmlError(
                    code = "INVALID_PARAMETER",
                    message = "Missing 'path' parameter.",
                    fixHint = "Provide a valid file path in the 'path' parameter."
                )
            )

        if (rawEdits.isEmpty())
            return makeFailure(
                ErrorCode.INVALID_PARAMETER, buildXmlError(
                    code = "INVALID_PARAMETER",
                    message = "No edits provided.",
                    fixHint = "Provide at least one edit object with 'oldString' and 'newString' fields."
                )
            )

        val resolvedPath: Path = if (projectRoot.isNotBlank() && !filePath.startsWith("/")) {
            Path.of(projectRoot, filePath).toAbsolutePath().normalize()
        } else {
            Path.of(filePath).toAbsolutePath().normalize()
        }
        val targetFile: File = resolvedPath.toFile()

        return try {
            val originalContent: String = targetFile.readText(Charsets.UTF_8)
            val parsedEdits: List<EditOperation> = rawEdits.mapIndexed { editIndex: Int, editEntry: Map<String, Any> ->
                EditOperation(
                    searchText = editEntry["oldString"] as? String ?: "",
                    replaceText = editEntry["newString"] as? String ?: "",
                    editIndex = editIndex
                )
            }

            val invalidEdit: EditOperation? = parsedEdits.firstOrNull { it.searchText.isBlank() }
            if (invalidEdit != null) {
                return makeFailure(
                    ErrorCode.INVALID_PARAMETER, buildXmlError(
                        code = "INVALID_PARAMETER",
                        message = "Edit ${invalidEdit.editIndex + 1} has empty 'search' text.",
                        fixHint = "Provide non-empty oldString text that matches the target code block in the file."
                    )
                )
            }

            applySequentialEdits(resolvedPath, targetFile, originalContent, parsedEdits)
        } catch (_: FileNotFoundException) {
            makeFailure(
                ErrorCode.FILE_NOT_FOUND, buildXmlError(
                    code = "FILE_NOT_FOUND",
                    message = "File not found: $filePath",
                    fixHint = "Check the file path. Use explore_project to find the correct path."
                ), mapOf("path" to resolvedPath.toString())
            )
        } catch (exception: Exception) {
            makeFailure(
                ErrorCode.IO_ERROR, buildXmlError(
                    code = "IO_ERROR",
                    message = exception.message ?: "Unknown error during edit.",
                    fixHint = "This is not your fault. Check file permissions and disk space."
                ), mapOf("path" to resolvedPath.toString())
            )
        }
    }

    private fun applySequentialEdits(
        resolvedPath: Path, targetFile: File,
        originalContent: String, editOperations: List<EditOperation>
    ): SkillResult {
        var currentContent: String = originalContent
        val appliedEdits: MutableList<Int> = mutableListOf()
        var linesAdded = 0
        var linesRemoved = 0

        for (editOperation in editOperations) {
            val fileLines = currentContent.lines()
            val searchLines = editOperation.searchText.lines()

            when (val matchResult = findMatchesWithFallback(fileLines, searchLines)) {
                is FindResult.Found -> {
                    if (matchResult.matches.size > 1) {
                        targetFile.writeText(currentContent, Charsets.UTF_8)
                        return makeFailure(
                            ErrorCode.MULTIPLE_MATCHES, buildXmlError(
                                code = "MULTIPLE_MATCHES",
                                message = "Edit ${editOperation.editIndex + 1} found ${matchResult.matches.size} matches. Cannot determine which to replace.",
                                fixHint = "Add more surrounding context (function name, class declaration, comments) to make the match unique.",
                                appliedCount = appliedEdits.size
                            ), mapOf("path" to resolvedPath.toString(), "appliedCount" to appliedEdits.size)
                        )
                    }

                    val bestMatch = matchResult.matches.first()
                    val replaceLines = if (editOperation.replaceText.isBlank()) emptyList() else editOperation.replaceText.lines()
                    linesRemoved += searchLines.size
                    linesAdded += replaceLines.size
                    val newFileLines = fileLines.subList(0, bestMatch.startIndex) +
                        replaceLines +
                        fileLines.subList(bestMatch.endIndex, fileLines.size)
                    currentContent = newFileLines.joinToString("\n")
                    appliedEdits.add(editOperation.editIndex)
                }

                is FindResult.NotFound -> {
                    targetFile.writeText(currentContent, Charsets.UTF_8)
                    val failureStep = when (matchResult.failedAtStep) {
                        MatchStrategy.EXACT -> "exact match"
                        MatchStrategy.NORMALIZED -> "normalized match"
                        MatchStrategy.NONE -> "matching"
                    }
                    return makeFailure(
                        ErrorCode.CODE_NOT_FOUND, buildXmlError(
                            code = "CODE_NOT_FOUND",
                            message = "Edit ${editOperation.editIndex + 1} failed at step: $failureStep. oldString text not found in file.",
                            fixHint = "Check for whitespace differences, line ending differences (\\r\\n vs \\n), " +
                                "or add more surrounding context (function name, class declaration, comments) to make the match unique.",
                            searchPreview = matchResult.searchPreview,
                            appliedCount = appliedEdits.size
                        ),
                        mapOf(
                            "path" to resolvedPath.toString(),
                            "appliedCount" to appliedEdits.size,
                            "failedAtStep" to matchResult.failedAtStep.name,
                            "searchPreview" to matchResult.searchPreview
                        )
                    )
                }
            }
        }

        if (currentContent.isBlank() && originalContent.isNotBlank()) {
            targetFile.writeText(originalContent, Charsets.UTF_8)
            return makeFailure(
                ErrorCode.EMPTY_RESULT, buildXmlError(
                    code = "EMPTY_RESULT",
                    message = "Edit resulted in empty content.",
                    fixHint = "The oldString block matched the entire file content. Add more context or check the file."
                ), mapOf("path" to resolvedPath.toString())
            )
        }

        targetFile.writeText(currentContent, Charsets.UTF_8)
        return makeSuccess(
            mapOf(
                "path" to resolvedPath.toString(),
                "editsApplied" to appliedEdits.size,
                "totalEdits" to editOperations.size,
                "linesAdded" to linesAdded,
                "linesRemoved" to linesRemoved,
                "syntaxErrors" to SyntaxChecker.checkSyntax(resolvedPath),
                "originalContent" to originalContent,
                "modifiedContent" to currentContent,
            ),
        )
    }

    private data class EditOperation(val searchText: String, val replaceText: String, val editIndex: Int)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Suppress("UNCHECKED_CAST")
        fun parseEdits(rawInput: Any?): List<Map<String, Any>> {
            if (rawInput is List<*>) return rawInput.filterIsInstance<Map<String, Any>>()

            if (rawInput is String && rawInput.isNotBlank()) {
                try {
                    val jsonElement = json.parseToJsonElement(rawInput)
                    if (jsonElement is JsonArray) {
                        return jsonElement.mapNotNull { jsonItem ->
                            if (jsonItem is JsonObject) {
                                val oldStringContent = (jsonItem["oldString"] as? JsonPrimitive)?.content ?: ""
                                val newStringContent = (jsonItem["newString"] as? JsonPrimitive)?.content ?: ""
                                mapOf("oldString" to oldStringContent, "newString" to newStringContent)
                            } else null
                        }
                    }
                } catch (_: Exception) {
                    // Fall through to empty
                }
            }
            return emptyList()
        }
    }
}
