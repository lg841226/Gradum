package gradum.idea.chat.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Routes [GradumChatVirtualFile] to [GradumChatFileEditor] so the Gradum
 * chat can be hosted as an editor tab. [accept] matches only the pseudo-file
 * class, so ordinary project files keep their normal editors. Registered in
 * plugin.xml as the fileEditorProvider extension.
 *
 * The policy is [FileEditorPolicy.HIDE_OTHER_EDITORS]: the pseudo-file is a
 * plain-text LightVirtualFile, so without it the platform's default text
 * provider would also open the file as a second, empty "Gradum Chat" editor.
 */
class GradumChatEditorProvider : FileEditorProvider {

  override fun accept(project: Project, file: VirtualFile): Boolean =
    file is GradumChatVirtualFile

  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    GradumChatFileEditor(project = project, file = file)

  override fun getEditorTypeId(): String = "gradum.chat.editor"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS
}
