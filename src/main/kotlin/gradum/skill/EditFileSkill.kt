/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditFileSkill.kt  2026-07-04 19:55:23 Changed by gwy
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

private const val FUZZY_THRESHOLD = 0.85

/**
 * Levenshtein distance between two strings.
 * Returns the minimum number of single-character edits (insertions, deletions, substitutions)
 * required to change [oldString] into [newString].
 */
private fun levenshteinDistance(oldString: String, newString: String): Int {
    val oldLength = oldString.length
    val newLength = newString.length

    val distanceTable = Array(oldLength + 1) { IntArray(newLength + 1) }

    for (oldIndex in 0..oldLength) {
        distanceTable[oldIndex][0] = oldIndex
    }
    for (newIndex in 0..newLength) {
        distanceTable[0][newIndex] = newIndex
    }

    for (oldIndex in 1..oldLength) {
        for (newIndex in 1..newLength) {
            val charOld = oldString[oldIndex - 1]
            val charNew = newString[newIndex - 1]

            val costOfDeletion = distanceTable[oldIndex - 1][newIndex] + 1
            val costOfInsertion = distanceTable[oldIndex][newIndex - 1] + 1
            val costOfSubstitution = distanceTable[oldIndex - 1][newIndex - 1] + if (charOld == charNew) 0 else 1

            distanceTable[oldIndex][newIndex] = minOf(
                costOfDeletion,
                costOfInsertion,
                costOfSubstitution
            )
        }
    }

    return distanceTable[oldLength][newLength]
}

/**
 * Similarity ratio between two strings. Returns 1.0 for identical strings, 0.0 for completely different.
 */
private fun similarity(oldString: String, newString: String): Double {
    if (oldString == newString) return 1.0
    if (oldString.isEmpty() || newString.isEmpty()) return 0.0
    val maxLen = maxOf(oldString.length, newString.length)
    val distance = levenshteinDistance(oldString, newString)
    return 1.0 - distance.toDouble() / maxLen
}

/**
 * Matching strategy used by the 5-step fallback.
 */
private enum class MatchStrategy {
    EXACT, NORMALIZED, FUZZY, NONE
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
        val searchPreview: List<String>,
        val fileContext: List<String>
    ) : FindResult()
}

/**
 * 5-step fallback matching for search blocks.
 *
 * Step 1: EXACT - trimEnd() comparison
 * Step 2: NORMALIZED - trim() comparison (ignore all whitespace)
 * Step 3: FUZZY - Levenshtein similarity > 85%
 * Step 4: (reserved for future anchor-based matching)
 * Step 5: NONE - return detailed error
 */
