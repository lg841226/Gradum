/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditorContext.kt  2026-07-28 20:39:34 Changed by gwy
 */

package gradum.idea.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

data class EditorContext(
  val currentFile: VirtualFile?,
  val allOpenFiles: List<VirtualFile>,
  val projectDir: VirtualFile?
) {
  companion object {
    val EMPTY = EditorContext(null, emptyList(), null)
  }
}

fun getLanguageIconKey(extension: String?): IconKey? {
  return when (extension?.lowercase()) {
    "java" -> AllIconsKeys.FileTypes.Java
    "js" -> AllIconsKeys.FileTypes.JavaScript
    "jsx" -> GradumIcons.Jsx
    "ts" -> GradumIcons.TypeScript
    "tsx" -> GradumIcons.Tsx
    "html" -> AllIconsKeys.FileTypes.Html
    "css" -> AllIconsKeys.FileTypes.Css
    "xml" -> AllIconsKeys.FileTypes.Xml
    "json" -> AllIconsKeys.FileTypes.Json
    "yaml", "yml" -> AllIconsKeys.FileTypes.Yaml
    "txt" -> AllIconsKeys.FileTypes.Text
    "md" -> GradumIcons.Markdown
    "kt", "kts" -> GradumIcons.Kotlin
    "py" -> GradumIcons.Python
    "http" -> AllIconsKeys.FileTypes.Http
    "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff" -> GradumIcons.Png
    "kotlin" -> GradumIcons.Kotlin
    "python" -> GradumIcons.Python
    "javascript" -> AllIconsKeys.FileTypes.JavaScript
    "typescript" -> GradumIcons.TypeScript
    "markdown" -> GradumIcons.Markdown
    "bash", "shell", "sh", "zsh" -> GradumIcons.Ran
    // "go" -> AllIconsKeys.FileTypes.Go
    // "rs" -> AllIconsKeys.FileTypes.Rust
    // "rb" -> AllIconsKeys.FileTypes.Ruby
    // "php" -> AllIconsKeys.FileTypes.Php
    // "c" -> AllIconsKeys.FileTypes.C
    // "cpp", "h" -> AllIconsKeys.FileTypes.Cpp
    // "cs" -> AllIconsKeys.FileTypes.Csharp
    // "swift" -> AllIconsKeys.FileTypes.Swift
    // "scala" -> AllIconsKeys.FileTypes.Scala
    // "groovy" -> AllIconsKeys.FileTypes.Groovy
    // "r" -> GradumIcons.R
    // "sql" -> AllIconsKeys.FileTypes.Sql
    // "toml" -> GradumIcons.Toml
    // "ini", "cfg", "conf" -> AllIconsKeys.FileTypes.Config
    // "dockerfile" -> GradumIcons.Docker
    // "makefile" -> GradumIcons.Makefile
    // "cmake" -> GradumIcons.CMake
    // "gradle" -> GradumIcons.Gradle
    // "dart" -> AllIconsKeys.FileTypes.Dart
    // "vue" -> GradumIcons.Vue
    // "svelte" -> GradumIcons.Svelte
    // "elm" -> GradumIcons.Elm
    // "hs" -> AllIconsKeys.FileTypes.Haskell
    // "ex", "exs" -> GradumIcons.Elixir
    // "erl" -> GradumIcons.Erlang
    // "clj" -> GradumIcons.Clojure
    // "jl" -> GradumIcons.Julia
    // "lua" -> GradumIcons.Lua
    // "solidity", "sol" -> GradumIcons.Solidity
    // "ps1" -> GradumIcons.PowerShell
    // "bat", "cmd" -> GradumIcons.WindowsBatch
    else -> AllIconsKeys.FileTypes.Text
  }
}

object EditorUtils {

  fun getEditorContext(project: Project): EditorContext {
    return ApplicationManager.getApplication().runReadAction<EditorContext> {
      val fileEditorManager = FileEditorManager.getInstance(project)
      val allFiles = fileEditorManager.openFiles.toList()
      val currentFile = fileEditorManager.selectedFiles.firstOrNull()
      val projectDir = project.basePath?.let {
        LocalFileSystem.getInstance().findFileByPath(it)
      }

      if (currentFile != null) {
        EditorContext(
          currentFile = currentFile,
          allOpenFiles = allFiles,
          projectDir = projectDir
        )
      } else {
        EditorContext(
          currentFile = null,
          allOpenFiles = allFiles,
          projectDir = projectDir
        )
      }
    }
  }

  /**
   * Opens a fenced code block as a brand-new untitled editor tab.
   *
   * Creates a `LightVirtualFile` backed by a PSI file (so syntax highlighting
   * kicks in immediately for the chosen language), then asks the
   * [FileEditorManager] to open it. The file lives in memory until the user
   * chooses `Save As` from the editor — we never write into the project root
   * unprompted, since that would be a destructive side effect of just
   * clicking a code-block toolbar button.
   *
   * Threading: this is invoked from the Swing EDT (toolbar click), so the
   * PSI factory call is wrapped in [ApplicationManager.runReadAction] per
   * the platform's "read action on EDT" requirement. [FileEditorManager.openFile]
   * runs back on the EDT once the read action releases.
   *
   * @param project  current IntelliJ project.
   * @param code     raw code block text (no fence markers).
   * @param language fenced language tag (e.g. `"kotlin"`); pass `""` for
   *                 unknown / unlabelled blocks — falls back to plain text.
   */
  fun openCodeAsNewFile(project: Project, code: String, language: String) {
    val normalizedLanguage = language.trim().lowercase()
    val extension = extensionForLanguage(normalizedLanguage)
    val stem = normalizedLanguage.ifBlank { "untitled" }
    val fileName = "gradum_$stem.$extension"

    val baseDir: VirtualFile = project.baseDir ?: return

    val virtualFile = WriteCommandAction.writeCommandAction(project)
      .compute<VirtualFile, Exception> {
        val newFile = baseDir.createChildData(this, fileName)
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
  private fun extensionForLanguage(language: String): String = when (language) {
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
