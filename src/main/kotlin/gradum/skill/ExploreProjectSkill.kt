/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkill.kt  2026-08-25 16:41:02 Changed by gwy
 */

package gradum.skill

import gradum.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("ExploreProjectSkill")

private val MINIMUM_DEPTH: Int = GradumConfig.EXPLORE_MIN_DEPTH
private val MAXIMUM_DEPTH: Int = GradumConfig.EXPLORE_MAX_DEPTH
private val DEFAULT_DEPTH: Int = GradumConfig.EXPLORE_DEFAULT_DEPTH
private val DEFAULT_LIMIT: Int = GradumConfig.EXPLORE_DEFAULT_LIMIT
private val MAXIMUM_CHILDREN_PER_DIRECTORY: Int = GradumConfig.EXPLORE_MAX_CHILDREN

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

  override val simpleDescription: String =
    "Scan project directory tree and return file summary (config, code with line counts, other files)." +
      " Use depth to control recursion depth."

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    integer(
      name = "depth",
      description = "Recursion depth ($MINIMUM_DEPTH..$MAXIMUM_DEPTH, default=$DEFAULT_DEPTH). " +
        "depth=$MINIMUM_DEPTH lists immediate children only. Values below $MINIMUM_DEPTH are forced to $DEFAULT_DEPTH.",
      constraints = IntConstraints(default = DEFAULT_DEPTH, minimum = MINIMUM_DEPTH, maximum = MAXIMUM_DEPTH),
    )
    cloudOnly {
      string(
        name = "exclude",
        description = "Glob patterns to exclude, comma-separated (e.g. '*.test.*,**/node_modules/**')",
      )
      string(
        name = "sort_by",
        description = "Sort results by: 'name', 'lines', 'size' (default='name')",
        enumValues = listOf("name", "lines", "size"),
      )
    }
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = context.isSimpleModel

    val requestedDepth: Int = when (val depthValue: Any? = arguments["depth"]) {
      is Number -> depthValue.toInt()
      is String -> depthValue.toIntOrNull() ?: DEFAULT_DEPTH
      else -> DEFAULT_DEPTH
    }.coerceIn(MINIMUM_DEPTH, MAXIMUM_DEPTH)

    val excludePattern: String = (
      arguments["exclude"] as? String ?: arguments["exclude_pattern"] as? String ?: ""
      ).lowercase()

    val sortBy: String = (arguments["sort_by"] as? String ?: "name").lowercase()
    val limit: Int = DEFAULT_LIMIT

    val excludePatterns: List<String> =
      if (excludePattern.isBlank()) emptyList()
      else excludePattern.split(",").map {
        it.trim()
      }.filter { it.isNotBlank() }
    val excludeMatchers: List<ExcludeMatcher> = excludePatterns.map {
      compileExcludePattern(it)
    }

    if (projectRoot.isBlank())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
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
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Invalid project root path: $projectRoot",
          fixHint = "Check that the project root path is valid and accessible."
        )
      )
    }

    val rootFile: File = resolvedPath.toFile()
    if (!rootFile.exists())
      return makeFailure(
        code = ErrorCode.FILE_NOT_FOUND,
        message = buildXmlError(
          code = "FILE_NOT_FOUND",
          message = "project_root does not exist: $resolvedPath",
          fixHint = "Verify the project directory exists. Use explore_project to find the correct path."
        ),
        context = mapOf("project_root" to resolvedPath.toString())
      )
    if (!rootFile.isDirectory)
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "project_root is not a directory: $resolvedPath",
          fixHint = "The path must point to a directory, not a file."
        ),
        context = mapOf("project_root" to resolvedPath.toString())
      )

    val filterConfig = FilterConfig(
      limit = limit,
      sortBy = sortBy,
      excludeMatchers = excludeMatchers
    )

    val scanResult = ScanResult(relativeRoot = resolvedPath)
    val visitedPaths: MutableSet<Path> = mutableSetOf(resolvedPath)
    scanDirectory(requestedDepth, resolvedPath, scanResult, filterConfig, visitedPaths)

    val filteredConfigFiles = applyFilters(scanResult.configFiles, filterConfig)
    val filteredCodeFiles = applyCodeFilters(scanResult.codeFiles, filterConfig)
    val filteredOtherFiles = applyFilters(scanResult.otherFiles, filterConfig)

    val normalizedConfigFiles: List<Map<String, Any>> = filteredConfigFiles
      .sorted().take(n = filterConfig.limit)
      .map { filePath: String -> linkedMapOf<String, Any>("path" to filePath, "lines" to 0) }
    val normalizedOtherFiles: List<Map<String, Any>> = filteredOtherFiles
      .sorted().take(n = filterConfig.limit)
      .map { filePath: String -> linkedMapOf<String, Any>("path" to filePath, "lines" to 0) }

    return buildOutput(
      scanResult = scanResult,
      resolvedPath = resolvedPath,
      filterConfig = filterConfig,
      excludePattern = excludePattern,
      requestedDepth = requestedDepth,
      useSimpleOutput = useSimpleOutput,
      filteredCodeFiles = filteredCodeFiles,
      filteredOtherFiles = normalizedOtherFiles,
      filteredConfigFiles = normalizedConfigFiles
    )
  }

  private fun buildOutput(
    resolvedPath: Path,
    requestedDepth: Int,
    scanResult: ScanResult,
    excludePattern: String,
    useSimpleOutput: Boolean,
    filterConfig: FilterConfig,
    filteredCodeFiles: List<Map<String, Any>>,
    filteredOtherFiles: List<Map<String, Any>>,
    filteredConfigFiles: List<Map<String, Any>>,
  ): SkillResult {
    if (useSimpleOutput) {
      val codeFileDetails: List<String> = filteredCodeFiles
        .sortedBy { (it["path"] as? String) ?: "" }
        .map { entry -> "${entry["path"]}:${entry["lines"]}" }

      return makeSuccess(
        data = linkedMapOf(
          "depth" to requestedDepth,
          "code_files" to filteredCodeFiles.size,
          "other_files" to filteredOtherFiles.size,
          "project_root" to resolvedPath.toString(),
          "config_files" to filteredConfigFiles.size,
          "total_size" to formatSize(sizeInBytes = scanResult.totalSize),
          "code_file_details" to codeFileDetails.take(n = filterConfig.limit),
          "unreadable_paths" to scanResult.failedPaths.sortedBy {
            (it["path"] as? String) ?: ""
          },
        )
      )
    }

    return makeSuccess(
      data = linkedMapOf(
        "depth" to requestedDepth,
        "project_root" to resolvedPath.toString(),
        "total_size" to formatSize(sizeInBytes = scanResult.totalSize),
        "config_files" to filteredConfigFiles,
        "code_files" to filteredCodeFiles.sortedBy {
          (it["path"] as? String) ?: ""
        }.take(n = filterConfig.limit),
        "other_files" to filteredOtherFiles,
        "unreadable_paths" to scanResult.failedPaths.sortedBy {
          (it["path"] as? String) ?: ""
        }.take(n = filterConfig.limit),
        "filter_applied" to linkedMapOf(
          "limit" to filterConfig.limit,
          "sort_by" to filterConfig.sortBy,
          "exclude_pattern" to excludePattern,
        ),
        "result_count" to linkedMapOf(
          "code_files" to filteredCodeFiles.size,
          "other_files" to filteredOtherFiles.size,
          "config_files" to filteredConfigFiles.size,
          "unreadable_paths" to scanResult.failedPaths.size,
        )
      )
    )
  }
}

