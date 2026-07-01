/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditFileSkill.kt  2026-07-01 12:28:57 Changed by gwy
 */

@file:Suppress("RedundantExplicitType")

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import gradum.utils.SyntaxChecker
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path

/**
 * Computes the length of the longest common subsequence of two line lists.
 * Standard O(nxm) DP. Used by [computeLineDiff] to figure out how many
 * search lines were preserved (and therefore do not count as removed) and
 * how many replace lines were already present (and therefore do not count
 * as added).
 */
private fun longestCommonSubsequenceLength(oldLines: List<String>, newLines: List<String>): Int {
    val oldLineCount = oldLines.size
    val newLineCount = newLines.size

    if (oldLineCount == 0 || newLineCount == 0) return 0

    // dp[firstIndex][secondIndex] = LCS length for prefixes up to these indices
    val longestCommonSubsequence = Array(oldLineCount + 1) { IntArray(newLineCount + 1) }

    for (firstIndex in 1..oldLineCount) {
        for (secondIndex in 1..newLineCount) {
            longestCommonSubsequence[firstIndex][secondIndex] =
                if (oldLines[firstIndex - 1] == newLines[secondIndex - 1]) {
                    longestCommonSubsequence[firstIndex - 1][secondIndex - 1] + 1
                } else {
                    maxOf(
                        longestCommonSubsequence[firstIndex - 1][secondIndex],
                        longestCommonSubsequence[firstIndex][secondIndex - 1]
                    )
                }
        }
    }

    return longestCommonSubsequence[oldLineCount][newLineCount]
}

/**
 * Returns (linesAdded, linesRemoved) for replacing [oldText] with [newText],
 * treating them as ordered lists of lines. Lines shared by both sides (in
 * order) are counted neither as added nor as removed.
 */
private fun computeLineDiff(oldText: String, newText: String): Pair<Int, Int> {
    val oldLines: List<String> = oldText.lines()
    val newLines: List<String> = newText.lines()
    val sharedLineCount: Int = longestCommonSubsequenceLength(oldLines, newLines)
    val linesAdded: Int = newLines.size - sharedLineCount
    val linesRemoved: Int = oldLines.size - sharedLineCount
    return linesAdded to linesRemoved
}

/**
 * Applies a list of search-and-replace edits to a file.
 *
 * Supports two modes:
 * - `sequential`: applies edits in order, each operating on the previous result.
 * - `atomic`: applies all edits to a snapshot, and rolls back if any fails.
 */
class EditFileSkill : Skill() {

    override val skillName: String = "edit_file"
    override val alias: String = "Edited"
    override val description: String = "Search and replace edits with sequential or atomic mode"

    /**
     * edit_file mutates the filesystem. It is rejected in READ_ONLY mode
     * by the agent's mode gate; the schema whitelist hides it from the
     * LLM in that mode but the agent enforces the invariant regardless.
     * SINGLE_STEP is allowed because the user has explicitly opted into
     * write access — the gate only excludes READ_ONLY.
     */
    override val allowedToolModes: Set<gradum.ToolMode> = setOf(
        gradum.ToolMode.WRITE,
        gradum.ToolMode.SINGLE_STEP,
    )

