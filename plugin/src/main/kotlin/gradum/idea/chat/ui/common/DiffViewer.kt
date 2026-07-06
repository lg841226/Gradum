/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DiffViewer.kt  2026-07-01 21:53:11 Changed by gwy
 */

package gradum.idea.chat.ui.common

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.bundle.GradumBundle
import java.io.File

/**
 * Thin facade over the IntelliJ Platform's native diff viewer. Used by
 * the chat UI to expose a "View Diff" action on a successful
 * `edit_file` tool call, so the user can inspect exactly what the LLM
 * changed before trusting the result.
 *
 * The platform API is intentionally accessed through a narrow surface
 * — `DiffManager.showDiff` + `DiffContentFactory.create` +
 * `SimpleDiffRequest` + `DiffDialogHints.MODAL` — because those types
 * have been the stable contract since 2017 (2024.3 / 2025.x / 2026.x
 * all keep the same signature). Newer diff APIs
 * (e.g. `DiffEditorTabTitleProvider`, `DiffRequestFactory` for merge
 * tools) are deliberately avoided here because they are convenience
 * wrappers, not replacements.
 *
 * Side resolution falls back through three tiers, best to worst:
 *  1. [VirtualFile] from `LocalFileSystem.findFileByPath(path)` or
 *     the path joined with `project.basePath` — gives the diff panel
 *     the real file type, full syntax highlighting, gutter icons,
 *     and navigation. The `edit_file` tool emits project-relative
 *     paths to the plugin, and `findFileByPath` does NOT resolve
 *     those itself, which is why the second lookup exists.
 *  2. [FileType] from `FileTypeRegistry.getFileTypeByFileName(name)`
 *     when the file is not yet on disk or lives outside the VFS —
 *     still produces correct syntax highlighting for known
 *     extensions (`.kt` → KotlinFileType, `.java` → JavaFileType,
 *     etc.) but loses the live editor integration.
 *  3. [PlainTextFileType.INSTANCE] as the last resort — no syntax
 *     highlighting, used only for extensions the platform cannot
 *     recognize.
 */
object DiffViewer {
  private val log: Logger = Logger.getInstance(DiffViewer::class.java)

  /**
   * Open the platform diff viewer as a modal dialog showing the diff
   * between [originalContent] and [modifiedContent] in the context
   * of the file at [path]. The path is used for the display title
   * and for resolving a file type — no file is read or written.
   *
   * The `project` may be null when invoked from a context without a
   * project (e.g. a unit test that drives the chat UI standalone);
   * the platform accepts null and falls back to the default
   * non-project window.
   */
  fun showFileDiff(
    project: Project?,
    path: String,
    originalContent: String,
    modifiedContent: String,
  ) {
    log.info(
      "showFileDiff invoked: project=${project?.name ?: "<null>"}, " +
        "path='$path', original=${originalContent.length} chars, " +
        "modified=${modifiedContent.length} chars"
    )

    try {
      val contentFactory: DiffContentFactory = DiffContentFactory.getInstance()
      val title: String = GradumBundle.message("gradum.tool.diff.title", path)
      val leftTitle: String = GradumBundle.message("gradum.tool.diff.original")
      val rightTitle: String = GradumBundle.message("gradum.tool.diff.modified")

      val fileName: String = path.substringAfterLast('/').ifBlank { "diff" }
      val virtualFile: VirtualFile? = resolveVirtualFile(project, path)
      val resolvedType: ResolvedType = if (virtualFile != null) {
        ResolvedType.FromVirtualFile(virtualFile)
      } else {
        val inferred: FileType = FileTypeRegistry.getInstance().getFileTypeByFileName(fileName)
        if (inferred is PlainTextFileType) ResolvedType.PlainTextFallback else ResolvedType.FromFileType(
          inferred
        )
      }

      val leftContent: DiffContent
      val rightContent: DiffContent
      when (val resolution: ResolvedType = resolvedType) {
        is ResolvedType.FromVirtualFile -> {
          val vf: VirtualFile = resolution.file
          leftContent = contentFactory.create(project, originalContent, vf)
          rightContent = contentFactory.create(project, modifiedContent, vf)
          log.info("Resolved source: VirtualFile path='${vf.path}', fileType='${vf.fileType.name}'")
        }

        is ResolvedType.FromFileType -> {
          val ft: FileType = resolution.type
          leftContent = contentFactory.create(project, originalContent, ft)
          rightContent = contentFactory.create(project, modifiedContent, ft)
          log.info("Resolved source: FileType='${ft.name}' (no VirtualFile)")
        }

        ResolvedType.PlainTextFallback -> {
          val ft: FileType = PlainTextFileType.INSTANCE
          leftContent = contentFactory.create(project, originalContent, ft)
          rightContent = contentFactory.create(project, modifiedContent, ft)
          log.info("Resolved source: PlainTextFileType fallback (no syntax highlighting)")
        }
      }

      log.info(
        "DiffContent ready: left=${leftContent.javaClass.simpleName}(${originalContent.length} chars), " +
          "right=${rightContent.javaClass.simpleName}(${modifiedContent.length} chars)"
      )

      val request = SimpleDiffRequest(title, leftContent, rightContent, leftTitle, rightTitle)

      log.info("Dispatching DiffManager.showDiff with DiffDialogHints.MODAL")
      DiffManager.getInstance().showDiff(project, request, DiffDialogHints.MODAL)
      log.info("DiffManager.showDiff returned without throwing")
    } catch (exception: Exception) {
      log.warn("Failed to open diff viewer for $path", exception)
    }
  }

  /**
   * Resolution tier used for selecting which [DiffContentFactory.create]
   * overload to call. Split out as a sealed hierarchy so the caller
   * does not have to remember whether the diff came from a
   * [VirtualFile], a fallback [FileType], or `PlainTextFileType` —
   * each tier routes to a different `create` overload and the log
   * line records which path was taken.
   */
  private sealed class ResolvedType {
    data class FromVirtualFile(val file: VirtualFile) : ResolvedType()
    data class FromFileType(val type: FileType) : ResolvedType()
    data object PlainTextFallback : ResolvedType()
  }

  /**
   * Resolve [path] to a [VirtualFile] in the local file system.
   *
   * Tries, in order:
   * 1. `LocalFileSystem.findFileByPath(path)` — succeeds for
   *    absolute paths the platform already knows about.
   * 2. `path` joined with `project.basePath` — needed because the
   *    `edit_file` tool receives project-relative paths from the
   *    LLM and `findFileByPath` does NOT resolve them itself.
   *
   * Returns null when both lookups fail; the caller falls back to
   * a [FileType] inferred from the file name.
   */
  private fun resolveVirtualFile(project: Project?, path: String): VirtualFile? {
    if (path.isBlank()) return null
    val localFileSystem = LocalFileSystem.getInstance()

    runCatching { localFileSystem.findFileByPath(path) }
      .getOrNull()
      ?.let { return it }

    val basePath: String? = project?.basePath
    if (!basePath.isNullOrBlank() && !File(path).isAbsolute) {
      runCatching { localFileSystem.findFileByPath("$basePath/$path") }
        .getOrNull()
        ?.let { return it }
    }
    return null
  }
}