private val truncatedDirectoryNames: Set<String> = setOf(
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

private val configFiles: Set<String> = setOf(
  "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
  "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
  "gradle.properties", "gradlew", "gradlew.bat",
  "Cargo.toml", "Cargo.lock",
  "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", "poetry.lock",
  "CMakeLists.txt", "Makefile", "makefile",
  "go.mod", "go.sum",
  "Gemfile", "Gemfile.lock",
  "composer.json", "composer.lock",
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
  ".gitignore", ".gitattributes", ".gitmodules",
  "*.iml", ".editorconfig",
  "LICENSE", "LICENSE.txt", "LICENSE.md", "LICENCE", "LICENCE.txt",
  "CHANGELOG.md", "CHANGELOG.txt", "CHANGES.md",
  "CONTRIBUTING.md", "CONTRIBUTING.txt",
  ".DS_Store", "Thumbs.db"
)

private val codeExtensions: Set<String> = setOf(
  "kt", "kts", "java", "scala", "groovy",
  "js", "jsx", "ts", "tsx", "mjs", "cjs",
  "py", "pyw",
  "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
  "rs", "go", "swift", "m", "mm",
  "html", "htm", "css", "scss", "sass", "less", "vue", "svelte",
  "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
  "sql", "graphql", "gql", "proto", "thrift",
  "yaml", "yml", "toml", "xml", "json", "json5",
  "rb", "php", "pl", "pm", "r", "R", "lua", "dart", "ex", "exs", "erl",
  "md", "txt", "rst", "adoc"
)

private fun shouldTruncate(entryName: String): Boolean {
  if (entryName.startsWith(prefix = ".")) return true
  if (entryName.endsWith(suffix = ".iml")) return true
  if (entryName in truncatedDirectoryNames) return true
  if (entryName.endsWith(suffix = ".egg-info")) return true
  return false
}

private fun isConfigFile(entryName: String): Boolean {
  if (entryName in configFiles) return true
  if (entryName.endsWith(suffix = ".iml")) return true
  if (entryName.startsWith(prefix = ".env")) return true
  return false
}

private fun isCodeFile(entryName: String): Boolean {
  val fileExtension = entryName.substringAfterLast(delimiter = '.', missingDelimiterValue = "")
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
  var totalSize: Long = 0,
  val otherFiles: MutableList<String> = mutableListOf(),
  val configFiles: MutableList<String> = mutableListOf(),
  val codeFiles: MutableList<Map<String, Any>> = mutableListOf(),
  val failedPaths: MutableList<Map<String, Any>> = mutableListOf()
)

private data class FilterConfig(
  val limit: Int,
  val sortBy: String,
  val excludeMatchers: List<ExcludeMatcher>
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
    regex = Regex(pattern = "^$regexPattern$"),
    containsLiteral = pattern.replace("**/", "").replace("**", "")
  )
}

