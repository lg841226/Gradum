package gradum.workspace

import gradum.skill.external.DeployResult
import gradum.skill.external.ExternalSkillHost
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

const val SKILLS_ROOT: String = "skills"
const val UI_ROOT: String = "ui"

data class WorkspaceRoot(val name: String, val directory: File)

/**
 * One tree over the registered roots. A path is always `root/relative/path`;
 * reads, writes, and deploys resolve that string against exactly one root.
 */
class Workspace(roots: List<WorkspaceRoot>) {
  private val roots: List<WorkspaceRoot> = roots.toList()
  private val rootDirectories: Map<String, File>

  init {
    require(this.roots.map { root -> root.name }.distinct().size == this.roots.size) {
      "Duplicate workspace root names"
    }
    rootDirectories = this.roots.associate { root ->
      require(ROOT_NAME_PATTERN.matches(root.name)) { "Invalid workspace root name: '${root.name}'" }
      root.name to root.directory
    }
  }

  fun tree(): List<Map<String, Any?>> = roots.map { root ->
    mapOf(
      "name" to root.name,
      "path" to root.name,
      "directory" to true,
      "children" to listChildren(root.directory, root.name),
    )
  }

  fun read(path: String): String? {
    val candidate: Path = resolve(path)
    return if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      Files.readString(candidate, Charsets.UTF_8)
    } else {
      null
    }
  }

  fun write(path: String, text: String) {
    val candidate: Path = resolve(path)
    require(!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
      "Workspace path is a directory: '$path'"
    }
    val parent: Path? = candidate.parent
    require(parent != null && Files.isDirectory(parent)) {
      "Parent directory does not exist for '$path'"
    }
    Files.writeString(candidate, text, Charsets.UTF_8)
  }

  fun deploy(path: String, text: String): DeployResult {
    val rootName: String = path.substringBefore('/')
    require(rootName == SKILLS_ROOT) { "Only the skills root supports deploy: '$path'" }
    resolve(path)
    val scanner = ExternalSkillHost.scanner
      ?: throw IllegalStateException("External skills directory is not initialized")
    return scanner.deploy(path.substringAfter('/'), text)
  }

  fun listFiles(rootName: String, suffix: String? = null): List<String> {
    val directory: File = rootDirectories[rootName]
      ?: throw IllegalArgumentException("Unknown workspace root: '$rootName'")
    val children: Array<File> = directory.listFiles() ?: return emptyList()
    return children.asSequence()
      .filter { child -> child.isFile && !Files.isSymbolicLink(child.toPath()) }
      .filter { child -> !child.name.startsWith(".") }
      .filter { child -> suffix == null || child.name.endsWith(suffix) }
      .map { child -> child.name }
      .sorted()
      .toList()
  }

  private fun listChildren(directory: File, pathPrefix: String): List<Map<String, Any?>> {
    val children: Array<File> = directory.listFiles() ?: return emptyList()
    return children.asSequence()
      .filter { child -> !child.name.startsWith(".") }
      .filter { child -> !Files.isSymbolicLink(child.toPath()) }
      .mapNotNull { child ->
        val childPath = "$pathPrefix/${child.name}"
        when {
          Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) -> mapOf(
            "name" to child.name,
            "path" to childPath,
            "directory" to true,
            "children" to listChildren(child, childPath),
          )

          child.isFile -> mapOf(
            "name" to child.name,
            "path" to childPath,
            "directory" to false,
          )

          else -> null
        }
      }
      .sortedWith(
        compareByDescending<Map<String, Any?>> { node -> node["directory"] == true }
          .thenBy { node -> node["name"] as String }
      )
      .toList()
  }

  private fun resolve(path: String): Path {
    val segments: List<String> = path.split('/')
    require(segments.size >= 2) { "Workspace path must be 'root/relative/path': '$path'" }
    require(
      segments.all { segment ->
        segment.isNotBlank() &&
          segment != "." &&
          segment != ".." &&
          segment.indexOf('\\') < 0 &&
          segment.indexOf('\u0000') < 0
      }
    ) { "Invalid workspace path: '$path'" }

    val rootName: String = segments.first()
    val directory: File = rootDirectories[rootName]
      ?: throw IllegalArgumentException("Unknown workspace root: '$rootName'")
    val rootReal: Path = directory.toPath().toRealPath()
    val candidate: Path = rootReal.resolve(segments.drop(1).joinToString(File.separator)).normalize()
    require(candidate.startsWith(rootReal)) { "Workspace path escapes root: '$path'" }

    var ancestor: Path? = candidate
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      ancestor = ancestor.parent
    }
    val realAncestor: Path = ancestor?.toRealPath() ?: rootReal
    require(realAncestor.startsWith(rootReal)) { "Workspace path escapes root through a link: '$path'" }
    return candidate
  }
}

private val ROOT_NAME_PATTERN: Regex = Regex("[A-Za-z0-9_-]+")

object WorkspaceHost {
  @Volatile
  var workspace: Workspace? = null
}
