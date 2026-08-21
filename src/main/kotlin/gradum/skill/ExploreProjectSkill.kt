/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkill.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.skill

import gradum.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("ExploreProjectSkill")

private const val MINIMUM_DEPTH: Int = 5
private const val MAXIMUM_DEPTH: Int = 14
private const val DEFAULT_DEPTH: Int = 8
private const val DEFAULT_LIMIT: Int = 500
private const val MAXIMUM_CHILDREN_PER_DIRECTORY: Int = 2048

/**
 * Scans a project directory tree and returns a flat categorized summary:
 * - config_files: project configuration files
 * - code_files: source code files with line counts
 * - other_files: miscellaneous files
 *
 * Build artifacts and dependency directories are skipped.
 * The first call returns detailed lists; subsequent calls return counts only.
 */
class ExploreProjectSkill : Skill() {
  override val alias: String = "Explored"
  override val skillName: String = "explore_project"
  override val description: String =
    "Scan project and categorize files. Returns config files, code files (with line counts), and other files."

  override val allowedToolModes: Set<ToolMode> = setOf(
    ToolMode.AGENT, ToolMode.READ_ONLY, ToolMode.EDIT
  )

  override val historyKeepCount: Int = 3

  /**
   * For OLDER explore_project calls, collapse the file lists down to
   * counts so a long session doesn't have to re-send thousands of
   * paths every time. The LLM's working set (the last
   * [historyKeepCount] calls) keeps the full lists because the model
   * may still refer back to them when planning a follow-up read.
   *
   * The current call's full lists are returned by [execute] as-is —
   * they are not collapsed here, since the LLM needs them in full
   * to decide what to read next.
   */
  override fun compactHistory(
    conversationHistory: MutableList<Map<String, Any>>,
    ownMessageIndices: List<Int>,
    callCount: Int
  ) {
    if (ownMessageIndices.isEmpty()) return
    val dropCount: Int = (ownMessageIndices.size + 1 - historyKeepCount).coerceAtLeast(0)
    val dropEndIndex: Int = dropCount.coerceAtMost(ownMessageIndices.size)
    if (dropEndIndex == 0) return
    for (i in 0 until dropEndIndex) {
      val historyIndex: Int = ownMessageIndices[i]
      if (historyIndex in conversationHistory.indices) {
        conversationHistory[historyIndex] =
          compactMessageContent(conversationHistory[historyIndex])
      }
    }
  }

  private fun compactMessageContent(message: Map<String, Any>): Map<String, Any> {
    val content: String = message["content"] as? String ?: return message
    val parsed: Map<String, Any?> = try {
      gradum.utils.JsonUtil.decodeMap(content)
    } catch (jsonParseException: Exception) {
      // Not a JSON object (plain string, error marker, etc.) — leave alone.
      logger.debug("Message content is not a JSON object, leaving as-is: ${jsonParseException.message}", jsonParseException)
      return message
    }

    @Suppress("UNCHECKED_CAST")
    val configCount: Int = (parsed["config_files"] as? List<*>)?.size ?: 0

    @Suppress("UNCHECKED_CAST")
    val codeCount: Int = (parsed["code_files"] as? List<*>)?.size ?: 0

    @Suppress("UNCHECKED_CAST")
    val otherCount: Int = (parsed["other_files"] as? List<*>)?.size ?: 0

    val compacted: Map<String, Any?> = linkedMapOf(
      "project_root" to (parsed["project_root"] ?: ""),
      "depth" to (parsed["depth"] ?: 0),
      "total_size" to (parsed["total_size"] ?: "0 B"),
      "config_files" to configCount,
      "code_files" to codeCount,
      "other_files" to otherCount
    )
    val reencoded: String = gradum.utils.JsonUtil.encodeMap(compacted)
    return message + ("content" to reencoded)
  }

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimpleSchema = context?.isSimpleModel == true

