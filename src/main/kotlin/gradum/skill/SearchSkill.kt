/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchSkill.kt  2026-06-22 13:09:03 Changed by gwy
 */

package gradum.skill

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess

private const val SEARCH_TIMEOUT_SECONDS: Long = 120
private const val MAXIMUM_FILES: Int = 600
private const val MAXIMUM_DEPTH: Int = 6
private const val HARD_MAX_RESULTS: Int = 20
private val BINARY_EXTENSIONS: Set<String> = setOf(
    "class", "jar", "png", "jpg", "jpeg", "gif", "bmp", "ico",
    "pdf", "mp3", "mp4", "exe", "dll", "so", "dylib",
    "zip", "tar", "gz",
)

/**
 * Searches file content, filenames, and directories within a project tree.
 *
 * Bounded by [SEARCH_TIMEOUT_SECONDS], [MAXIMUM_FILES], [MAXIMUM_DEPTH] and
 * [HARD_MAX_RESULTS] to keep the agent's tool loop responsive on large repos.
 *
 * Parameters
 *
 * - `keyword` — term(s) to search for. Accepts a single string or an array of
 *   up to 5 strings (OR-logic). Backward-compatible with `query`.
 * - `file_pattern` — glob pattern to filter files (e.g. `*.java`, `*.{kt,py}`).
 * - `path` — directory to search in (default `.`).
 * - `type` — `content` (default), `filename`, or `directory`.
 */
class SearchSkill : Skill() {

    override val skillName: String = "search"
    override val alias: String = "Explored"
    override val description: String = "Search code content, filenames, and directories"

