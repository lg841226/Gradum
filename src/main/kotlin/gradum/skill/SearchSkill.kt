/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchSkill.kt  2026-06-20 20:22:43 Created by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val SEARCH_TIMEOUT_SECONDS: Long = 120
private const val MAXIMUM_FILES: Int = 600
private const val MAXIMUM_DEPTH: Int = 6
private const val HARD_MAX_RESULTS: Int = 20

/**
 * Searches file content, filenames, and directories within a project tree.
 *
 * Bounded by [SEARCH_TIMEOUT_SECONDS], [MAXIMUM_FILES], [MAXIMUM_DEPTH] and
 * [HARD_MAX_RESULTS] to keep the agent's tool loop responsive on large repos.
 */
class SearchSkill : Skill() {

    override val skillName: String = "search"
    override val alias: String = "Explored"
    override val description: String = "Search code content, filenames, and directories"

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "Search query (regex or filename pattern)"),
                        "path" to mapOf("type" to "string", "description" to "Directory to search in"),
                        "type" to mapOf("type" to "string", "description" to "Search type: 'content', 'filename', or 'directory'"),
                    ),
                    "required" to listOf("query"),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>): SkillResult {
        val searchQuery: String = arguments["query"] as? String ?: ""
        val searchPath: String = arguments["path"] as? String ?: "."
        val searchType: String = arguments["type"] as? String ?: "content"

        if (searchQuery.isBlank()) {
            return makeFailure("INVALID_PARAMETER", "Missing 'query' parameter")
        }

        val rootDirectory: Path = Paths.get(searchPath).toAbsolutePath().normalize()

        return try {
            val results: List<SearchResult> = when (searchType) {
                "filename" -> searchByFilename(rootDirectory, searchQuery)
                "directory" -> searchDirectories(rootDirectory, searchQuery)
                else -> searchContent(rootDirectory, searchQuery)
            }

            val limitedResults: List<SearchResult> = results.take(HARD_MAX_RESULTS)
            val truncated: Boolean = results.size > HARD_MAX_RESULTS

            makeSuccess(
                mapOf(
                    "query" to searchQuery,
                    "searchType" to searchType,
                    "results" to limitedResults.map { result -> result.toMap() },
                    "totalMatches" to results.size,
                    "truncated" to truncated,
                ),
            )
        } catch (e: Exception) {
            makeFailure("IO_ERROR", e.message ?: "Search failed", mapOf("query" to searchQuery))
        }
    }

    private fun searchContent(rootDirectory: Path, queryPattern: String): List<SearchResult> {
        val matchingResults: MutableList<SearchResult> = mutableListOf()
        val regex: Regex = try {
            Regex(queryPattern, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return matchingResults
        }

        val deadline: Long = System.currentTimeMillis() + SEARCH_TIMEOUT_SECONDS * 1000
        var filesVisited: Int = 0

        Files.walk(rootDirectory, MAXIMUM_DEPTH)
            .filter { filePath: Path ->
                filesVisited++
                filesVisited <= MAXIMUM_FILES &&
                    System.currentTimeMillis() < deadline &&
                    !filePath.toFile().isDirectory &&
                    !isExcludedDirectory(filePath)
            }
            .forEach { filePath: Path ->
                if (System.currentTimeMillis() >= deadline) return@forEach
                try {
                    val fileLines: List<String> = filePath.toFile().readLines(Charsets.UTF_8)
                    for ((lineIndex: Int, lineContent: String) in fileLines.withIndex()) {
                        if (regex.containsMatchIn(lineContent)) {
                            matchingResults.add(
                                SearchResult(
                                    filePath = filePath.toAbsolutePath().toString(),
                                    lineNumber = lineIndex + 1,
                                    matchedText = lineContent.trim(),
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip unreadable files
                }
            }

        return matchingResults
    }

    private fun searchByFilename(rootDirectory: Path, filenamePattern: String): List<SearchResult> {
        val matchingResults: MutableList<SearchResult> = mutableListOf()
        val regex: Regex = try {
            Regex(filenamePattern, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return matchingResults
        }

        Files.walk(rootDirectory, MAXIMUM_DEPTH)
            .filter { filePath: Path -> !isExcludedDirectory(filePath) }
            .forEach { filePath: Path ->
                val fileName: String = filePath.fileName.toString()
                if (regex.containsMatchIn(fileName)) {
                    matchingResults.add(
                        SearchResult(
                            filePath = filePath.toAbsolutePath().toString(),
                            lineNumber = 0,
                            matchedText = fileName,
                        ),
                    )
                }
            }

        return matchingResults
    }

    private fun searchDirectories(rootDirectory: Path, directoryPattern: String): List<SearchResult> {
        val matchingResults: MutableList<SearchResult> = mutableListOf()
        val regex: Regex = try {
            Regex(directoryPattern, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return matchingResults
        }

        Files.walk(rootDirectory, MAXIMUM_DEPTH)
            .filter { filePath: Path -> filePath.toFile().isDirectory && !isExcludedDirectory(filePath) }
            .forEach { directoryPath: Path ->
                val directoryName: String = directoryPath.fileName.toString()
                if (regex.containsMatchIn(directoryName)) {
                    matchingResults.add(
                        SearchResult(
                            filePath = directoryPath.toAbsolutePath().toString(),
                            lineNumber = 0,
                            matchedText = directoryName,
                        ),
                    )
                }
            }

        return matchingResults
    }

    private fun isExcludedDirectory(directoryPath: Path): Boolean {
        val directoryName: String = directoryPath.fileName.toString()
        return directoryName.startsWith(".") ||
            directoryName == "__pycache__" || directoryName == "node_modules" ||
            directoryName == ".venv" || directoryName == "venv"
    }

    private data class SearchResult(
        val filePath: String,
        val lineNumber: Int,
        val matchedText: String,
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf(
                "filePath" to filePath,
                "lineNumber" to lineNumber,
                "matchedText" to matchedText,
            )
        }
    }
}