    /**
     * Keep the full diff metrics (linesAdded / linesRemoved / syntaxErrors / totalEdits)
     * for the most recent 2 results. Older results only carry the path and edit count
     * so the LLM can still tell what happened, but the conversation context stays small.
     */
    override val historyKeepCount: Int = 2
    override val historyVolatileKeys: List<String> =
        listOf("syntaxErrors", "linesAdded", "linesRemoved", "totalEdits", "path")

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "File path to edit"),
                        "edits" to mapOf(
                            "type" to "array",
                            "description" to "List of search/replace edits",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "search" to mapOf("type" to "string", "description" to "Text to find"),
                                    "replace" to mapOf("type" to "string", "description" to "Replacement text"),
                                ),
                                "required" to listOf("search", "replace"),
                            ),
                        ),
                        "mode" to mapOf(
                            "type" to "string",
                            "description" to "Edit mode: 'sequential' or 'atomic'",
                            "enum" to listOf("sequential", "atomic"),
                        ),
                    ),
                    "required" to listOf("path", "edits"),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""
        val rawEdits: List<Map<String, Any>> =
            (arguments["edits"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
        val editMode: String = arguments["mode"] as? String ?: "sequential"
        val projectRoot: String = context.projectRoot

        if (filePath.isBlank())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Missing 'path' parameter")

        if (rawEdits.isEmpty())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "No edits provided")

        val resolvedPath: Path = if (projectRoot.isNotBlank() && !filePath.startsWith("/")) {
            Path.of(projectRoot, filePath).toAbsolutePath().normalize()
        } else {
            Path.of(filePath).toAbsolutePath().normalize()
        }
        val targetFile: File = resolvedPath.toFile()

        return try {
            val originalContent: String = targetFile.readText(Charsets.UTF_8)
            val edits: List<EditOperation> = rawEdits.mapIndexed { index: Int, edit: Map<String, Any> ->
                EditOperation(
                    search = edit["search"] as? String ?: "",
                    replace = edit["replace"] as? String ?: "",
                    index = index
                )
            }

            val invalidEdit: EditOperation? = edits.firstOrNull { it.search.isBlank() }
            if (invalidEdit != null) {
                return makeFailure(
                    ErrorCode.INVALID_PARAMETER, "Edit ${invalidEdit.index + 1} has empty 'search' text"
                )
            }

            when (editMode) {
                "atomic" -> applyAtomicEdits(resolvedPath, targetFile, originalContent, edits)
                else -> applySequentialEdits(resolvedPath, targetFile, originalContent, edits)
            }
        } catch (_: FileNotFoundException) {
            makeFailure(
                ErrorCode.FILE_NOT_FOUND,
                "File not found: $filePath",
                mapOf("path" to resolvedPath.toString())
            )
        } catch (exception: Exception) {
            makeFailure(
                ErrorCode.IO_ERROR,
                exception.message ?: "Unknown error during edit",
                mapOf("path" to resolvedPath.toString())
            )
        }
    }

    private fun applySequentialEdits(
        resolvedPath: Path, targetFile: File,
        originalContent: String, edits: List<EditOperation>
    ): SkillResult {
        var currentContent: String = originalContent
        val appliedEdits: MutableList<Int> = mutableListOf()
        var linesAdded = 0
        var linesRemoved = 0

        for (edit in edits) {
            val occurrences: Int = currentContent.countOccurrences(edit.search)

            when {
                occurrences == 0 -> {
                    val failureMessage: String = "Edit ${edit.index + 1} failed: Code not found."
                    targetFile.writeText(currentContent, Charsets.UTF_8)
                    return makeFailure(
                        ErrorCode.CODE_NOT_FOUND,
                        buildPartialFailureMessage(failureMessage, appliedEdits.size),
                        mapOf("path" to resolvedPath.toString(), "appliedCount" to appliedEdits.size),
                    )
                }

                occurrences > 1 -> {
                    val failureMessage: String = "Edit ${edit.index + 1} failed: Found $occurrences matches."
                    targetFile.writeText(currentContent, Charsets.UTF_8)

                    return makeFailure(
                        ErrorCode.MULTIPLE_MATCHES,
                        buildPartialFailureMessage(failureMessage, appliedEdits.size),
                        mapOf("path" to resolvedPath.toString(), "appliedCount" to appliedEdits.size),
                    )
                }

                else -> {
                    linesRemoved += edit.search.lines().size
                    linesAdded += edit.replace.lines().size
                    currentContent = currentContent.replaceFirst(edit.search, edit.replace)
                    appliedEdits.add(edit.index)
                }
            }
        }

        if (currentContent.isBlank() && originalContent.isNotBlank()) {
            targetFile.writeText(originalContent, Charsets.UTF_8)
            return makeFailure(
                ErrorCode.EMPTY_RESULT,
                "Edit resulted in empty content",
                mapOf("path" to resolvedPath.toString())
            )
        }

        targetFile.writeText(currentContent, Charsets.UTF_8)
        return makeSuccess(
            mapOf(
                "path" to resolvedPath.toString(),
                "editsApplied" to appliedEdits.size,
                "totalEdits" to edits.size,
                "linesAdded" to linesAdded,
                "linesRemoved" to linesRemoved,
                "syntaxErrors" to SyntaxChecker.checkSyntax(resolvedPath)
            ),
        )
    }

    private fun applyAtomicEdits(
        resolvedPath: Path, targetFile: File,
        originalContent: String, edits: List<EditOperation>
    ): SkillResult {
        var workingContent: String = originalContent
        var linesAdded = 0
        var linesRemoved = 0

        for (edit in edits) {
            val occurrences: Int = workingContent.countOccurrences(edit.search)

            when {
                occurrences == 0 -> {
                    targetFile.writeText(originalContent, Charsets.UTF_8)
                    return makeFailure(
                        ErrorCode.CODE_NOT_FOUND,
                        "Edit ${edit.index + 1} failed: Code not found. Original content restored.",
                        mapOf("path" to resolvedPath.toString(), "appliedCount" to 0)
                    )
                }

                occurrences > 1 -> {
                    targetFile.writeText(originalContent, Charsets.UTF_8)
                    return makeFailure(
                        ErrorCode.MULTIPLE_MATCHES,
                        "Edit ${edit.index + 1} failed: Found $occurrences matches. Original content restored.",
                        mapOf("path" to resolvedPath.toString(), "appliedCount" to 0)
                    )
                }

                else -> {
                    val (editAdded: Int, editRemoved: Int) = computeLineDiff(edit.search, edit.replace)
                    linesRemoved += editRemoved
                    linesAdded += editAdded
                    workingContent = workingContent.replaceFirst(edit.search, edit.replace)
                }
            }
        }

        if (workingContent.isBlank() && originalContent.isNotBlank()) {
            targetFile.writeText(originalContent, Charsets.UTF_8)
            return makeFailure(
                ErrorCode.EMPTY_RESULT,
                "Edit resulted in empty content. Original content restored.",
                mapOf("path" to resolvedPath.toString())
            )
        }

        targetFile.writeText(workingContent, Charsets.UTF_8)
        return makeSuccess(
            mapOf(
                "path" to resolvedPath.toString(),
                "editsApplied" to edits.size,
                "totalEdits" to edits.size,
                "linesAdded" to linesAdded,
                "linesRemoved" to linesRemoved,
                "syntaxErrors" to SyntaxChecker.checkSyntax(resolvedPath)
            ),
        )
    }

    private fun buildPartialFailureMessage(baseMessage: String, appliedCount: Int): String {
        if (appliedCount == 0) return baseMessage
        return "$baseMessage $appliedCount edit(s) applied before failure."
    }

    private data class EditOperation(val search: String, val replace: String, val index: Int)
}

private fun String.countOccurrences(substring: String): Int =
    if (substring.isEmpty()) 0 else split(substring).size - 1
