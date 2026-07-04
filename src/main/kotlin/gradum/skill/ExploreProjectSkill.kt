/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkill.kt  2026-07-03 20:03:42 Changed by gwy
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

private fun shouldTruncate(name: String): Boolean {
    if (name.startsWith(".")) return true
    if (name in truncatedDirectoryNames) return true
    if (name.endsWith(".egg-info")) return true
    if (name.endsWith(".iml")) return true
    return false
}

private fun isConfigFile(name: String): Boolean {
    if (name in configFiles) return true
    if (name.endsWith(".iml")) return true
    if (name.startsWith(".env")) return true
    return false
}

private fun isCodeFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "")
    return ext in codeExtensions
}

private fun countLines(file: File): Int {
    return try {
        file.bufferedReader().use { reader ->
            reader.lines().count().toInt()
        }
    } catch (_: Exception) {
        0
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

private data class ScanResult(
    val configFiles: MutableList<String> = mutableListOf(),
    val codeFiles: MutableList<Map<String, Any>> = mutableListOf(),
    val otherFiles: MutableList<String> = mutableListOf(),
    var totalSize: Long = 0
)

private fun scanDirectory(directory: Path, remainingDepth: Int, visited: Set<Path>, result: ScanResult) {
    if (remainingDepth <= 0) return

    val entries: List<File> = try {
        directory.toFile().listFiles()?.toList() ?: emptyList()
    } catch (_: SecurityException) {
        return
    }

    val sortedEntries: List<File> = entries
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    val limitedEntries: List<File> = if (sortedEntries.size > MAXIMUM_CHILDREN_PER_DIRECTORY) {
        sortedEntries.take(MAXIMUM_CHILDREN_PER_DIRECTORY)
    } else {
        sortedEntries
    }

    for (entry in limitedEntries) {
        if (entry.isDirectory) {
            if (shouldTruncate(entry.name)) continue

            val childPath: Path = entry.toPath()
            val normalizedChild: Path = childPath.toAbsolutePath().normalize()

            if (normalizedChild in visited) continue

            val newVisited: Set<Path> = visited.plusElement(normalizedChild)
            scanDirectory(childPath, remainingDepth - 1, newVisited, result)
        } else {
            result.totalSize += entry.length()

            val relativePath = directory.relativize(entry.toPath()).toString()

            when {
                isConfigFile(entry.name) -> result.configFiles.add(relativePath)
                isCodeFile(entry.name) -> {
                    val lines = countLines(entry)
                    result.codeFiles.add(linkedMapOf("path" to relativePath, "lines" to lines))
                }

                else -> result.otherFiles.add(relativePath)
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

    override val skillName: String = "explore_project"
    override val alias: String = "Explored"
    override val description: String =
        "Scan project and categorize files. Returns config files, code files (with line counts), and other files."

    override val allowedToolModes: Set<ToolMode> = setOf(
        ToolMode.AGENT,
        ToolMode.EDIT
    )

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

        val depth: Int = when (val depthValue: Any? = arguments["depth"]) {
            is Number -> depthValue.toInt()
            is String -> depthValue.toIntOrNull() ?: DEFAULT_DEPTH
            else -> DEFAULT_DEPTH
        }.coerceIn(MINIMUM_DEPTH, MAXIMUM_DEPTH)

        if (projectRoot.isBlank())
            return makeFailure(
                ErrorCode.INVALID_PARAMETER,
                "Server has no project root configured for this session.",
            )

        val resolvedPath: Path = try {
            Paths.get(projectRoot).toAbsolutePath().normalize()
        } catch (_: Exception) {
            return makeFailure(
                ErrorCode.INVALID_PARAMETER,
                "Invalid project root path: $projectRoot"
            )
        }

        val rootFile: File = resolvedPath.toFile()
        if (!rootFile.exists())
            return makeFailure(
                ErrorCode.FILE_NOT_FOUND,
                "project_root does not exist: $resolvedPath",
                mapOf("project_root" to resolvedPath.toString())
            )
        if (!rootFile.isDirectory)
            return makeFailure(
                ErrorCode.INVALID_PARAMETER,
                "project_root is not a directory: $resolvedPath",
                mapOf("project_root" to resolvedPath.toString())
            )

        val result = ScanResult()
        val visited: Set<Path> = setOf(resolvedPath)
        scanDirectory(resolvedPath, depth, visited, result)

        return makeSuccess(
            linkedMapOf(
                "project_root" to resolvedPath.toString(),
                "total_size" to formatSize(result.totalSize),
                "config_files" to result.configFiles.sorted(),
                "code_files" to result.codeFiles.sortedBy { (it["path"] as? String) ?: "" },
                "other_files" to result.otherFiles.sorted()
            )
        )
    }
}
