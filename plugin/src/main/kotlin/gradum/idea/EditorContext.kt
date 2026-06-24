/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditorContext.kt  2026-06-24 22:36:50 Changed by gwy
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
    val currentLanguage: String?
)

fun getLanguageIconKey(extension: String?): IconKey? {
    return when (extension?.lowercase()) {
        "java" -> AllIconsKeys.FileTypes.Java
        "js" -> AllIconsKeys.FileTypes.JavaScript
        "html" -> AllIconsKeys.FileTypes.Html
        "css" -> AllIconsKeys.FileTypes.Css
        "xml" -> AllIconsKeys.FileTypes.Xml
        "json" -> AllIconsKeys.FileTypes.Json
        "yaml", "yml" -> AllIconsKeys.FileTypes.Yaml
        "txt" -> AllIconsKeys.FileTypes.Text
        else -> AllIconsKeys.FileTypes.Text
    }
}

object EditorUtils {

    fun getEditorContext(project: Project): EditorContext {
        return ReadAction.compute<EditorContext, Throwable> {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val allFiles = fileEditorManager.openFiles.toList()
            val currentFile = fileEditorManager.selectedFiles.firstOrNull()

            if (currentFile != null) {
                val language = LanguageUtil.getLanguageForPsi(project, currentFile)
                EditorContext(
                    currentFile = currentFile,
                    allOpenFiles = allFiles,
                    currentLanguage = language?.displayName ?: currentFile.fileType.name
                )
            } else {
                EditorContext(
                    currentFile = null,
                    allOpenFiles = allFiles,
                    currentLanguage = null
                )
            }
        }
    }
}
