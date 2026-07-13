/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchSkills.kt  2026-07-10 18:28:23 Changed by gwy
 */
package gradum.skill

import gradum.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

private const val MAX_CONCURRENCY: Int = 4
private const val MAX_FILE_SIZE: Long = 2L * 1024 * 1024
private const val MAX_MATCHES_DEFAULT: Int = 100
private const val MAX_MATCHES_LIMIT: Int = 1000
private const val MIN_PATTERN_LENGTH: Int = 3
private const val GLOB_MAX_RESULTS: Int = 500
private const val GLOB_MAX_LIMIT: Int = 2000

private val BINARY_EXTENSIONS: Set<String> = setOf(
  "class", "jar", "so", "dylib", "dll", "exe", "bin", "o", "a",
  "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "tiff",
  "mp3", "mp4", "avi", "mov", "mkv", "flv", "wmv", "ogg", "wav",
  "woff", "woff2", "ttf", "eot", "otf",
  "pyc", "pyo", "wasm"
)

private val ARCHIVE_EXTENSIONS: Set<String> = setOf(
  "zip", "tar", "gz", "bz2", "xz", "rar", "7z",
  "war", "ear", "tgz", "tbz2", "txz", "zst", "lz4"
)

private val DOCUMENT_EXTENSIONS: Set<String> = setOf(
  "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
  "odt", "ods", "odp", "rtf"
)

private val SKIPPED_DIRECTORY_NAMES: Set<String> = setOf(
  ".git", ".svn", ".hg",
  ".gradle", "build", "out", ".idea", "target", "bin", "obj",
  ".kotlin", "cmake-build-debug", "cmake-build-release", "DerivedData",
  "node_modules", ".next", ".nuxt", "dist", ".turbo", ".parcel-cache",
  "__pycache__", ".venv", "venv", "env", ".eggs", ".pytest_cache",
  ".mypy_cache", ".ruff_cache", ".tox",
  "vendor", ".bundle",
  "Pods", ".build",
  ".terraform", ".dart_tool", ".serverless", ".expo", ".vercel",
  "coverage", ".nyc_output"
)

private data class SearchParams(
  val pattern: String,
  val regex: Regex,
  val rootFile: File,
  val resolvedPath: Path,
  val includeFilter: String,
  val limit: Int
)

private data class GlobParams(
  val pattern: String,
  val globMatcher: PathMatcher,
  val fallbackMatcher: PathMatcher?,
  val rootFile: File,
  val resolvedPath: Path,
  val projectRoot: Path,
  val limit: Int
)

private sealed class Either<out T, out E> {
  data class Success<T>(val value: T) : Either<T, Nothing>()
  data class Failure<E>(val error: E) : Either<Nothing, E>()
}

private fun resolveSearchPathImpl(context: SkillContext, relativePath: String?): Path? {
  val projectRoot = context.projectRoot
  if (projectRoot.isBlank()) return null

  if (relativePath.isNullOrBlank()) {
    return try {
      Paths.get(projectRoot).toAbsolutePath().normalize()
    } catch (_: Exception) {
      null
    }
  }

  val resolved: ResolvedProjectPath = resolveProjectPath(relativePath, projectRoot)
  return try {
    resolved.resolved
  } catch (_: Exception) {
    null
  }
}

private fun validateSearchDir(resolvedPath: Path): SkillResult? {
  val rootFile = resolvedPath.toFile()
  if (!rootFile.exists()) {
    return makeFailure(
      ErrorCode.FILE_NOT_FOUND,
      buildXmlError(
        code = "FILE_NOT_FOUND",
        message = "Search path does not exist: $resolvedPath",
        fixHint = "Verify the directory exists. Use explore_project to find the correct path."
      )
    )
  }
  if (!rootFile.isDirectory) {
    return makeFailure(
      ErrorCode.INVALID_PARAMETER,
      buildXmlError(
        code = "INVALID_PARAMETER",
        message = "Search path is not a directory: $resolvedPath",
        fixHint = "The path must point to a directory, not a file."
      )
    )
  }
  return null
}

