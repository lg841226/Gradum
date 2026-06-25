/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditorContext.kt  2026-06-25 13:41:05 Changed by gwy
 */

package gradum.idea

import com.intellij.lang.LanguageUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

data class EditorContext(
    val currentFile: VirtualFile?,
    val allOpenFiles: List<VirtualFile>,
    val currentLanguage: String?,
    val projectDir: VirtualFile?
) {
    companion object {
        val EMPTY = EditorContext(null, emptyList(), null, null)
    }
}

/**
 * A file the user has attached from the add-menu or file chooser, exposed
 * to the chat as additional LLM context.
 *
 * @property file  The IntelliJ [VirtualFile] backing this attachment.
 * @property iconKey  The Jewel icon key used to render the attachment chip
 *   in the input bar. Selected by [getLanguageIconKey] from the file's
 *   extension (or `Unknown` when the extension is not registered).
 */
data class AttachedFile(
    val file: VirtualFile,
    val iconKey: IconKey
)

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
        else -> AllIconsKeys.FileTypes.Text
    }
}

object EditorUtils {

    fun getEditorContext(project: Project): EditorContext {
        return ReadAction.compute<EditorContext, Throwable> {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val allFiles = fileEditorManager.openFiles.toList()
            val currentFile = fileEditorManager.selectedFiles.firstOrNull()
            val projectDir = project.baseDir

            if (currentFile != null) {
                val language = LanguageUtil.getLanguageForPsi(project, currentFile)
                EditorContext(
                    currentFile = currentFile,
                    allOpenFiles = allFiles,
                    currentLanguage = language?.displayName ?: currentFile.fileType.name,
                    projectDir = projectDir
                )
            } else {
                EditorContext(
                    currentFile = null,
                    allOpenFiles = allFiles,
                    currentLanguage = null,
                    projectDir = projectDir
                )
            }
        }
    }
}
