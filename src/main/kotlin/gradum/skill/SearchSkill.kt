/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchSkill.kt  2026-06-22 13:09:03 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.*

private val logger: Logger = LoggerFactory.getLogger("SearchSkill")

private const val SEARCH_TIMEOUT_SECONDS: Long = 120
private const val MAXIMUM_FILES: Int = 600
private const val MAXIMUM_DEPTH: Int = 6
private const val DEFAULT_MAX_RESULTS: Int = 20
private const val MAX_ALLOWED_RESULTS: Int = 100
private const val MAX_CONTEXT_LINES: Int = 10
private const val CONTENT_MATCH_LIMIT: Int = 4096
private const val FILENAME_MATCH_LIMIT: Int = 2048

/**
 * Tracks traversal state during a search, used for diagnostics
 * and structured error reporting.
 */
private data class SearchContext(
    var scannedFileCount: Int = 0,
    var scannedDepth: Int = 0,
    val startTimeMillis: Long = System.currentTimeMillis(),
) {
    fun elapsedMillis(): Long = System.currentTimeMillis() - startTimeMillis
}

/**
 * Thrown when a search exceeds the allowed match count for a given category.
 * Carries traversal context for structured error reporting.
 */
private class SearchOverflowException(
    val keyword: String,
    val searchType: String,
    val matchCount: Int,
    val limit: Int,
    val searchContext: SearchContext,
) : Exception("Match limit exceeded")

/**
 * Searches file contents, filenames, or directories within a project tree.
 *
 * Supports four search modes via the `type` parameter:
 * - `all` (default): runs both content and filename searches, returning categorized results.
 * - `content`: searches file contents only.
 * - `filename`: searches by file name only — ideal when looking for a specific file.
 * - `directory`: searches directory names only.
 *
 * `keyword` accepts a regex pattern (case-insensitive). When an array of strings
 * is provided, they are combined with OR logic — a file matches if it matches
 * *any* of the keywords. For AND logic, combine keywords into a single regex
 * pattern using lookahead: `(?=.*foo)(?=.*bar)`.
 *
 * `file_pattern` supports glob syntax (`*.java`, `*.{kt,py}`) applied to filenames.
 *
 * Results are sorted by relevance: exact matches rank higher than substring
 * matches, filename matches rank higher than content matches, and shorter
 * file paths rank higher than deeper ones.
 *
 * Bounded by [SEARCH_TIMEOUT_SECONDS], [MAXIMUM_FILES], [MAXIMUM_DEPTH],
 * [CONTENT_MATCH_LIMIT], and [FILENAME_MATCH_LIMIT] to keep the agent's
 * tool loop responsive on large repos.
 */
class SearchSkill : Skill() {

    override val skillName: String = "search"
    override val alias: String = "Explored"
    override val description: String = "Search files in a project. 'keyword' is case-insensitive regex. " +
        "Multiple keywords use OR logic (match any). For AND logic, combine into one regex with lookahead. " +
        "type='all' (default) searches both file contents and filenames simultaneously. " +
        "type='content' searches only file contents. " +
        "type='filename' searches only by file name — use when looking for a specific file. " +
        "type='directory' searches directory names. " +
        "Content match limit: 4096. Filename match limit: 2048."