    return buildFunctionSchema(
      description = if (useSimpleSchema) localDescription() else description,
      properties = if (useSimpleSchema) localProperties() else cloudProperties(),
      required = emptyList(),
    )
  }

  private fun localDescription(): String =
    "Scan project directory tree and return file summary (config, code with line counts, other files)." +
      " Use depth to control recursion depth."

  private fun localProperties(): Map<String, Any> = mapOf(
    "depth" to mapOf(
      "type" to "integer",
      "description" to "Recursion depth ($MINIMUM_DEPTH..$MAXIMUM_DEPTH, default=$DEFAULT_DEPTH). " +
        "depth=$MINIMUM_DEPTH lists immediate children only.",
      "minimum" to MINIMUM_DEPTH,
      "maximum" to MAXIMUM_DEPTH,
      "default" to DEFAULT_DEPTH,
    ),
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "depth" to mapOf(
      "type" to "integer",
      "description" to "Recursion depth ($MINIMUM_DEPTH..$MAXIMUM_DEPTH, default=$DEFAULT_DEPTH). " +
        "depth=$MINIMUM_DEPTH lists immediate children only. Values below $MINIMUM_DEPTH are forced to $DEFAULT_DEPTH.",
      "minimum" to MINIMUM_DEPTH,
      "maximum" to MAXIMUM_DEPTH,
      "default" to DEFAULT_DEPTH,
    ),
    "exclude" to mapOf(
      "type" to "string",
      "description" to "Glob patterns to exclude, comma-separated (e.g. '*.test.*,**/node_modules/**')",
    ),
    "sort_by" to mapOf(
      "type" to "string",
      "description" to "Sort results by: 'name', 'lines', 'size' (default='name')",
      "enum" to listOf("name", "lines", "size"),
    ),
  )

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = context.isSimpleModel

    val requestedDepth: Int = when (val depthValue: Any? = arguments["depth"]) {
      is Number -> depthValue.toInt()
      is String -> depthValue.toIntOrNull() ?: DEFAULT_DEPTH
      else -> DEFAULT_DEPTH
    }.coerceIn(MINIMUM_DEPTH, MAXIMUM_DEPTH)

    val excludePattern: String = (arguments["exclude"] as? String
      ?: arguments["exclude_pattern"] as? String ?: "").lowercase()
    val sortBy: String = (arguments["sort_by"] as? String ?: "name").lowercase()
    val limit: Int = DEFAULT_LIMIT

    val excludePatterns: List<String> = if (excludePattern.isBlank()) emptyList()
    else excludePattern.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val excludeMatchers: List<ExcludeMatcher> = excludePatterns.map { compileExcludePattern(it) }

    if (projectRoot.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Server has no project root configured for this session.",
          fixHint = "Ensure the project root is set in the IDE settings or server configuration."
        ),
      )

    val resolvedPath: Path = try {
      Paths.get(projectRoot).toAbsolutePath().normalize()
    } catch (pathException: Exception) {
      logger.warn("Invalid project root path '$projectRoot': ${pathException.message}", pathException)
      return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Invalid project root path: $projectRoot",
          fixHint = "Check that the project root path is valid and accessible."
        )
      )
    }

    val rootFile: File = resolvedPath.toFile()
    if (!rootFile.exists())
      return makeFailure(
        ErrorCode.FILE_NOT_FOUND,
        buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "project_root does not exist: $resolvedPath",
          fixHint = "Verify the project directory exists. Use explore_project to find the correct path."
        ),
        mapOf("project_root" to resolvedPath.toString())
      )
    if (!rootFile.isDirectory)
      return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "project_root is not a directory: $resolvedPath",
          fixHint = "The path must point to a directory, not a file."
        ),
        mapOf("project_root" to resolvedPath.toString())
      )

    val filterConfig = FilterConfig(
      excludeMatchers = excludeMatchers,
      sortBy = sortBy,
      limit = limit
    )

    val scanResult = ScanResult(relativeRoot = resolvedPath)
    val visitedPaths: MutableSet<Path> = mutableSetOf(resolvedPath)
    scanDirectory(resolvedPath, requestedDepth, visitedPaths, scanResult, filterConfig)

    val filteredConfigFiles = applyFilters(scanResult.configFiles, filterConfig)
    val filteredCodeFiles = applyCodeFilters(scanResult.codeFiles, filterConfig)
    val filteredOtherFiles = applyFilters(scanResult.otherFiles, filterConfig)

    // Normalize config / other lists to {path, lines} shape so the three
    // file lists share a single shape with code_files. Clients then don't
    // need a per-list branch when deciding what to pass to read_file.
    val normalizedConfigFiles: List<Map<String, Any>> = filteredConfigFiles
      .sorted().take(filterConfig.limit)
      .map { filePath: String -> linkedMapOf<String, Any>("path" to filePath, "lines" to 0) }
    val normalizedOtherFiles: List<Map<String, Any>> = filteredOtherFiles
      .sorted().take(filterConfig.limit)
      .map { filePath: String -> linkedMapOf<String, Any>("path" to filePath, "lines" to 0) }

    return buildOutput(
      resolvedPath = resolvedPath,
      requestedDepth = requestedDepth,
      scanResult = scanResult,
      filteredConfigFiles = normalizedConfigFiles,
      filteredCodeFiles = filteredCodeFiles,
      filteredOtherFiles = normalizedOtherFiles,
      excludePattern = excludePattern,
      filterConfig = filterConfig,
      useSimpleOutput = useSimpleOutput,
    )
  }

  private fun buildOutput(
    resolvedPath: Path,
    requestedDepth: Int,
    scanResult: ScanResult,
    filteredConfigFiles: List<Map<String, Any>>,
    filteredCodeFiles: List<Map<String, Any>>,
    filteredOtherFiles: List<Map<String, Any>>,
    excludePattern: String,
    filterConfig: FilterConfig,
    useSimpleOutput: Boolean,
  ): SkillResult {
    if (useSimpleOutput) {
      val codeFileDetails: List<String> = filteredCodeFiles
        .sortedBy { (it["path"] as? String) ?: "" }
        .map { entry -> "${entry["path"]}:${entry["lines"]}" }

      return makeSuccess(
        linkedMapOf(
          "project_root" to resolvedPath.toString(),
          "depth" to requestedDepth,
          "total_size" to formatSize(scanResult.totalSize),
          "config_files" to filteredConfigFiles.size,
          "code_files" to filteredCodeFiles.size,
          "other_files" to filteredOtherFiles.size,
          "code_file_details" to codeFileDetails.take(filterConfig.limit),
          "unreadable_paths" to scanResult.failedPaths.sortedBy { (it["path"] as? String) ?: "" },
        )
      )
    }

    return makeSuccess(
      linkedMapOf(
        "project_root" to resolvedPath.toString(),
        "depth" to requestedDepth,
        "total_size" to formatSize(scanResult.totalSize),
        "config_files" to filteredConfigFiles,
        "code_files" to filteredCodeFiles.sortedBy { (it["path"] as? String) ?: "" }.take(filterConfig.limit),
        "other_files" to filteredOtherFiles,
        "unreadable_paths" to scanResult.failedPaths.sortedBy { (it["path"] as? String) ?: "" }.take(filterConfig.limit),
        "filter_applied" to linkedMapOf(
          "exclude_pattern" to excludePattern,
          "sort_by" to filterConfig.sortBy,
          "limit" to filterConfig.limit,
        ),
        "result_count" to linkedMapOf(
          "config_files" to filteredConfigFiles.size,
          "code_files" to filteredCodeFiles.size,
          "other_files" to filteredOtherFiles.size,
          "unreadable_paths" to scanResult.failedPaths.size,
        )
      )
    )
  }
}