private fun findMatchesWithFallback(
    fileLines: List<String>,
    searchLines: List<String>
): FindResult {
    if (searchLines.isEmpty()) return FindResult.NotFound(
        MatchStrategy.NONE, emptyList(), emptyList()
    )

    val searchNonBlank = searchLines.filter { it.isNotBlank() }
    if (searchNonBlank.isEmpty()) return FindResult.NotFound(
        MatchStrategy.NONE, searchLines.take(3), emptyList()
    )

    val windowSize = searchLines.size
    if (windowSize > fileLines.size) return FindResult.NotFound(
        MatchStrategy.NONE, searchNonBlank.take(3), fileLines.take(5)
    )

    // EXACT match (trimEnd)
    val exactMatches = findMatchesByStrategy(fileLines, searchNonBlank, windowSize) { fileLine, searchLine ->
        fileLine.trimEnd() == searchLine.trimEnd()
    }
    if (exactMatches.isNotEmpty()) return FindResult.Found(exactMatches)

    // NORMALIZED match (trim all whitespace)
    val normalizedMatches = findMatchesByStrategy(fileLines, searchNonBlank, windowSize) { fileLine, searchLine ->
        fileLine.trim() == searchLine.trim()
    }
    if (normalizedMatches.isNotEmpty()) return FindResult.Found(normalizedMatches)

    // FUZZY match (Levenshtein > 85%)
    val fuzzyMatches = findFuzzyMatches(fileLines, searchNonBlank, windowSize)
    if (fuzzyMatches.isNotEmpty()) return FindResult.Found(fuzzyMatches)

    // NONE - return detailed error
    val nearestLine = findNearestLine(fileLines, searchNonBlank)
    return FindResult.NotFound(
        failedAtStep = MatchStrategy.FUZZY,
        searchPreview = searchNonBlank.take(3),
        fileContext = nearestLine
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

    for (i in 0..fileLines.size - windowSize) {
        val window = fileLines.subList(i, i + windowSize)
        val windowNonBlank = window.filter { it.isNotBlank() }

        if (windowNonBlank.size != searchLines.size) continue

        val allMatch = windowNonBlank.zip(searchLines).all { (fileLine, searchLine) ->
            comparator(fileLine, searchLine)
        }

        if (allMatch)
            matches.add(MatchResult(i, i + windowSize, MatchStrategy.EXACT))
    }

    return matches
}

/**
 * Find fuzzy matches where average similarity > threshold.
 */
private fun findFuzzyMatches(fileLines: List<String>, searchLines: List<String>, windowSize: Int): List<MatchResult> {
    val matches = mutableListOf<MatchResult>()

    for (i in 0..fileLines.size - windowSize) {
        val window = fileLines.subList(i, i + windowSize)
        val windowNonBlank = window.filter { it.isNotBlank() }

        if (windowNonBlank.size != searchLines.size) continue

        val avgSimilarity = windowNonBlank.zip(searchLines).map { (fileLine, searchLine) ->
            similarity(fileLine.trim(), searchLine.trim())
        }.average()

        if (avgSimilarity >= FUZZY_THRESHOLD) {
            matches.add(MatchResult(i, i + windowSize, MatchStrategy.FUZZY))
        }
    }

    return matches
}

/**
 * Find the nearest file line to any search line (for error context).
 */
private fun findNearestLine(fileLines: List<String>, searchLines: List<String>): List<String> {
    var bestScore = -1.0
    var bestIndex = 0

    for (i in fileLines.indices) {
        val fileLine = fileLines[i].trim()
        if (fileLine.isEmpty()) continue

        for (searchLine in searchLines) {
            val score = similarity(fileLine, searchLine.trim())
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
    }

    val start = maxOf(0, bestIndex - 2)
    val end = minOf(fileLines.size, bestIndex + 3)
    return fileLines.subList(start, end).map { "  $it" }
}

/**
 * Applies search-and-replace edits to a file using 5-step fallback matching.
 */
class EditFileSkill : Skill() {

    override val skillName: String = "edit_file"
    override val alias: String = "Edited"
    override val description: String = "Search and replace edits with sequential or atomic mode"

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
        return executeCloud(arguments, context)
    }

    /**
     * Cloud model execution: search/replace based editing with fuzzy matching.
     * Powerful and flexible — finds text blocks and replaces them.
     */
    private fun executeCloud(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""
        val rawEdits: List<Map<String, Any>> = parseEdits(arguments["edits"])
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
            val fileLines = currentContent.lines()
            val searchLines = edit.search.lines()

            when (val result = findMatchesWithFallback(fileLines, searchLines)) {
                is FindResult.Found -> {
                    if (result.matches.size > 1) {
                        targetFile.writeText(currentContent, Charsets.UTF_8)
                        return makeFailure(
                            ErrorCode.MULTIPLE_MATCHES,
                            buildPartialFailureMessage(
                                "Edit ${edit.index + 1} failed: Found ${result.matches.size} matches.",
                                appliedEdits.size
                            ),
                            mapOf("path" to resolvedPath.toString(), "appliedCount" to appliedEdits.size)
                        )
                    }

                    val match = result.matches.first()
                    val replaceLines = if (edit.replace.isBlank()) emptyList() else edit.replace.lines()
                    linesRemoved += searchLines.size
                    linesAdded += replaceLines.size
                    val newFileLines = fileLines.subList(0, match.startIndex) +
                        replaceLines +
                        fileLines.subList(match.endIndex, fileLines.size)
                    currentContent = newFileLines.joinToString("\n")
                    appliedEdits.add(edit.index)
                }

                is FindResult.NotFound -> {
                    targetFile.writeText(currentContent, Charsets.UTF_8)
                    return makeFailure(
                        ErrorCode.CODE_NOT_FOUND,
                        buildNotFoundMessage(edit, result, appliedEdits.size),
                        mapOf(
                            "path" to resolvedPath.toString(),
                            "appliedCount" to appliedEdits.size,
                            "failedAtStep" to result.failedAtStep.name,
                            "searchPreview" to result.searchPreview,
                            "fileContext" to result.fileContext
                        )
                    )
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
                "syntaxErrors" to SyntaxChecker.checkSyntax(resolvedPath),
                "originalContent" to originalContent,
                "modifiedContent" to currentContent,
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
            val fileLines = workingContent.lines()
            val searchLines = edit.search.lines()

            when (val result = findMatchesWithFallback(fileLines, searchLines)) {
                is FindResult.Found -> {
                    if (result.matches.size > 1) {
                        targetFile.writeText(originalContent, Charsets.UTF_8)
                        return makeFailure(
                            ErrorCode.MULTIPLE_MATCHES,
                            "Edit ${edit.index + 1} failed: Found ${result.matches.size} matches. Original content restored.",
                            mapOf("path" to resolvedPath.toString(), "appliedCount" to 0)
                        )
                    }

                    val match = result.matches.first()
                    val replaceLines = if (edit.replace.isBlank()) emptyList() else edit.replace.lines()
                    linesRemoved += searchLines.size
                    linesAdded += replaceLines.size
                    val newFileLines = fileLines.subList(0, match.startIndex) +
                        replaceLines +
                        fileLines.subList(match.endIndex, fileLines.size)
                    workingContent = newFileLines.joinToString("\n")
                }

                is FindResult.NotFound -> {
                    targetFile.writeText(originalContent, Charsets.UTF_8)
                    return makeFailure(
                        ErrorCode.CODE_NOT_FOUND,
                        "Edit ${edit.index + 1} failed: Code not found. Original content restored.",
                        mapOf(
                            "path" to resolvedPath.toString(),
                            "appliedCount" to 0,
                            "failedAtStep" to result.failedAtStep.name,
                            "searchPreview" to result.searchPreview,
                            "fileContext" to result.fileContext
                        )
                    )
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
                "syntaxErrors" to SyntaxChecker.checkSyntax(resolvedPath),
                "originalContent" to originalContent,
                "modifiedContent" to workingContent,
            ),
        )
    }

    private fun buildPartialFailureMessage(baseMessage: String, appliedCount: Int): String {
        if (appliedCount == 0) return baseMessage
        return "$baseMessage $appliedCount edit(s) applied before failure."
    }

    private fun buildNotFoundMessage(edit: EditOperation, result: FindResult.NotFound, appliedCount: Int): String {
        val step = when (result.failedAtStep) {
            MatchStrategy.EXACT -> "exact match"
            MatchStrategy.NORMALIZED -> "normalized match"
            MatchStrategy.FUZZY -> "fuzzy match (>85% similarity)"
            MatchStrategy.NONE -> "matching"
        }
        val base = "Edit ${edit.index + 1} failed at step: $step"
        val context = if (result.fileContext.isNotEmpty()) {
            "\nNearest file content:\n${result.fileContext.joinToString("\n")}"
        } else ""
        val hint =
            "\nHint: Add more surrounding context (function name, class declaration, comments) to make the match unique."
        val partial = if (appliedCount > 0) " $appliedCount edit(s) applied before failure." else ""
        return "$base$partial$context$hint"
    }

    private data class EditOperation(val search: String, val replace: String, val index: Int)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Suppress("UNCHECKED_CAST")
        fun parseEdits(raw: Any?): List<Map<String, Any>> {
            if (raw is List<*>) return raw.filterIsInstance<Map<String, Any>>()

            if (raw is String && raw.isNotBlank()) {
                try {
                    val element = json.parseToJsonElement(raw)
                    if (element is JsonArray) {
                        return element.mapNotNull { item ->
                            if (item is JsonObject) {
                                val search = (item["search"] as? JsonPrimitive)?.content ?: ""
                                val replace = (item["replace"] as? JsonPrimitive)?.content ?: ""
                                mapOf("search" to search, "replace" to replace)
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
