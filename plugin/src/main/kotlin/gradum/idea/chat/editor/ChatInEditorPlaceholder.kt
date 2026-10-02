package gradum.idea.chat.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.addKeyboardAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import gradum.idea.utils.GradumBundle.message
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Shown in the tool window while the chat lives in an editor tab, mirroring
 * the platform's tab-in-editor placeholder. The links jump to the editor tab
 * or restore the chat here; restoring closes the editor, whose disposal puts
 * a fresh chat panel back into the tool window.
 */
internal class ChatInEditorPlaceholder(
  val file: VirtualFile, private val project: Project
) : JBPanelWithEmptyText() {
  init {
    emptyText.appendLine(message("gradum.editor.placeholder.title"))
    emptyText.appendLine("")
    emptyText.appendLine(message("gradum.editor.placeholder.jump"), SimpleTextAttributes.LINK_ATTRIBUTES) {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
    emptyText.appendLine(message("gradum.editor.placeholder.restore"), SimpleTextAttributes.LINK_ATTRIBUTES) {
      FileEditorManager.getInstance(project).closeFile(file)
    }
    isFocusable = true
    addKeyboardAction(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
  }
}