private val truncatedDirectoryNames: Set<String> = setOf(
  // VCS
  ".git", ".svn", ".hg",
  // JVM / Gradle / Maven / IntelliJ
  ".gradle", "build", "out", ".idea", "target", "bin", "obj",
  ".kotlin", "cmake-build-debug", "cmake-build-release", "DerivedData",
  // Node / JS / TS
  "node_modules", ".next", ".nuxt", "dist", ".turbo", ".parcel-cache",
  // Python
  "__pycache__", ".venv", "venv", "env", ".eggs", ".pytest_cache",
  ".mypy_cache", ".ruff_cache", ".tox",
  // Go / PHP / Ruby
  "vendor", ".bundle",
  // iOS / macOS
  "Pods", ".build",
  // Misc build / cache
  ".terraform", ".dart_tool", ".serverless", ".expo", ".vercel",
  "coverage", ".nyc_output"
)

private val configFiles: Set<String> = setOf(
  // Build tools
  "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
  "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
  "gradle.properties", "gradlew", "gradlew.bat",
  "Cargo.toml", "Cargo.lock",
  "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", "poetry.lock",
  "CMakeLists.txt", "Makefile", "makefile",
  "go.mod", "go.sum",
  "Gemfile", "Gemfile.lock",
  "composer.json", "composer.lock",
  // Config files
  "tsconfig.json", "tsconfig.base.json",
  ".eslintrc", ".eslintrc.json", ".eslintrc.js", ".eslintrc.yml",
  ".prettierrc", ".prettierrc.json", ".prettierrc.js",
  "jest.config.js", "jest.config.ts", "vitest.config.ts",
  "webpack.config.js", "vite.config.ts", "vite.config.js",
  "tailwind.config.js", "tailwind.config.ts",
  "postcss.config.js",
  ".babelrc", "babel.config.js",
  "docker-compose.yml", "docker-compose.yaml", "docker-compose.override.yml",
  "Dockerfile", "Dockerfile.dev", "Dockerfile.prod",
  ".env", ".env.example", ".env.local", ".env.development", ".env.production",
  // VCS
  ".gitignore", ".gitattributes", ".gitmodules",
  // IDE
  "*.iml", ".editorconfig",
  // Misc
  "LICENSE", "LICENSE.txt", "LICENSE.md", "LICENCE", "LICENCE.txt",
  "CHANGELOG.md", "CHANGELOG.txt", "CHANGES.md",
  "CONTRIBUTING.md", "CONTRIBUTING.txt",
  ".DS_Store", "Thumbs.db"
)

