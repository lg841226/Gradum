package gradum.idea.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.icons.Icon
import com.intellij.platform.icons.swing.toNewIcon
import com.intellij.util.IconUtil
import gradum.idea.chat.editor.GradumChatVirtualFile

data class EditorContext(
  val projectDir: VirtualFile?,
  val currentFile: VirtualFile?,
  val allOpenFiles: List<VirtualFile>
) {
  companion object {
    val EMPTY = EditorContext(projectDir = null, currentFile = null, allOpenFiles = emptyList())
  }
}

// File icons are read from the IDE's own file type registry instead of a
// hand-written extension table: every language a bundled plugin registers
// (Kotlin, Python, JS/TS, Docker, ...) shows the same icon in the chat as in
// the IDE's own views, and nobody has to enumerate extensions by hand.
//
// The platform hands file icons out as Swing icons while Jewel renders the
// platform's new icon model, so each lookup is bridged with [toNewIcon] and used
// through Jewel's `Icon(icon = ...)` overload.

/** Icon the IDE shows for a plain text file; also the fallback for icon-less file types. */
val TextFileIcon: Icon by lazy { AllIcons.FileTypes.Text.toNewIcon() }

/**
 * The icon the IDE itself shows for [file]: the file type icon for regular files,
 * the folder icon for directories, plus whatever icon a plugin contributes for
 * that particular file. Icon providers may touch PSI, so the lookup runs under a
 * read action like the rest of our platform reads.
 */
fun fileIcon(file: VirtualFile): Icon = ReadAction.compute<Icon, RuntimeException> {
  IconUtil.getIcon(file, 0, null).toNewIcon()
}

/**
 * Icon for a GFM fenced-code language tag. The tag is mapped to a conventional
 * file extension (the same mapping [EditorUtils.openCodeAsNewFile] uses when it
 * turns a block into a file) and then resolved through the file type registry, so
 * a `kotlin` block is labelled with the icon of the `.kt` file it would create.
 */
fun languageIcon(language: String?): Icon =
  extensionIcon(extension = EditorUtils.extensionForLanguage(language.orEmpty().trim().lowercase()))

private fun extensionIcon(extension: String): Icon =
  FileTypeManager.getInstance().getFileTypeByExtension(extension).icon?.toNewIcon() ?: TextFileIcon

object EditorUtils {

  fun getEditorContext(project: Project): EditorContext {
    return ReadAction.compute<EditorContext, RuntimeException> {
      val fileEditorManager = FileEditorManager.getInstance(project)
      val allFiles: List<VirtualFile> =
        fileEditorManager.openFiles.filterNot { openFile -> openFile is GradumChatVirtualFile }
      val currentFile: VirtualFile? = fileEditorManager.selectedFiles
        .firstOrNull()
        ?.takeIf { selectedFile -> selectedFile !is GradumChatVirtualFile }
      val projectDir = project.basePath?.let {
        LocalFileSystem.getInstance().findFileByPath(it)
      }

      EditorContext(
        projectDir = projectDir,
        currentFile = currentFile,
        allOpenFiles = allFiles
      )
    }
  }

  /**
   * Opens a fenced code block as a brand-new untitled editor tab.
   *
   * Creates a `LightVirtualFile` backed by a PSI file (so syntax highlighting
   * kicks in immediately for the chosen language), then asks the
   * [FileEditorManager] to open it. The file lives in memory until the user
   * chooses `Save As` from the editor: we never write into the project root
   * unprompted, since that would be a destructive side effect of just
   * clicking a code-block toolbar button.
   *
   * Threading: this is invoked from the Swing EDT (toolbar click), so the
   * PSI factory call is wrapped in ApplicationManager.runReadAction per
   * the platform's "read action on EDT" requirement. [FileEditorManager.openFile]
   * runs back on the EDT once the read action releases.
   *
   * @param project  current IntelliJ project.
   * @param code     raw code block text (no fence markers).
   * @param language fenced language tag (e.g. `"kotlin"`); pass `""` for
   *                 unknown / unlabelled blocks: falls back to plain text.
   */
  fun openCodeAsNewFile(project: Project, code: String, language: String) {
    val normalizedLanguage: String = language.trim().lowercase()
    val extension: String = extensionForLanguage(normalizedLanguage)
    val stem: String = normalizedLanguage.ifBlank { "untitled" }
    val fileName = "gradum_$stem.$extension"

    val basePath: String = project.basePath ?: return
    val baseDir: VirtualFile = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return

    val virtualFile = WriteCommandAction.writeCommandAction(project)
      .compute<VirtualFile, Exception> {
        val newFile: VirtualFile = baseDir.createChildData(this, fileName)
        newFile.setBinaryContent(code.toByteArray(Charsets.UTF_8))
        newFile
      }

    ApplicationManager.getApplication().invokeLater {
      FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }
  }

  /**
   * Maps a GFM fenced-code language tag to a conventional file extension.
   * Unknown / blank tags fall back to `.txt`.
   */
  internal fun extensionForLanguage(language: String): String =
    when (language) {
      "kotlin", "kt" -> "kt"
      "java" -> "java"
      "javascript", "js" -> "js"
      "typescript", "ts" -> "ts"
      "python", "py" -> "py"
      "go", "golang" -> "go"
      "rust", "rs" -> "rs"
      "c" -> "c"
      "cpp", "c++" -> "cpp"
      "csharp", "cs", "c#" -> "cs"
      "html" -> "html"
      "css" -> "css"
      "scss" -> "scss"
      "json" -> "json"
      "yaml", "yml" -> "yaml"
      "xml" -> "xml"
      "shell", "bash", "sh", "zsh" -> "sh"
      "ruby", "rb" -> "rb"
      "php" -> "php"
      "sql" -> "sql"
      "markdown", "md" -> "md"
      "swift" -> "swift"
      "kotlin-script", "kts" -> "kts"
      else -> "txt"
    }
}