private fun resolveAndValidatePath(
  context: SkillContext,
  relativePath: String?
): Either<Pair<File, Path>, SkillResult> {
  val resolvedPath = resolveSearchPathImpl(context, relativePath)
    ?: return Either.Failure(
      makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Invalid search path.",
          fixHint = "Ensure the path is a valid directory path relative to the project root."
        )
      )
    )

  val dirError = validateSearchDir(resolvedPath)
  if (dirError != null) return Either.Failure(dirError)

  return Either.Success(resolvedPath.toFile() to resolvedPath)
}

private fun walkFiltered(
  root: File, onFile: (File) -> Unit
) {
  walkFilteredImpl(root, onFile)
}

private fun walkFilteredImpl(
  dir: File, onFile: (File) -> Unit
) {
  val entries = try {
    dir.listFiles()?.toList() ?: emptyList()
  } catch (_: SecurityException) {
    return
  }

  for (entry in entries) {
    if (entry.isDirectory) {
      if (entry.name in SKIPPED_DIRECTORY_NAMES || entry.name.startsWith(".")) continue
      walkFilteredImpl(entry, onFile)
    } else {
      if (entry.length() > MAX_FILE_SIZE) continue
      val ext = entry.name.substringAfterLast('.', "").lowercase()
      if (ext in BINARY_EXTENSIONS) continue
      if (ext in ARCHIVE_EXTENSIONS) continue
      if (ext in DOCUMENT_EXTENSIONS) continue
      onFile(entry)
    }
  }
}

@Suppress("unused")
class GrepSkill : Skill() {

  override val skillName: String = "grep"
  override val alias: String = "Grep"
  override val description: String = "Search file contents by regular expression pattern." +
    "Returns matched lines with file path and line number."

  override val allowedToolModes: Set<ToolMode> =
    setOf(ToolMode.AGENT, ToolMode.EDIT, ToolMode.READ_ONLY)

  /**
   * Keep the last 5 grep results' full `matches` in history.
   * Older results have their `matches` array stripped by
   * [gradum.skill.Skill.compactHistory]'s default impl; the
   * LLM can re-run the grep to get the matches back. Current
   * call always returns in full.
   */
  override val historyKeepCount: Int = 5