private val codeExtensions: Set<String> = setOf(
  // JVM
  "kt", "kts", "java", "scala", "groovy",
  // JS / TS
  "js", "jsx", "ts", "tsx", "mjs", "cjs",
  // Python
  "py", "pyw",
  // Systems
  "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
  "rs", "go", "swift", "m", "mm",
  // Web
  "html", "htm", "css", "scss", "sass", "less", "vue", "svelte",
  // Shell
  "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
  // Data / Config (still code)
  "sql", "graphql", "gql", "proto", "thrift",
  "yaml", "yml", "toml", "xml", "json", "json5",
  "rb", "php", "pl", "pm", "r", "R", "lua", "dart", "ex", "exs", "erl",
  // Other
  "md", "txt", "rst", "adoc"
)

private fun shouldTruncate(entryName: String): Boolean {
  if (entryName.startsWith(".")) return true
  if (entryName in truncatedDirectoryNames) return true
  if (entryName.endsWith(".egg-info")) return true
  if (entryName.endsWith(".iml")) return true
  return false
}

private fun isConfigFile(entryName: String): Boolean {
  if (entryName in configFiles) return true
  if (entryName.endsWith(".iml")) return true
  if (entryName.startsWith(".env")) return true
  return false
}

private fun isCodeFile(entryName: String): Boolean {
  val fileExtension = entryName.substringAfterLast('.', "")
  return fileExtension in codeExtensions
}

private fun countLines(targetFile: File): Int {
  return try {
    targetFile.bufferedReader().use { bufferedReader ->
      bufferedReader.lines().count().toInt()
    }
  } catch (readException: Exception) {
    logger.debug("Failed to count lines in ${targetFile.path}: ${readException.message}", readException)
    0
  }
}

private fun formatSize(sizeInBytes: Long): String {
  return when {
    sizeInBytes < 1024 -> "$sizeInBytes B"
    sizeInBytes < 1024 * 1024 -> String.format("%.1f KB", sizeInBytes / 1024.0)
    sizeInBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", sizeInBytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", sizeInBytes / (1024.0 * 1024 * 1024))
  }
}

private data class ScanResult(
  val relativeRoot: Path,
  val configFiles: MutableList<String> = mutableListOf(),
  val codeFiles: MutableList<Map<String, Any>> = mutableListOf(),
  val otherFiles: MutableList<String> = mutableListOf(),
  val failedPaths: MutableList<Map<String, Any>> = mutableListOf(),
  var totalSize: Long = 0
)

private data class FilterConfig(
  val excludeMatchers: List<ExcludeMatcher>,
  val sortBy: String,
  val limit: Int
)

private data class ExcludeMatcher(
  val regex: Regex,
  val containsLiteral: String
)

private fun compileExcludePattern(pattern: String): ExcludeMatcher {
  val regexPattern = pattern
    .replace(".", "\\.")
    .replace("**/", ".*/")
    .replace("**", ".*")
    .replace("*", "[^/]*")
    .replace("?", "[^/]")
  return ExcludeMatcher(
    regex = Regex("^$regexPattern$"),
    containsLiteral = pattern.replace("**/", "").replace("**", "")
  )
}