    override val historyKeepCount: Int = 2
    override val historyVolatileKeys: List<String> = listOf("hint", "summary")

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
                            "description" to "Regex keyword (case-insensitive). String or array of up to 5 strings. " +
                                "Array uses OR logic — matches if ANY keyword matches. " +
                                "For AND logic, combine into a single regex with lookahead: (?=.*foo)(?=.*bar).",
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
                            "description" to "Search type: 'all' (default, searches both content and filenames), 'content' (file contents only), 'filename' (file names only), or 'directory' (directory names only).",
                        ),
                        "max_results" to mapOf(
                            "type" to "integer",
                            "description" to "Maximum number of results to return (1-100, default 20).",
                        ),
                        "context_lines" to mapOf(
                            "type" to "integer",
                            "description" to "Number of lines of context around each content match (0-10, default 0). Only applies to content searches.",
                        ),
                    ),
                    "required" to listOf("keyword"),
                ),
            ),
        )
    }

    /**
     * Executes a search across the project tree.
     *
     * Validates input parameters, compiles the file pattern matcher, then
     * dispatches to the appropriate search strategy based on searchType.
     * Returns a unified response structure with results, summary, and hints.
     */
    override fun execute(arguments: Map<String, Any>): SkillResult {
        val keywords: List<String> = extractKeywords(arguments)

        if (keywords.isEmpty())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Missing 'keyword' or 'query' parameter")

        val searchPath: String = arguments["path"] as? String ?: "."
        val searchType: String = arguments["type"] as? String ?: "all"
        val filePatternGlob: String? = arguments["file_pattern"] as? String
        val compiledFileMatcher: PathMatcher? = filePatternGlob?.let { compileGlob(it) }
        val maxResults: Int = (arguments["max_results"] as? Int)?.coerceIn(1, MAX_ALLOWED_RESULTS) ?: DEFAULT_MAX_RESULTS
        val contextLines: Int = (arguments["context_lines"] as? Int)?.coerceIn(0, MAX_CONTEXT_LINES) ?: 0

        val searchRoot: Path = Paths.get(searchPath).toAbsolutePath().normalize()

        // Validate all keywords are legal regex patterns before searching
        for (keyword: String in keywords) {
            try {
                Regex(keyword)
            } catch (exception: Exception) {
                val errorMessage = "Invalid regex in keyword \"$keyword\": ${exception.message}"
                return makeFailure(ErrorCode.INVALID_PARAMETER, errorMessage)
            }
        }

        return try {
            val responsePayload: MutableMap<String, Any> = mutableMapOf(
                "query" to keywords.joinToString(", "),
                "searchType" to searchType,
                "maxResults" to maxResults
            )

            when (searchType) {
                "all" -> executeAllSearch(keywords, searchRoot, compiledFileMatcher, filePatternGlob, maxResults, contextLines, responsePayload)
                "filename" -> executeFilenameSearch(keywords, searchRoot, compiledFileMatcher, filePatternGlob, maxResults, responsePayload)
                "directory" -> executeDirectorySearch(keywords, searchRoot, maxResults, responsePayload)
                else -> executeContentSearch(keywords, searchRoot, compiledFileMatcher, filePatternGlob, maxResults, contextLines, responsePayload)
            }

            makeSuccess(responsePayload)
        } catch (exception: SearchOverflowException) {
            buildOverflowFailure(exception, keywords)
        } catch (exception: Exception) {
            logger.error("Search failed for keywords: {}", keywords, exception)
            makeFailure(ErrorCode.IO_ERROR, exception.message ?: "Search failed", mapOf("query" to keywords.joinToString(", ")))
        }
    }

    /**
     * Runs both content and filename searches, merging results into
     * a unified response with additional categorized breakdowns.
     */
    private fun executeAllSearch(
        keywords: List<String>, searchRoot: Path,
        compiledFileMatcher: PathMatcher?, filePatternGlob: String?,
        maxResults: Int, contextLines: Int,
        responsePayload: MutableMap<String, Any>,
    ) {
        val searchContext = SearchContext()

        val contentMatchedResults: List<SearchResult> = collectSearchResults(keywords) { keyword: String ->
            searchContent(searchRoot, keyword, compiledFileMatcher, keyword, contextLines, searchContext)
        }.distinctBy { "${it.filePath}:${it.lineNumber}:${it.matchedText}" }

        val filenameMatchedResults: List<SearchResult> = collectSearchResults(keywords) { keyword: String ->
            searchByFilename(searchRoot, keyword, compiledFileMatcher, keyword, searchContext)
        }.distinctBy { it.filePath to it.matchedText }

        val allResults: List<SearchResult> = sortByRelevance(contentMatchedResults + filenameMatchedResults)
        val displayedResults: List<SearchResult> = allResults.take(maxResults)

        responsePayload["results"] = displayedResults.map { it.toMap() }
        responsePayload["totalMatches"] = allResults.size
        responsePayload["truncated"] = allResults.size > maxResults

        responsePayload["contentResults"] = contentMatchedResults.take(maxResults).map { it.toMap() }
        responsePayload["contentTotal"] = contentMatchedResults.size
        responsePayload["filenameResults"] = filenameMatchedResults.take(maxResults).map { it.toMap() }
        responsePayload["filenameTotal"] = filenameMatchedResults.size

        responsePayload["summary"] = buildSearchSummary(allResults)

        val structuredHint: Map<String, Any>? = buildStructuredHint(allResults, keywords, filePatternGlob, searchContext)
        structuredHint?.let { responsePayload["hint"] = it }
    }

    /**
     * Runs a filename-only search and populates [responsePayload].
     */
    private fun executeFilenameSearch(
        keywords: List<String>,
        searchRoot: Path,
        compiledFileMatcher: PathMatcher?,
        filePatternGlob: String?,
        maxResults: Int,
        responsePayload: MutableMap<String, Any>,
    ): Unit {
        val searchContext = SearchContext()

        val matchedResults: List<SearchResult> = collectSearchResults(keywords) { keyword: String ->
            searchByFilename(searchRoot, keyword, compiledFileMatcher, keyword, searchContext)
        }.distinctBy { it.filePath to it.matchedText }

        val sortedResults: List<SearchResult> = sortByRelevance(matchedResults)
        populateUnifiedResponse(sortedResults, maxResults, responsePayload)

        val structuredHint: Map<String, Any>? = buildStructuredHint(sortedResults, keywords, filePatternGlob, searchContext)
        structuredHint?.let { responsePayload["hint"] = it }
    }

    /**
     * Runs a directory-only search and populates [responsePayload].
     */
    private fun executeDirectorySearch(
        keywords: List<String>,
        searchRoot: Path,
        maxResults: Int,
        responsePayload: MutableMap<String, Any>,
    ): Unit {
        val searchContext: SearchContext = SearchContext()

        val matchedResults: List<SearchResult> = collectSearchResults(keywords) { keyword: String ->
            searchDirectories(searchRoot, keyword)
        }.distinctBy { it -> it.filePath to it.matchedText }

        populateUnifiedResponse(matchedResults, maxResults, responsePayload)
    }

    /**
     * Runs a content-only search and populates [responsePayload].
     */
    private fun executeContentSearch(
        keywords: List<String>,
        searchRoot: Path,
        compiledFileMatcher: PathMatcher?,
        filePatternGlob: String?,
        maxResults: Int,
        contextLines: Int,
        responsePayload: MutableMap<String, Any>,
    ): Unit {
        val searchContext = SearchContext()

        val matchedResults: List<SearchResult> = collectSearchResults(keywords) { keyword: String ->
            searchContent(searchRoot, keyword, compiledFileMatcher, keyword, contextLines, searchContext)
        }.distinctBy { "${it.filePath}:${it.lineNumber}:${it.matchedText}" }

        val sortedResults: List<SearchResult> = sortByRelevance(matchedResults)
        populateUnifiedResponse(sortedResults, maxResults, responsePayload)

        val structuredHint: Map<String, Any>? = buildStructuredHint(sortedResults, keywords, filePatternGlob, searchContext)
        structuredHint?.let { responsePayload["hint"] = it }
    }

    /**
     * Extracts and normalizes search keywords from the argument map.
     *
     * Accepts `keyword` (String or List<String>) as the primary input.
     * Falls back to the deprecated `query` parameter for backward compatibility.
     * Returns at most 5 non-blank keywords to bound OR-search cardinality.
     *
     * @param arguments Map containing search parameters
     * @return Normalized list of non-blank keywords, limited to 5 items
     */
    private fun extractKeywords(arguments: Map<String, Any>): List<String> {
        val rawKeyword: Any? = arguments["keyword"]

        if (rawKeyword != null) {
            return when (rawKeyword) {
                is String ->
                    if (rawKeyword.isBlank())
                        emptyList()
                    else
                        listOf(rawKeyword.trim())

                is List<*> ->
                    rawKeyword
                        .filterIsInstance<String>().map { keywordString: String -> keywordString.trim() }
                        .filter { trimmedKeyword: String -> trimmedKeyword.isNotBlank() }.take(5)

                else -> emptyList()
            }
        }

        val deprecatedQueryParameter: String = arguments["query"] as? String ?: ""

        return if (deprecatedQueryParameter.isBlank())
            emptyList()
        else
            listOf(deprecatedQueryParameter.trim())
    }

    /**
     * Compiles a glob pattern string into a [PathMatcher].
     * Returns null if the pattern is invalid.
     */
    private fun compileGlob(pattern: String): PathMatcher? {
        return try {
            val defaultFileSystem: FileSystem = FileSystems.getDefault()
            val prefixedPattern: String = if (!pattern.startsWith("glob:")) "glob:$pattern" else pattern
            defaultFileSystem.getPathMatcher(prefixedPattern)
        } catch (exception: IllegalArgumentException) {
            logger.warn("Invalid glob pattern '{}': {}", pattern, exception.message)
            null
        }
    }

    /**
     * Runs a search function across all keywords, collecting results.
     */
    private fun collectSearchResults(keywords: List<String>, searchFunction: (String) -> List<SearchResult>): List<SearchResult> {
        return keywords.flatMap { keyword: String -> searchFunction(keyword) }
    }

    /**
     * Populates [responsePayload] with the unified response structure:
     * results, totalMatches, truncated, summary, and optionally hint.
     */
    private fun populateUnifiedResponse(
        sortedResults: List<SearchResult>, maxResults: Int,
        responsePayload: MutableMap<String, Any>,
    ){
        val displayedResults: List<SearchResult> = sortedResults.take(maxResults)
        responsePayload["results"] = displayedResults.map { it.toMap() }
        responsePayload["totalMatches"] = sortedResults.size
        responsePayload["truncated"] = sortedResults.size > maxResults
        responsePayload["summary"] = buildSearchSummary(sortedResults)
    }

    /**
     * Searches file contents for lines matching the given regex pattern.
     *
     * Walks the directory tree up to [MAXIMUM_DEPTH], visiting at most
     * [MAXIMUM_FILES] files. When [contextLines] > 0, includes surrounding
     * lines in the matchedText field. Throws [SearchOverflowException] when
     * [CONTENT_MATCH_LIMIT] is exceeded.
     */
    private fun searchContent(searchRoot: Path, queryPattern: String,
        compiledFileMatcher: PathMatcher?, keyword: String,
        contextLines: Int, searchContext: SearchContext,
    ): List<SearchResult> {
        val matchedResults: MutableList<SearchResult> = mutableListOf()

        val compiledRegex: Regex = try {
            Regex(queryPattern, setOf(RegexOption.IGNORE_CASE))
        } catch (exception: Exception) {
            logger.warn("Invalid content regex '{}': {}", queryPattern, exception.message)
            return matchedResults
        }

        val timeoutDeadline: Long = System.currentTimeMillis() + SEARCH_TIMEOUT_SECONDS * 1000

        Files.walk(searchRoot, MAXIMUM_DEPTH)
            .filter { currentPath: Path ->
                searchContext.scannedFileCount++

                if (searchContext.scannedFileCount > MAXIMUM_FILES) return@filter false
                if (System.currentTimeMillis() >= timeoutDeadline) return@filter false
                if (currentPath.toFile().isDirectory) return@filter false
                if (isExcludedDirectory(currentPath)) return@filter false
                if (compiledFileMatcher != null && !compiledFileMatcher.matches(currentPath.fileName)) return@filter false

                val depth: Int = currentPath.toString().count { it == '/' || it == '\\' }

                if (depth > searchContext.scannedDepth)
                    searchContext.scannedDepth = depth
                true
            }
            .forEach { currentPath: Path ->
                if (System.currentTimeMillis() >= timeoutDeadline) return@forEach

                try {
                    val fileLines: List<String> = currentPath.toFile().readLines(Charsets.UTF_8)
                    val filePathString: String = currentPath.toAbsolutePath().toString()

                    for ((lineNumber: Int, lineText: String) in fileLines.withIndex()) {
                        if (compiledRegex.containsMatchIn(lineText)) {
                            val matchedContent: String = if (contextLines > 0) {
                                val startLine: Int = (lineNumber - contextLines).coerceAtLeast(0)
                                val endLine: Int = (lineNumber + contextLines).coerceAtMost(fileLines.size - 1)
                                fileLines.subList(startLine, endLine + 1).joinToString("\n")
                            } else {
                                lineText.trim()
                            }

                            matchedResults.add(
                                SearchResult(
                                    filePath = filePathString,
                                    lineNumber = lineNumber + 1,
                                    matchedText = matchedContent,
                                    matchType = "content",
                                ),
                            )

                            if (matchedResults.size > CONTENT_MATCH_LIMIT)
                                throw SearchOverflowException(keyword, "content", matchedResults.size, CONTENT_MATCH_LIMIT, searchContext)
                        }
                    }
                } catch (exception: SearchOverflowException) {
                    throw exception
                } catch (exception: Exception) {
                    logger.debug("Failed to read file {}: {}", currentPath, exception.message)
                }
            }

        return matchedResults
    }

    /**
     * Searches filenames for matches against the given regex pattern.
     *
     * Walks the directory tree up to [MAXIMUM_DEPTH], filtering out
     * excluded directories. Throws [SearchOverflowException] when
     * [FILENAME_MATCH_LIMIT] is exceeded.
     */
    private fun searchByFilename(searchRoot: Path, filenamePattern: String,
        compiledFileMatcher: PathMatcher?, keyword: String, searchContext: SearchContext
    ): List<SearchResult> {
        val matchedResults: MutableList<SearchResult> = mutableListOf()
        val compiledRegex: Regex = try {
            Regex(filenamePattern, setOf(RegexOption.IGNORE_CASE))
        } catch (exception: Exception) {
            logger.warn("Invalid filename regex '{}': {}", filenamePattern, exception.message)
            return matchedResults
        }

        Files.walk(searchRoot, MAXIMUM_DEPTH)
            .filter { currentPath: Path ->
                if (currentPath.toFile().isDirectory) return@filter false
                if (isExcludedDirectory(currentPath)) return@filter false
                if (compiledFileMatcher != null && !compiledFileMatcher.matches(currentPath.fileName)) return@filter false
                true
            }
            .forEach { currentPath: Path ->
                val currentFileName: String = currentPath.fileName.toString()

                if (compiledRegex.containsMatchIn(currentFileName)) {
                    matchedResults.add(
                        SearchResult(
                            filePath = currentPath.toAbsolutePath().toString(),
                            lineNumber = 0,
                            matchedText = currentFileName,
                            matchType = "filename"
                        )
                    )

                    if (matchedResults.size > FILENAME_MATCH_LIMIT)
                        throw SearchOverflowException(keyword, "filename", matchedResults.size, FILENAME_MATCH_LIMIT, searchContext)
                }
            }
        return matchedResults
    }

    /**
     * Searches directory names for matches against the given regex pattern.
     */
    private fun searchDirectories(searchRoot: Path, directoryPattern: String): List<SearchResult> {
        val matchedResults: MutableList<SearchResult> = mutableListOf()
        val compiledRegex: Regex = try {
            Regex(directoryPattern, setOf(RegexOption.IGNORE_CASE))
        } catch (exception: Exception) {
            logger.warn("Invalid directory regex '{}': {}", directoryPattern, exception.message)
            return matchedResults
        }

        Files.walk(searchRoot, MAXIMUM_DEPTH)
            .filter { currentPath: Path -> currentPath.toFile().isDirectory && !isExcludedDirectory(currentPath) }
            .forEach { currentDirectoryPath: Path ->
                val currentDirectoryName: String = currentDirectoryPath.fileName.toString()

                if (compiledRegex.containsMatchIn(currentDirectoryName)) {
                    matchedResults.add(
                        SearchResult(
                            filePath = currentDirectoryPath.toAbsolutePath().toString(),
                            lineNumber = 0,
                            matchedText = currentDirectoryName,
                            matchType = "directory"
                        ),
                    )
                }
            }
        return matchedResults
    }

    /**
     * Returns true if the directory should be skipped during traversal.
     * Excludes hidden directories, common build/cache directories,
     * and dependency folders.
     */
    private fun isExcludedDirectory(directoryPath: Path): Boolean {
        val directoryName: String = directoryPath.fileName.toString()
        return directoryName.startsWith(".") ||
            directoryName == "__pycache__" || directoryName == "node_modules" ||
            directoryName == ".venv" || directoryName == "venv" ||
            directoryName == "build" || directoryName == "output"
    }

    /**
     * Sorts search results by relevance.
     *
     * Priority order:
     * 1. Match type: filename > content
     * 2. Match quality: exact match > prefix match > substring match
     * 3. Path depth: shorter paths rank higher
     */
    private fun sortByRelevance(results: List<SearchResult>): List<SearchResult> {
        return results.sortedWith(compareByDescending<SearchResult> { it.matchType == "filename" }
            .thenByDescending { calculateMatchQuality(it) }
            .thenBy { it.filePath.length })
    }

    /**
     * Calculates a match quality score for a single result.
     * Higher scores indicate better matches.
     */
    private fun calculateMatchQuality(result: SearchResult): Int {
        val fileName: String = if (result.matchType == "filename") result.matchedText
        else result.filePath.substringAfterLast('/').substringAfterLast('\\')

        val queryWords: String = result.matchedText.lowercase()

        return when {
            fileName.lowercase() == queryWords -> 100
            fileName.lowercase().startsWith(queryWords) -> 80
            fileName.lowercase().contains(queryWords) -> 60
            else -> 40
        }
    }

    /**
     * Builds a summary of search results grouped by file and match type.
     */
    private fun buildSearchSummary(results: List<SearchResult>): Map<String, Any> {
        val byFile: Map<String, Int> = results
            .groupBy { it.filePath }.mapValues { it.value.size }

        val byMatchType: Map<String, Int> = results
            .groupBy { it.matchType }.mapValues { it.value.size }

        return mapOf("byFile" to byFile, "byMatchType" to byMatchType)
    }

    /**
     * Builds a structured hint when results exceed the display limit.
     * Returns a JSON-compatible map with suggestedFilters, mostCommonExtensions,
     * and estimatedMatches. Only suggests file_patterns that actually exist
     * in the result set.
     */
    private fun buildStructuredHint(
        results: List<SearchResult>, keywords: List<String>,
        filePatternGlob: String?, searchContext: SearchContext
    ): Map<String, Any>? {
        if (results.isEmpty()) return null

        val matchedFilePaths: List<String> = results.map { it.filePath }.distinct()
        val extensionDistribution: Map<String, Int> = matchedFilePaths
            .groupBy { filePath: String -> filePath.substringAfterLast('.', "") }.mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .take(5).associate { it.key to it.value }

        val suggestedFilters: MutableList<String> = mutableListOf()

        if (filePatternGlob == null && extensionDistribution.isNotEmpty()) {
            val topExtension: String = extensionDistribution.keys.first()
            suggestedFilters.add("file_pattern=\"*.$topExtension\"")
        }

        if (keywords.size > 1) suggestedFilters.add("fewer keywords")

        suggestedFilters.add("more specific keyword")
        suggestedFilters.add("narrower path")

        return mapOf(
            "estimatedMatches" to results.size,
            "mostCommonExtensions" to extensionDistribution,
            "suggestedFilters" to suggestedFilters,
            "scannedFiles" to searchContext.scannedFileCount,
            "scannedDepth" to searchContext.scannedDepth,
            "elapsedMs" to searchContext.elapsedMillis(),
        )
    }

    /**
     * Builds a structured failure response when a search overflows.
     * Includes traversal diagnostics for debugging.
     */
    private fun buildOverflowFailure(exception: SearchOverflowException, keywords: List<String>): SkillResult {
        val suggestion: String = "Try: 1) add `file_pattern` to narrow file types, " +
            "2) use more specific keyword, 3) add `path` to limit directory scope."

        return makeFailure(
            ErrorCode.INVALID_PARAMETER,
            "Too many matches for keyword \"${exception.keyword}\" " +
                "(${exception.searchType} search: ${exception.matchCount} matches > limit ${exception.limit}). " +
                "Scanned ${exception.searchContext.scannedFileCount} files " +
                "(depth ${exception.searchContext.scannedDepth}) " +
                "in ${exception.searchContext.elapsedMillis()}ms. $suggestion",
            mapOf(
                "query" to keywords.joinToString(", "),
                "overflowKeyword" to exception.keyword,
                "overflowType" to exception.searchType,
                "overflowCount" to exception.matchCount,
                "overflowLimit" to exception.limit,
                "scannedFiles" to exception.searchContext.scannedFileCount,
                "scannedDepth" to exception.searchContext.scannedDepth,
                "elapsedMs" to exception.searchContext.elapsedMillis()
            ),
        )
    }

    private data class SearchResult(
        val filePath: String, val lineNumber: Int,
        val matchedText: String, val matchType: String
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf(
                "filePath" to filePath,
                "lineNumber" to lineNumber,
                "matchedText" to matchedText,
                "matchType" to matchType
            )
        }
    }
}