    override val historyKeepCount: Int = 2
    override val historyVolatileKeys: List<String> = listOf("searchType", "truncated")

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "keyword" to mapOf(
                            "type" to "string",
                            "description" to "Search keyword (string) or keywords (array of up to 5 strings, OR-logic). Use instead of 'query'.",
                        ),
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "Deprecated — use 'keyword' instead. Single search term.",
                        ),
                        "file_pattern" to mapOf(
                            "type" to "string",
                            "description" to "Glob pattern to filter files by name (e.g. '*.java', '*.{kt,py}'). Uses fnmatch syntax.",
                        ),
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "Directory to search in. Defaults to current directory.",
                        ),
                        "type" to mapOf(
                            "type" to "string",
                            "description" to "Search type: 'content' (default), 'filename', or 'directory'.",
                        ),
                    ),
                    "required" to listOf("keyword"),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>): SkillResult {
        val keywords: List<String> = extractKeywords(arguments)
        if (keywords.isEmpty()) {
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Missing 'keyword' or 'query' parameter")
        }

        val searchPath: String = arguments["path"] as? String ?: "."
        val searchType: String = arguments["type"] as? String ?: "content"
        val rawFilePattern: String? = arguments["file_pattern"] as? String
        val fileMatcher: PathMatcher? = rawFilePattern?.let { compileGlob(it) }

        val rootDirectory: Path = Paths.get(searchPath).toAbsolutePath().normalize()

        return try {
            val results: List<SearchResult> = when (searchType) {
                "filename" -> keywords.flatMap { kw ->
                    searchByFilename(rootDirectory, kw, fileMatcher)
                }.distinctBy { it.filePath to it.matchedText }

                "directory" -> keywords.flatMap { kw ->
                    searchDirectories(rootDirectory, kw)
                }.distinctBy { it.filePath to it.matchedText }

                else -> keywords.flatMap { kw ->
                    searchContent(rootDirectory, kw, fileMatcher)
                }.distinctBy { "${it.filePath}:${it.lineNumber}:${it.matchedText}" }
            }

            val limitedResults: List<SearchResult> = results.take(HARD_MAX_RESULTS)
            val truncated: Boolean = results.size > HARD_MAX_RESULTS
            val hint: String? = truncatedHint(results, keywords, rawFilePattern, searchType)

            val resultMap: MutableMap<String, Any> = mutableMapOf(
                "query" to keywords.joinToString(", "),
                "searchType" to searchType,
                "results" to limitedResults.map { it.toMap() },
                "totalMatches" to results.size,
                "truncated" to truncated,
            )
            hint?.let { resultMap["hint"] = it }

            makeSuccess(resultMap)
        } catch (exception: Exception) {
            makeFailure(ErrorCode.IO_ERROR, exception.message ?: "Search failed", mapOf("query" to keywords.joinToString(", ")))
        }
    }

    /**
     * Extracts and normalizes search keywords from the argument map.
     *
     * Accepts `keyword` (String or List<String>) as the primary input.
     * Falls back to the deprecated `query` parameter for backward compatibility.
     * Returns at most 5 non-blank keywords to bound OR-search cardinality.
     */
    private fun extractKeywords(args: Map<String, Any>): List<String> {
        val keyword: Any? = args["keyword"]
        if (keyword != null) {
            return when (keyword) {
                is String -> if (keyword.isBlank()) emptyList() else listOf(keyword.trim())
                is List<*> -> keyword
                    .filterIsInstance<String>()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(5)
                else -> emptyList()
            }
        }
        val query: String = args["query"] as? String ?: ""
        return if (query.isBlank()) emptyList() else listOf(query.trim())
    }

    private fun compileGlob(pattern: String): PathMatcher? {
        return try {
            val fileSystem: FileSystem = FileSystems.getDefault()
            val normalised: String = if (!pattern.startsWith("glob:")) "glob:$pattern" else pattern
            fileSystem.getPathMatcher(normalised)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun searchContent(rootDirectory: Path, queryPattern: String, fileMatcher: PathMatcher?): List<SearchResult> {
        val matchingResults: MutableList<SearchResult> = mutableListOf()
        val regex: Regex = try {
            Regex(queryPattern, setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
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
                    !isExcludedDirectory(filePath) &&
                    !isBinaryFile(filePath) &&
                    (fileMatcher == null || fileMatcher.matches(filePath.fileName))
            }
            .forEach { filePath: Path ->
                if (System.currentTimeMillis() >= deadline) return@forEach
                // Skip binary files: a .class / .jar / .png / .pdf / etc. will read as garbage
                // and the regex may spuriously match on embedded source-file metadata strings
                // (e.g. .class files contain "VsixDownLoader.java" as a SourceFile attribute).
                if (isBinaryFile(filePath)) return@forEach
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
                } catch (_: Exception) {
                }
            }

        return matchingResults
    }

    private fun searchByFilename(rootDirectory: Path, filenamePattern: String, fileMatcher: PathMatcher?): List<SearchResult> {
        val matchingResults: MutableList<SearchResult> = mutableListOf()
        val regex: Regex = try {
            Regex(filenamePattern, setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
            return matchingResults
        }

        Files.walk(rootDirectory, MAXIMUM_DEPTH)
            .filter { filePath: Path ->
                !filePath.toFile().isDirectory &&
                    !isExcludedDirectory(filePath) &&
                    (fileMatcher == null || fileMatcher.matches(filePath.fileName))
            }
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
        } catch (_: Exception) {
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
            directoryName == ".venv" || directoryName == "venv" ||
            directoryName == "build" || directoryName == "output"
    }

    private fun isBinaryFile(filePath: Path): Boolean {
        val extension: String = filePath.toString().substringAfterLast('.', "").lowercase()
        return extension in BINARY_EXTENSIONS
    }

    private fun truncatedHint(results: List<SearchResult>, keywords: List<String>, filePattern: String?, searchType: String): String? {
        if (results.size <= HARD_MAX_RESULTS) return null

        val files: List<String> = results.map { it.filePath }.distinct()
        val extensions: Map<String, Int> = files
            .groupBy { file -> file.substringAfterLast('.', "") }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .associate { it.key to it.value }

        val topExtension: String? = extensions.entries.firstOrNull()?.key

        return buildString {
            append("Found ${results.size} matches (showing $HARD_MAX_RESULTS). ")
            if (extensions.size == 1 && topExtension != null && filePattern == null) {
                append("All matches are in .$topExtension files. ")
                append("Try adding `file_pattern=\"*.$topExtension\"` to narrow the search.")
            } else if (extensions.size <= 3 && filePattern == null) {
                val matchedExtensions: String = extensions.keys.joinToString(", ") { ".$it" }
                append("Matches span $matchedExtensions files. ")
                append("Add `file_pattern=\"*.${topExtension}\"` to focus on the most common type.")
            } else if (keywords.size == 1) {
                append("Try adding more specific keywords or a `file_pattern`.")
            } else {
                append("Try narrowing with a `file_pattern` or fewer keywords.")
            }
        }
    }

    private data class SearchResult(
        val filePath: String,
        val lineNumber: Int,
        val matchedText: String,
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf("filePath" to filePath, "lineNumber" to lineNumber, "matchedText" to matchedText)
        }
    }
}
