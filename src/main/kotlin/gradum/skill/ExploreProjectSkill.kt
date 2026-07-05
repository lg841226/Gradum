/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkill.kt  2026-07-04 21:43:23 Changed by gwy
 */

package gradum.skill

import gradum.*
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

private const val MINIMUM_DEPTH: Int = 5
private const val MAXIMUM_DEPTH: Int = 12
private const val DEFAULT_DEPTH: Int = 8
private const val MAXIMUM_CHILDREN_PER_DIRECTORY: Int = 2048

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
    val configFiles: MutableList<String> = mutableListOf(),
    val codeFiles: MutableList<Map<String, Any>> = mutableListOf(),
    val otherFiles: MutableList<String> = mutableListOf(),
    var totalSize: Long = 0
)

private fun scanDirectory(targetDirectory: Path, remainingDepth: Int, visitedPaths: Set<Path>, scanResult: ScanResult) {
    if (remainingDepth <= 0) return

    val directoryEntries: List<File> = try {
        targetDirectory.toFile().listFiles()?.toList() ?: emptyList()
    } catch (_: SecurityException) {
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

            val childPath: Path = directoryEntry.toPath()
            val normalizedChild: Path = childPath.toAbsolutePath().normalize()

            if (normalizedChild in visitedPaths) continue

            val updatedVisited: Set<Path> = visitedPaths.plusElement(normalizedChild)
            scanDirectory(childPath, remainingDepth - 1, updatedVisited, scanResult)
        } else {
            scanResult.totalSize += directoryEntry.length()

            val relativePath = targetDirectory.relativize(directoryEntry.toPath()).toString()

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

    override fun getSchema(context: SkillContext?): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to skillName,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "depth" to mapOf(
                        "type" to "integer",
                        "description" to "Recursion depth ($MINIMUM_DEPTH..$MAXIMUM_DEPTH, default=$DEFAULT_DEPTH). " +
                            "depth=$MINIMUM_DEPTH lists immediate children only. Values below $MINIMUM_DEPTH are forced to $DEFAULT_DEPTH.",
                        "minimum" to MINIMUM_DEPTH,
                        "maximum" to MAXIMUM_DEPTH,
                        "default" to DEFAULT_DEPTH,
                    ),
                ),
                "required" to emptyList<String>(),
            ),
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

        val scanResult = ScanResult()
        val visitedPaths: Set<Path> = setOf(resolvedPath)
        scanDirectory(resolvedPath, requestedDepth, visitedPaths, scanResult)

        if (useSimpleOutput) {
            val codeFileDetails: List<String> = scanResult.codeFiles
                .sortedBy { (it["path"] as? String) ?: "" }
                .map { "${it["path"]}:${it["lines"]}" }

            return makeSuccess(
                linkedMapOf(
                    "project_root" to resolvedPath.toString(),
                    "total_size" to formatSize(scanResult.totalSize),
                    "config_files" to scanResult.configFiles.size,
                    "code_files" to scanResult.codeFiles.size,
                    "other_files" to scanResult.otherFiles.size,
                    "code_file_details" to codeFileDetails,
                )
            )
        }

        return makeSuccess(
            linkedMapOf(
                "project_root" to resolvedPath.toString(),
                "total_size" to formatSize(scanResult.totalSize),
                "config_files" to scanResult.configFiles.sorted(),
                "code_files" to scanResult.codeFiles.sortedBy { (it["path"] as? String) ?: "" },
                "other_files" to scanResult.otherFiles.sorted()
            )
        )
    }
}