  override val historyVolatileKeys: List<String> = listOf("matches")

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimpleSchema =
      context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE

    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to if (useSimpleSchema) localDescription() else description,
        "parameters" to mapOf(
          "type" to "object",
          "properties" to if (useSimpleSchema) localProperties() else cloudProperties(),
          "required" to listOf("pattern")
        )
      )
    )
  }

  private fun localDescription(): String =
    "Search file contents by pattern. Returns matching lines with file path and line number."

  private fun localProperties(): Map<String, Any> = mapOf(
    "pattern" to mapOf(
      "type" to "string",
      "description" to "Text or regex pattern to search for."
    ),
    "path" to mapOf(
      "type" to "string",
      "description" to "Directory to search in. Relative to project root. Defaults to project root."
    ),
    "include" to mapOf(
      "type" to "string",
      "description" to "File filter, e.g. '*.txt'."
    )
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "pattern" to mapOf(
      "type" to "string",
      "description" to "Regular expression pattern to search for in file contents."
    ),
    "path" to mapOf(
      "type" to "string",
      "description" to "Directory path to search in. Relative to project root. Defaults to project root."
    ),
    "include" to mapOf(
      "type" to "string",
      "description" to "File glob pattern to filter which files to search, e.g. '*.kt' or '*.{kt,java}'."
    ),
    "limit" to mapOf(
      "type" to "integer",
      "description" to "Maximum number of matches to return. Defaults to 100.",
      "minimum" to 1,
      "maximum" to MAX_MATCHES_LIMIT
    ),
    "caseSensitive" to mapOf(
      "type" to "boolean",
      "description" to "Whether the search is case-sensitive. Defaults to false."
    )
  )

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val useSimpleSchema = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    return if (useSimpleSchema) executeLocal(arguments, context)
    else executeCloud(arguments, context)
  }

  private fun prepareSearch(
    arguments: Map<String, Any>,
    context: SkillContext,
    ignoreCase: Boolean
  ): Either<SearchParams, SkillResult> {
    val patternStr = (arguments["pattern"] as? String)?.trim() ?: ""
    if (patternStr.length < MIN_PATTERN_LENGTH) {
      return Either.Failure(
        makeFailure(
          ErrorCode.INVALID_PARAMETER,
          buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Pattern must be at least $MIN_PATTERN_LENGTH characters long. Got ${patternStr.length}.",
            fixHint = "Provide a search pattern with at least $MIN_PATTERN_LENGTH characters."
          )
        )
      )
    }

    val flags = if (ignoreCase) setOf(RegexOption.IGNORE_CASE)
    else emptySet()

    val regex = try {
      Regex(patternStr, flags)
    } catch (e: PatternSyntaxException) {
      return Either.Failure(
        makeFailure(
          ErrorCode.INVALID_PARAMETER,
          buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Invalid regex pattern: ${e.message}",
            fixHint = "Check the regex syntax. Use simple text for literal searches."
          )
        )
      )
    }

    val pathResult = resolveAndValidatePath(context, arguments["path"] as? String)
    if (pathResult is Either.Failure) return pathResult
    val (rootFile, resolvedPath) = (pathResult as Either.Success).value

    val includeFilter = (arguments["include"] as? String)?.trim() ?: ""
    val limit = when (val v = arguments["limit"]) {
      is Number -> v.toInt().coerceIn(1, MAX_MATCHES_LIMIT)
      is String -> v.toIntOrNull()?.coerceIn(1, MAX_MATCHES_LIMIT) ?: MAX_MATCHES_DEFAULT
      else -> MAX_MATCHES_DEFAULT
    }

    return Either.Success(
      SearchParams(
        pattern = patternStr,
        regex = regex,
        rootFile = rootFile,
        resolvedPath = resolvedPath,
        includeFilter = includeFilter,
        limit = limit
      )
    )
  }

  private fun executeLocal(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val params = when (val result = prepareSearch(arguments, context, ignoreCase = false)) {
      is Either.Success -> result.value
      is Either.Failure -> return result.error
    }

    val includeMatcher = buildIncludeMatcher(params.includeFilter)
    val files = collectFiles(params.rootFile, includeMatcher)
    if (files.isEmpty()) return buildEmptyResult(params)

    val results = searchFiles(files, params.regex, params.limit)
    return buildSearchResult(params, results, files.size)
  }

  private fun executeCloud(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val caseSensitive = when (val v = arguments["caseSensitive"]) {
      is Boolean -> v
      is String -> v.toBooleanStrictOrNull() ?: false
      else -> false
    }

    val params = when (val result = prepareSearch(arguments, context, ignoreCase = !caseSensitive)) {
      is Either.Success -> result.value
      is Either.Failure -> return result.error
    }

    val includeMatcher = buildIncludeMatcher(params.includeFilter)
    val files = collectFiles(params.rootFile, includeMatcher)
    if (files.isEmpty()) return buildEmptyResult(params)

    val results = searchFiles(files, params.regex, params.limit)
    return buildSearchResult(params, results, files.size)
  }

  private fun buildEmptyResult(params: SearchParams): SkillResult {
    return makeSuccess(
      linkedMapOf(
        "pattern" to params.pattern,
        "search_path" to params.resolvedPath.toString(),
        "total_matches" to 0,
        "matches" to emptyList<Map<String, Any>>(),
        "files_searched" to 0
      )
    )
  }

  private fun buildSearchResult(
    params: SearchParams, results: List<Map<String, Any>>, filesSearched: Int
  ): SkillResult {
    val limitApplied = results.size >= params.limit
    return makeSuccess(
      linkedMapOf(
        "pattern" to params.pattern,
        "search_path" to params.resolvedPath.toString(),
        "total_matches" to results.size,
        "matches" to results,
        "files_searched" to filesSearched,
        "limit_applied" to limitApplied
      )
    )
  }

  private fun buildIncludeMatcher(includeFilter: String): PathMatcher? {
    if (includeFilter.isBlank()) return null
    return try {
      FileSystems.getDefault().getPathMatcher("glob:$includeFilter")
    } catch (_: Exception) {
      // If the user's include glob is malformed, fall back to a
      // permissive filter (match every file) rather than silently
      // returning no files. The LLM will still get the matches from
      // any unfiltered files in the tree.
      FileSystems.getDefault().getPathMatcher("glob:**")
    }
  }

  private fun collectFiles(root: File, includeMatcher: PathMatcher?): List<File> {
    val result = mutableListOf<File>()
    walkFiltered(root) { file ->
      if (includeMatcher == null || includeMatcher.matches(file.toPath().fileName))
        result.add(file)
    }
    return result
  }

  private fun searchFiles(
    files: List<File>, regex: Regex, limit: Int
  ): List<Map<String, Any>> {
    val matches = ConcurrentLinkedQueue<Map<String, Any>>()
    val matchCount = AtomicInteger(0)

    runBlocking {
      val semaphore = Semaphore(MAX_CONCURRENCY)
      coroutineScope {
        for (file in files) {
          if (matchCount.get() >= limit) break
          launch(Dispatchers.IO) {
            semaphore.withPermit {
              if (matchCount.get() >= limit) return@withPermit
              searchFile(file, regex, limit, matchCount, matches)
            }
          }
        }
      }
    }

    return matches.toList().take(limit)
  }

  private fun searchFile(
    file: File, regex: Regex, limit: Int,
    matchCount: AtomicInteger, results: ConcurrentLinkedQueue<Map<String, Any>>
  ) {
    try {
      file.bufferedReader(Charsets.UTF_8).use { reader ->
        var lineNumber = 0
        reader.lineSequence().forEach { line ->
          lineNumber++
          if (matchCount.get() >= limit) return
          if (regex.containsMatchIn(line)) {
            if (matchCount.incrementAndGet() <= limit) {
              results.add(
                linkedMapOf(
                  "file" to file.absolutePath,
                  "line" to lineNumber,
                  "content" to line
                )
              )
            }
          }
        }
      }
    } catch (_: Exception) {
      // Skip unreadable files
    }
  }
}