private fun scanDirectory(
  remainingDepth: Int,
  targetDirectory: Path,
  scanResult: ScanResult,
  filterConfig: FilterConfig,
  visitedPaths: MutableSet<Path>
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
    .sortedWith(comparator = compareBy({ !it.isDirectory }, { it.name.lowercase() }))

  val limitedEntries: List<File> = if (sortedEntries.size > MAXIMUM_CHILDREN_PER_DIRECTORY) {
    sortedEntries.take(n = MAXIMUM_CHILDREN_PER_DIRECTORY)
  } else sortedEntries

  for (directoryEntry in limitedEntries) {
    if (directoryEntry.isDirectory) {
      if (shouldTruncate(entryName = directoryEntry.name)) continue

      val childPath: Path = directoryEntry.toPath()
      val normalizedChild: Path = childPath.toAbsolutePath().normalize()

      if (!visitedPaths.add(normalizedChild)) continue

      scanDirectory(remainingDepth - 1, targetDirectory = childPath, scanResult, filterConfig, visitedPaths)
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
          logger.debug(
            "Failed to relativize {} against root: {}",
            directoryEntry, relativizeException.message, relativizeException
          )
          targetDirectory.relativize(directoryEntry.toPath()).toString()
        }
        candidate.ifBlank { directoryEntry.name }
      }

      when {
        isConfigFile(entryName = directoryEntry.name) -> scanResult.configFiles.add(relativePath)
        isCodeFile(entryName = directoryEntry.name) -> {
          val lineCount = countLines(targetFile = directoryEntry)
          scanResult.codeFiles.add(linkedMapOf("path" to relativePath, "lines" to lineCount))
        }

        else -> scanResult.otherFiles.add(relativePath)
      }
    }
  }
}

private fun matchesExcludePattern(relativePath: String, matchers: List<ExcludeMatcher>): Boolean {
  for ((regex, containsLiteral) in matchers) {
    if (regex.matches(input = relativePath) ||
      relativePath.contains(containsLiteral)
    ) return true
  }
  return false
}

private fun applyFilters(files: List<String>, config: FilterConfig): List<String> {
  var filtered = files

  if (config.excludeMatchers.isNotEmpty())
    filtered = filtered.filter {
      !matchesExcludePattern(relativePath = it, config.excludeMatchers)
    }

  return filtered
}

private fun applyCodeFilters(files: List<Map<String, Any>>, config: FilterConfig): List<Map<String, Any>> {
  var filtered = files

  if (config.excludeMatchers.isNotEmpty()) {
    filtered = filtered.filter { fileInfo ->
      val path = fileInfo["path"] as? String ?: ""
      !matchesExcludePattern(relativePath = path, config.excludeMatchers)
    }
  }

  filtered = when (config.sortBy) {
    "lines" -> filtered.sortedByDescending { (it["lines"] as? Int) ?: 0 }
    "size" -> filtered.sortedByDescending { (it["path"] as? String)?.length ?: 0 }
    else -> filtered.sortedBy { it["path"] as? String ?: "" }
  }

  return filtered
}
