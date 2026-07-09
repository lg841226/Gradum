/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkill.kt  2026-07-05 23:55:35 Changed by gwy
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
private const val MAXIMUM_DEPTH: Int = 12
private const val DEFAULT_DEPTH: Int = 8
private const val MAXIMUM_CHILDREN_PER_DIRECTORY: Int = 2048
private const val DEFAULT_LIMIT: Int = 500
private const val MAXIMUM_LIMIT: Int = 2000

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

    override val allowedToolModes: Set<ToolMode> = setOf(ToolMode.AGENT)

    override val historyKeepCount: Int = 3

    override fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
        prepareHistoryCallCount++
        if (prepareHistoryCallCount <= historyKeepCount) return result

        @Suppress("UNCHECKED_CAST")
        val configCount = (result["config_files"] as? List<*>)?.size ?: 0

        @Suppress("UNCHECKED_CAST")
        val codeCount = (result["code_files"] as? List<*>)?.size ?: 0

        @Suppress("UNCHECKED_CAST")
        val otherCount = (result["other_files"] as? List<*>)?.size ?: 0

        return linkedMapOf(
            "project_root" to (result["project_root"] ?: ""),
            "total_size" to (result["total_size"] ?: "0 B"),
            "config_files" to configCount,
            "code_files" to codeCount,
            "other_files" to otherCount
        )
    }

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
                    "required" to emptyList<String>(),
                ),
            ),
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
        "filter_type" to mapOf(
            "type" to "string",
            "description" to "Filter by file type: 'code', 'config', 'other', or 'all' (default='all')",
            "enum" to listOf("code", "config", "other", "all"),
        ),
        "filter_extension" to mapOf(
            "type" to "string",
            "description" to "Filter by file extensions, comma-separated (e.g., 'kt,java,py')",
        ),
        "filter_directory" to mapOf(
            "type" to "string",
            "description" to "Only scan these directories, comma-separated (e.g., 'src,test')",
        ),
        "exclude_pattern" to mapOf(
            "type" to "string",
            "description" to "Exclude files matching these glob patterns, comma-separated (e.g., '*.test.kt,**/test/**')",
        ),
        "min_lines" to mapOf(
            "type" to "integer",
            "description" to "Minimum line count (default=0)",
            "minimum" to 0,
        ),
        "max_lines" to mapOf(
            "type" to "integer",
            "description" to "Maximum line count (default=unlimited)",
            "minimum" to 1,
        ),
        "sort_by" to mapOf(
            "type" to "string",
            "description" to "Sort results by: 'name', 'lines', 'size' (default='name')",
            "enum" to listOf("name", "lines", "size"),
        ),
        "limit" to mapOf(
            "type" to "integer",
            "description" to "Maximum number of files to return (default=500, max=2000)",
            "minimum" to 1,
            "maximum" to 2000,
            "default" to 500,
        ),
    )

    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val projectRoot: String = context.projectRoot
        val useSimpleOutput = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE

        val requestedDepth: Int = when (val depthValue: Any? = arguments["depth"]) {
            is Number -> depthValue.toInt()
            is String -> depthValue.toIntOrNull() ?: DEFAULT_DEPTH
            else -> DEFAULT_DEPTH
        }.coerceIn(MINIMUM_DEPTH, MAXIMUM_DEPTH)

        // Parse filter parameters
        val filterType: String = (arguments["filter_type"] as? String ?: "all").lowercase()
        val filterExtension: String = (arguments["filter_extension"] as? String ?: "").lowercase()
        val filterDirectory: String = (arguments["filter_directory"] as? String ?: "").lowercase()
        val excludePattern: String = (arguments["exclude_pattern"] as? String ?: "").lowercase()
        val minLines: Int = when (val value: Any? = arguments["min_lines"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
        val maxLines: Int? = when (val value: Any? = arguments["max_lines"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.coerceAtLeast(1)
        val sortBy: String = (arguments["sort_by"] as? String ?: "name").lowercase()
        val limit: Int = when (val value: Any? = arguments["limit"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: DEFAULT_LIMIT
            else -> DEFAULT_LIMIT
        }.coerceIn(1, MAXIMUM_LIMIT)

        // Parse extension and directory filters into sets
        val extensionFilters: Set<String> = if (filterExtension.isBlank()) emptySet()
        else filterExtension.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val directoryFilters: Set<String> = if (filterDirectory.isBlank()) emptySet()
        else filterDirectory.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val excludePatterns: List<String> = if (excludePattern.isBlank()) emptyList()
        else excludePattern.split(",").map { it.trim() }.filter { it.isNotBlank() }

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
        } catch (_: Exception) {
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
            filterType = filterType,
            extensionFilters = extensionFilters,
            directoryFilters = directoryFilters,
            excludePatterns = excludePatterns,
            minLines = minLines,
            maxLines = maxLines,
            sortBy = sortBy,
            limit = limit
        )

        val scanResult = ScanResult(relativeRoot = resolvedPath)
        val visitedPaths: Set<Path> = setOf(resolvedPath)
        scanDirectory(resolvedPath, requestedDepth, visitedPaths, scanResult, filterConfig)

        // Apply filters and sorting
        val filteredConfigFiles = applyFilters(scanResult.configFiles, "config", filterConfig)
        val filteredCodeFiles = applyCodeFilters(scanResult.codeFiles, filterConfig)
        val filteredOtherFiles = applyFilters(scanResult.otherFiles, "other", filterConfig)

        if (useSimpleOutput) {
            val codeFileDetails: List<String> = filteredCodeFiles
                .sortedBy { (it["path"] as? String) ?: "" }
                .map { entry -> "${entry["path"]}:${entry["lines"]}" }

            return makeSuccess(
                linkedMapOf(
                    "project_root" to resolvedPath.toString(),
                    "total_size" to formatSize(scanResult.totalSize),
                    "config_files" to filteredConfigFiles.size,
                    "code_files" to filteredCodeFiles.size,
                    "other_files" to filteredOtherFiles.size,
                    "code_file_details" to codeFileDetails.take(limit),
                    "unreadable_paths" to scanResult.failedPaths.sortedBy { (it["path"] as? String) ?: "" },
                )
            )
        }

        return makeSuccess(
            linkedMapOf(
                "project_root" to resolvedPath.toString(),
                "total_size" to formatSize(scanResult.totalSize),
                "config_files" to filteredConfigFiles.sorted().take(limit),
                "code_files" to filteredCodeFiles.sortedBy { (it["path"] as? String) ?: "" }.take(limit),
                "other_files" to filteredOtherFiles.sorted().take(limit),
                "unreadable_paths" to scanResult.failedPaths.sortedBy { (it["path"] as? String) ?: "" }.take(limit),
                "filter_applied" to linkedMapOf(
                    "filter_type" to filterType,
                    "filter_extension" to filterExtension,
                    "filter_directory" to filterDirectory,
                    "exclude_pattern" to excludePattern,
                    "min_lines" to minLines,
                    "max_lines" to maxLines,
                    "sort_by" to sortBy,
                    "limit" to limit,
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
    "Pods", "DerivedData", ".build",
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
    } catch (_: Exception) {
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
    val filterType: String,
    val extensionFilters: Set<String>,
    val directoryFilters: Set<String>,
    val excludePatterns: List<String>,
    val minLines: Int,
    val maxLines: Int?,
    val sortBy: String,
    val limit: Int
)

private fun scanDirectory(
    targetDirectory: Path,
    remainingDepth: Int,
    visitedPaths: Set<Path>,
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
        } catch (_: IllegalArgumentException) {
            targetDirectory.toString()
        }
        scanResult.failedPaths.add(linkedMapOf("path" to relativePath, "reason" to reason))
        return
    }

    val sortedEntries: List<File> = directoryEntries
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    val limitedEntries: List<File> = if (sortedEntries.size > MAXIMUM_CHILDREN_PER_DIRECTORY) {
        sortedEntries.take(MAXIMUM_CHILDREN_PER_DIRECTORY)
    } else {
        sortedEntries
    }

    for (directoryEntry in limitedEntries) {
        if (directoryEntry.isDirectory) {
            if (shouldTruncate(directoryEntry.name)) continue

            // Apply directory filter
            if (filterConfig.directoryFilters.isNotEmpty()) {
                val dirPath = targetDirectory.relativize(directoryEntry.toPath()).toString()
                if (!filterConfig.directoryFilters.any { dirPath.startsWith(it) || it in dirPath }) {
                    continue
                }
            }

            val childPath: Path = directoryEntry.toPath()
            val normalizedChild: Path = childPath.toAbsolutePath().normalize()

            if (normalizedChild in visitedPaths) continue

            val updatedVisited: Set<Path> = visitedPaths.plusElement(normalizedChild)
            scanDirectory(childPath, remainingDepth - 1, updatedVisited, scanResult, filterConfig)
        } else {
            // Apply to exclude pattern filter
            if (filterConfig.excludePatterns.isNotEmpty()) {
                val relativePath = targetDirectory.relativize(directoryEntry.toPath()).toString()
                if (matchesExcludePattern(relativePath, filterConfig.excludePatterns)) {
                    continue
                }
            }

            scanResult.totalSize += directoryEntry.length()

            val relativePath = targetDirectory.relativize(directoryEntry.toPath()).toString()

            when {
                isConfigFile(directoryEntry.name) -> scanResult.configFiles.add(relativePath)
                isCodeFile(directoryEntry.name) -> {
                    val lineCount = countLines(directoryEntry)
                    scanResult.codeFiles.add(linkedMapOf("path" to relativePath, "lines" to (lineCount ?: "unknown")))
                }

                else -> scanResult.otherFiles.add(relativePath)
            }
        }
    }
}

private fun matchesExcludePattern(relativePath: String, patterns: List<String>): Boolean {
    for (pattern in patterns) {
        // Simple glob matching
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("**/", ".*/")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .replace("?", "[^/]")

        if (relativePath.matches(Regex("^$regexPattern$")) ||
            relativePath.contains(pattern.replace("**/", "").replace("**", ""))
        ) {
            return true
        }
    }
    return false
}

private fun applyFilters(files: List<String>, fileType: String, config: FilterConfig): List<String> {
    var filtered = files

    // Apply filter_type
    if (config.filterType != "all" && config.filterType != fileType) {
        return emptyList()
    }

    // Apply extension filter
    if (config.extensionFilters.isNotEmpty()) {
        filtered = filtered.filter { fileName ->
            val ext = fileName.substringAfterLast('.', "")
            ext in config.extensionFilters
        }
    }

    // Apply to exclude pattern
    if (config.excludePatterns.isNotEmpty()) {
        filtered = filtered.filter { !matchesExcludePattern(it, config.excludePatterns) }
    }

    return filtered
}

private fun applyCodeFilters(files: List<Map<String, Any>>, config: FilterConfig): List<Map<String, Any>> {
    var filtered = files

    // Apply filter_type
    if (config.filterType != "all" && config.filterType != "code") {
        return emptyList()
    }

    // Apply extension filter
    if (config.extensionFilters.isNotEmpty()) {
        filtered = filtered.filter { fileInfo ->
            val path = fileInfo["path"] as? String ?: ""
            val ext = path.substringAfterLast('.', "")
            ext in config.extensionFilters
        }
    }

    // Apply line count filter
    if (config.minLines > 0 || config.maxLines != null) {
        filtered = filtered.filter { fileInfo ->
            val lines = fileInfo["lines"] as? Int ?: 0
            lines >= config.minLines && (config.maxLines == null || lines <= config.maxLines)
        }
    }

    // Apply to exclude pattern
    if (config.excludePatterns.isNotEmpty()) {
        filtered = filtered.filter { fileInfo ->
            val path = fileInfo["path"] as? String ?: ""
            !matchesExcludePattern(path, config.excludePatterns)
        }
    }

    // Apply sorting
    filtered = when (config.sortBy) {
        "lines" -> filtered.sortedByDescending { (it["lines"] as? Int) ?: 0 }
        "size" -> filtered.sortedByDescending { (it["path"] as? String)?.length ?: 0 }
        else -> filtered.sortedBy { it["path"] as? String ?: "" }
    }

    return filtered
}
