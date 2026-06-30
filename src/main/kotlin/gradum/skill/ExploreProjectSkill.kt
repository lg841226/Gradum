/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkill.kt  2026-06-28 22:15:18 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

private const val MINIMUM_DEPTH: Int = 1
private const val MAXIMUM_DEPTH: Int = 12
private const val DEFAULT_DEPTH: Int = 5
private const val MAXIMUM_CHILDREN_PER_DIRECTORY: Int = 2000

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
    // Go / PHP / Ruby (shared "vendor" kept as one entry — first match wins in the set)
    "vendor", ".bundle",
    // iOS / macOS
    "Pods", "DerivedData", ".build",
    // Misc build / cache
    ".terraform", ".dart_tool", ".serverless", ".expo", ".vercel",
    "coverage", ".nyc_output"
)

/**
 * Returns true when [name] is a directory we want visible to the LLM
 * (so it knows the directory exists) but do not want to recursively
 * expand — typically build artifacts, dependencies, and caches.
 *
 * Any dotfile directory (`.git`, `.idea`, `.venv`, …) is treated as
 * truncated as well; hidden top-level entries are configuration/tooling
 * state that almost never contains handwritten code worth expanding.
 */
private fun shouldTruncate(name: String): Boolean {
    if (name.startsWith(".")) return true
    if (name in truncatedDirectoryNames) return true
    if (name.endsWith(".egg-info")) return true
    if (name.endsWith(".iml")) return true
    return false
}

/**
 * Recursively builds the children list for [directory] up to [remainingDepth]
 * additional levels. Truncated directories are emitted as `{path, truncated: true}`
 * leaves; regular files are emitted as bare `{path}` objects; real directories
 * carry a nested `children` array (omitted when empty).
 *
 * Symlink loops are detected via [visited] (absolute, normalized paths) and
 * silently skipped so a single misconfigured symlink cannot wedge the scan.
 * Permission errors on individual directories are caught and the rest of the
 * listing is returned.
 */
private fun buildChildren(directory: Path, remainingDepth: Int, visited: Set<Path>): List<Map<String, Any>> {
    if (remainingDepth <= 0) return emptyList()

    val children: MutableList<Map<String, Any>> = mutableListOf()

    val entries: List<File> = try {
        directory.toFile().listFiles()?.toList() ?: emptyList()
    } catch (_: SecurityException) {
        return children
    }

    val sortedEntries: List<File> = entries
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    val limitedEntries: List<File> = if (sortedEntries.size > MAXIMUM_CHILDREN_PER_DIRECTORY) {
        sortedEntries.take(MAXIMUM_CHILDREN_PER_DIRECTORY)
    } else {
        sortedEntries
    }

    for (entry in limitedEntries) {
        if (entry.isDirectory)
            children.add(buildDirectoryNode(entry, remainingDepth, visited))
        else
            children.add(linkedMapOf("path" to entry.name))
    }

    return children
}

private fun buildDirectoryNode(entry: File, remainingDepth: Int, visited: Set<Path>): Map<String, Any> {
    if (shouldTruncate(entry.name))
        return linkedMapOf("path" to entry.name, "truncated" to true)

    val childPath: Path = entry.toPath()
    val normalizedChild: Path = childPath.toAbsolutePath().normalize()

    if (normalizedChild in visited)
        return linkedMapOf("path" to entry.name, "truncated" to true)

    val newVisited: Set<Path> = visited.plusElement(normalizedChild)
    val grandChildren: List<Map<String, Any>> =
        buildChildren(childPath, remainingDepth - 1, newVisited)

    val directoryNode: MutableMap<String, Any> = linkedMapOf("path" to entry.name)

    if (grandChildren.isNotEmpty())
        directoryNode["children"] = grandChildren

    return directoryNode
}

/**
 * Scans a project directory tree up to a configurable depth, returning a
 * hierarchical structure that lets the LLM understand project layout
 * without paying the cost of reading file contents.
 *
 * Build artifacts and dependency directories (.git, build, node_modules, …)
 * are emitted as truncated placeholders so the LLM can see they exist
 * while preventing a single scan from ballooning the conversation context.
 * Use [read_file][ReadFileSkill] on a specific path once the LLM has
 * located the file of interest.
 */
class ExploreProjectSkill : Skill() {

    override val skillName: String = "explore_project"
    override val alias: String = "Explored"
    override val description: String =
        "Scan directory tree. depth 1-$MAXIMUM_DEPTH (default $DEFAULT_DEPTH). Build/dependency dirs truncated."

    /**
     * Keep the full nested tree for the most recent 1 result. Older results are
     * collapsed to a flat list of top-level entry names so a session that
     * explores the project once at the start does not pay for the full tree
     * on every subsequent LLM call.
     */
    override val historyKeepCount: Int = 1

    override fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
        prepareHistoryCallCount++
        if (prepareHistoryCallCount <= historyKeepCount) return result

        val originalChildren: List<Map<String, Any>> = result["children"] as? List<Map<String, Any>> ?: return result
        val topLevelNames: List<String> = originalChildren.mapNotNull { child -> child["path"] as? String }
        val compactChildren: List<Map<String, Any>> = topLevelNames.map { name -> linkedMapOf("path" to name) }
        return result.toMutableMap().apply { this["children"] = compactChildren }
    }

    override fun getSchema(): Map<String, Any> = mapOf(
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
                                "depth=1 lists immediate children only.",
                        "minimum" to MINIMUM_DEPTH,
                        "maximum" to MAXIMUM_DEPTH,
                        "default" to DEFAULT_DEPTH,
                    ),
                ),
                "required" to emptyList<String>(),
            ),
        ),
    )

    override fun execute(arguments: Map<String, Any>): SkillResult {
        // The root to scan is ALWAYS the server-injected projectRoot (resolved
        // from --project-root at startup). The LLM has no way to influence it
        // because the schema no longer exposes any path/root parameter. Some
        // models still pass `project_root` out of habit / training memory —
        // we HARD-reject it so the model gets a clear error and stops wasting
        // turns, instead of silently ignoring and leaving the model confused.
        if (arguments.containsKey("project_root")) {
            return makeFailure(
                ErrorCode.INVALID_PARAMETER,
                "'project_root' is not a valid parameter for explore_project. " +
                    "The server determines the project root from its --project-root startup flag. " +
                    "Do not pass any path/root argument; just call explore_project with depth=N.",
            )
        }

        val projectRoot: String = arguments["projectRoot"] as? String ?: ""

        val depth: Int = when (val depthValue: Any? = arguments["depth"]) {
            is Number -> depthValue.toInt()
            is String -> depthValue.toIntOrNull() ?: DEFAULT_DEPTH
            else -> DEFAULT_DEPTH
        }

        if (projectRoot.isBlank())
            return makeFailure(
                ErrorCode.INVALID_PARAMETER,
                "Server has no project root configured (start the server with --project-root <path>).",
            )
        if (depth !in MINIMUM_DEPTH..MAXIMUM_DEPTH)
            return makeFailure(
                ErrorCode.INVALID_PARAMETER,
                "Invalid 'depth': $depth (allowed: $MINIMUM_DEPTH..$MAXIMUM_DEPTH)"
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

        val visited: Set<Path> = setOf(resolvedPath)
        val children: List<Map<String, Any>> = buildChildren(resolvedPath, depth, visited)

        return makeSuccess(
            mapOf(
                "project_root" to resolvedPath.toString(),
                "depth" to depth,
                "children" to children
            )
        )
    }
}
