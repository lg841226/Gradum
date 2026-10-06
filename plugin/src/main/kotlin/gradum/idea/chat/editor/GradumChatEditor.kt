@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package gradum.idea.chat.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import gradum.idea.createChatComposePanel
import gradum.idea.utils.GradumBundle.message
import org.jetbrains.jewel.foundation.enableNewSwingCompositing
import java.awt.Component
import java.awt.Container
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * FileEditor that hosts the same chat tree as the Gradum tool window. It is
 * the destination of the move performed by [openGradumChatInEditor]: while
 * the tab is open, the tool window shows the session history manager
 * (ChatInEditorHistoryPanel) instead,
 * so only one chat instance exists at a time. The session picks its work
 * scope from whichever hosts are still alive.
 *
 * Disposal: the platform calls [dispose] when the tab closes. It disposes
 * the underlying [ComposePanel] (idempotent) and restores the tool window
 * content with a fresh panel (see [restoreChatToToolWindow]).
 */
@Suppress("UnstableApiUsage")
class GradumChatFileEditor(
  private val project: Project,
  private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

  init {
    enableNewSwingCompositing()
  }

  private val host: JComponent = createChatComposePanel(
    project = project,
    inEditorTab = true,
    toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Gradum"),
  )

  override fun getComponent(): JComponent = host

  override fun getPreferredFocusedComponent(): JComponent = host

  override fun getFile(): VirtualFile = file

  override fun getName(): String = message("gradum.editor.tabname")

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  override fun setState(state: FileEditorState) {}

  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

  override fun dispose() {
    disposeComposeHost(component = host)
    restoreChatToToolWindow(project = project, file = file)
  }
}

/** Locates the [ComposePanel] inside the Jewel wrapper (or the panel itself). */
internal fun findComposePanel(component: Component): ComposePanel? =
  when (component) {
    is ComposePanel -> component
    is Container -> component.components.firstNotNullOfOrNull { child -> findComposePanel(child) }
    else -> null
  }

/** Disposes the Compose host so its composition and host scope go away. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun disposeComposeHost(component: JComponent) {
  findComposePanel(component = component)?.dispose()
}