private fun scanDirectory(
  targetDirectory: Path,
  remainingDepth: Int,
  visitedPaths: MutableSet<Path>,
  scanResult: ScanResult,
  filterConfig: FilterConfig
) {
  if (remainingDepth <= 0) return

  val directoryEntries: List<File> = try {
    targetDirectory.toFile().listFiles()?.toList() ?: emptyList()
  } catch (securityException: SecurityException) {
    val reason: String = securityException.message
      ?: securityException::class.simpleName
      ?: "access denied"
    logger.warn("SecurityException listing $targetDirectory: $reason", securityException)
    val relativePath: String = try {
      scanResult.relativeRoot.relativize(targetDirectory).toString()
    } catch (relativizeException: IllegalArgumentException) {
      logger.debug("Failed to relativize {}: {}", targetDirectory, relativizeException.message, relativizeException)
      targetDirectory.toString()
    }
    scanResult.failedPaths.add(linkedMapOf("path" to relativePath, "reason" to reason))
    return
  }

  val sortedEntries: List<File> = directoryEntries
    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

  val limitedEntries: List<File> = if (sortedEntries.size > MAXIMUM_CHILDREN_PER_DIRECTORY) {
    sortedEntries.take(MAXIMUM_CHILDREN_PER_DIRECTORY)
  } else sortedEntries

  for (directoryEntry in limitedEntries) {
    if (directoryEntry.isDirectory) {
      if (shouldTruncate(directoryEntry.name)) continue

      val childPath: Path = directoryEntry.toPath()
      val normalizedChild: Path = childPath.toAbsolutePath().normalize()

      if (!visitedPaths.add(normalizedChild)) continue

      scanDirectory(childPath, remainingDepth - 1, visitedPaths, scanResult, filterConfig)
    } else {
      if (filterConfig.excludeMatchers.isNotEmpty()) {
        val relativePath = scanResult.relativeRoot.relativize(directoryEntry.toPath()).toString()
        if (matchesExcludePattern(relativePath, filterConfig.excludeMatchers)) continue
      }

      scanResult.totalSize += directoryEntry.length()

      val relativePath: String = run {
        val candidate: String = try {
          scanResult.relativeRoot.relativize(directoryEntry.toPath()).toString()
        } catch (relativizeException: IllegalArgumentException) {
          logger.debug("Failed to relativize {} against root: {}", directoryEntry, relativizeException.message, relativizeException)
          targetDirectory.relativize(directoryEntry.toPath()).toString()
        }
        if (candidate.isNotBlank()) candidate else directoryEntry.name
      }

      when {
        isConfigFile(directoryEntry.name) -> scanResult.configFiles.add(relativePath)
        isCodeFile(directoryEntry.name) -> {
          val lineCount = countLines(directoryEntry)
          scanResult.codeFiles.add(linkedMapOf("path" to relativePath, "lines" to lineCount))
        }

        else -> scanResult.otherFiles.add(relativePath)
      }
    }
  }
}

private fun matchesExcludePattern(relativePath: String, matchers: List<ExcludeMatcher>): Boolean {
  for ((regex, containsLiteral) in matchers) {
    if (regex.matches(relativePath) ||
      relativePath.contains(containsLiteral)
    ) return true
  }
  return false
}

private fun applyFilters(files: List<String>, config: FilterConfig): List<String> {
  var filtered = files

  if (config.excludeMatchers.isNotEmpty())
    filtered = filtered.filter { !matchesExcludePattern(it, config.excludeMatchers) }

  return filtered
}

private fun applyCodeFilters(files: List<Map<String, Any>>, config: FilterConfig): List<Map<String, Any>> {
  var filtered = files

  if (config.excludeMatchers.isNotEmpty()) {
    filtered = filtered.filter { fileInfo ->
      val path = fileInfo["path"] as? String ?: ""
      !matchesExcludePattern(path, config.excludeMatchers)
    }
  }

  filtered = when (config.sortBy) {
    "lines" -> filtered.sortedByDescending { (it["lines"] as? Int) ?: 0 }
    "size" -> filtered.sortedByDescending { (it["path"] as? String)?.length ?: 0 }
    else -> filtered.sortedBy { it["path"] as? String ?: "" }
  }

  return filtered
}