@Suppress("unused")
class GlobSkill : Skill() {

  override val skillName: String = "glob"
  override val alias: String = "Glob"
  override val description: String = "Find files matching a glob pattern. Returns a list of matching file paths."

  override val allowedToolModes: Set<ToolMode> =
    setOf(ToolMode.AGENT, ToolMode.EDIT, ToolMode.READ_ONLY)

  /**
   * Keep the last 3 glob results' full `files` list in history.
   * Older results have `files` stripped by
   * [gradum.skill.Skill.compactHistory]'s default impl. The
   * LLM can re-run the glob to recover the path list. Current
   * call always returns in full.
   */
  override val historyKeepCount: Int = 3

  override val historyVolatileKeys: List<String> = listOf("files")

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimpleSchema =
      context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE

    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to if (useSimpleSchema) localDescription() else description,
        "parameters" to mapOf(
          "type" to "object",
          "properties" to if (useSimpleSchema) localProperties() else cloudProperties(),
          "required" to listOf("pattern")
        )
      )
    )
  }

  private fun localDescription(): String =
    "Find files matching a glob pattern. Returns matching file paths."

  private fun localProperties(): Map<String, Any> = mapOf(
    "pattern" to mapOf(
      "type" to "string",
      "description" to "Glob pattern, e.g. '*.kt', '**/*.test.*', 'src/**'."
    ),
    "path" to mapOf(
      "type" to "string",
      "description" to "Directory to search in. Relative to project root. Defaults to project root."
    )
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "pattern" to mapOf(
      "type" to "string",
      "description" to "Glob pattern to match files, e.g. '*.kt', '**/*.test.*', 'src/**/*.{kt,java}'."
    ),
    "path" to mapOf(
      "type" to "string",
      "description" to "Directory path to search in. Relative to project root. Defaults to project root."
    ),
    "limit" to mapOf(
      "type" to "integer",
      "description" to "Maximum number of files to return. Defaults to 500.",
      "minimum" to 1,
      "maximum" to GLOB_MAX_LIMIT
    )
  )

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val useSimpleSchema = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    return if (useSimpleSchema) executeLocal(arguments, context)
    else executeCloud(arguments, context)
  }

  private fun prepareGlobSearch(
    arguments: Map<String, Any>, context: SkillContext
  ): Either<GlobParams, SkillResult> {
    val patternStr = (arguments["pattern"] as? String)?.trim() ?: ""
    if (patternStr.isBlank()) {
      return Either.Failure(
        makeFailure(
          ErrorCode.INVALID_PARAMETER,
          buildXmlError(
            code = "INVALID_PARAMETER",
            message = "Pattern must not be empty.",
            fixHint = "Provide a glob pattern, e.g. '*.kt' or '**/*.test.*'."
          )
        )
      )
    }

    // Java's built-in `glob:` PathMatcher handles the corner cases
    // (`**` matching zero path segments, `*` not crossing '/', `?` not
    // crossing '/', literal regex metacharacters in filenames, brace
    // alternation, character classes) without us having to translate
    // glob to regex by hand. The previous hand-rolled translator
    // routed user input through `Regex.escape` (which is `Pattern.quote`
    // under the hood) and turned every glob into a literal substring
    // match, so Glob returned zero files regardless of the pattern.
    val globMatcher = try {
      FileSystems.getDefault().getPathMatcher("glob:$patternStr")
    } catch (_: Exception) {
      null
    } ?: return Either.Failure(
      makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Invalid glob pattern: $patternStr",
          fixHint = "Use standard glob syntax: '*' matches any chars, '**' matches path segments, '?' matches one char."
        )
      )
    )

    // Java's PathMatcher only honours `**` when at least one directory
    // segment follows it, so a pattern like `**/*.kt` never matches
    // `gradum_kotlin.kt` at the project root. Strip a leading `**/` and
    // try the suffix as a second matcher so that root-level files are
    // reachable too.
    val fallbackMatcher = if (patternStr.startsWith("**/")) {
      val stripped = patternStr.removePrefix("**/")
      try {
        FileSystems.getDefault().getPathMatcher("glob:$stripped")
      } catch (_: Exception) {
        null
      }
    } else null

    val pathResult = resolveAndValidatePath(context, arguments["path"] as? String)
    if (pathResult is Either.Failure) return pathResult
    val (rootFile, resolvedPath) = (pathResult as Either.Success).value

    val projectRoot = Paths.get(context.projectRoot).toAbsolutePath().normalize()

    val limit = when (val v = arguments["limit"]) {
      is Number -> v.toInt().coerceIn(1, GLOB_MAX_LIMIT)
      is String -> v.toIntOrNull()?.coerceIn(1, GLOB_MAX_LIMIT) ?: GLOB_MAX_RESULTS
      else -> GLOB_MAX_RESULTS
    }

    return Either.Success(
      GlobParams(
        pattern = patternStr,
        globMatcher = globMatcher,
        fallbackMatcher = fallbackMatcher,
        rootFile = rootFile,
        resolvedPath = resolvedPath,
        projectRoot = projectRoot,
        limit = limit
      )
    )
  }

  private fun executeLocal(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val params = when (val result = prepareGlobSearch(arguments, context)) {
      is Either.Success -> result.value
      is Either.Failure -> return result.error
    }

    val matchedFiles = globSearch(
      params.rootFile,
      params.projectRoot,
      params.globMatcher,
      params.fallbackMatcher,
      params.limit,
    )
    val limitApplied = matchedFiles.size >= params.limit

    return makeSuccess(
      linkedMapOf(
        "pattern" to params.pattern,
        "search_path" to params.resolvedPath.toString(),
        "total_files" to matchedFiles.size,
        "files" to matchedFiles,
        "limit_applied" to limitApplied
      )
    )
  }

  private fun executeCloud(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val params = when (val result = prepareGlobSearch(arguments, context)) {
      is Either.Success -> result.value
      is Either.Failure -> return result.error
    }

    val matchedFiles = globSearch(
      params.rootFile,
      params.projectRoot,
      params.globMatcher,
      params.fallbackMatcher,
      params.limit,
    )
    val limitApplied = matchedFiles.size >= params.limit

    return makeSuccess(
      linkedMapOf(
        "pattern" to params.pattern,
        "search_path" to params.resolvedPath.toString(),
        "total_files" to matchedFiles.size,
        "files" to matchedFiles,
        "limit_applied" to limitApplied
      )
    )
  }

  private fun globSearch(
    root: File,
    projectRoot: Path,
    matcher: PathMatcher,
    fallbackMatcher: PathMatcher?,
    limit: Int
  ): List<String> {
    val result = mutableListOf<String>()
    walkFiltered(root) { file ->
      if (result.size >= limit) return@walkFiltered
      val relativePath = projectRoot.relativize(file.toPath())
      val matches = matcher.matches(relativePath) ||
        (fallbackMatcher?.matches(relativePath) == true)
      if (matches) {
        result.add(relativePath.toString())
      }
    }
    return result
  }
}
