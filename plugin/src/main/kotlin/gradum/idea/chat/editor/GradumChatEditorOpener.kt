package gradum.idea.chat.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import gradum.idea.GradumChatContent
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.createChatComposePanel
import javax.swing.JComponent

/**
 * State of the tool window content while the chat lives in an editor tab.
 * The content's original chat panel is disposed by the platform when it is
 * replaced (ComposePanel disposes on remove), so restoring the content
 * builds a fresh panel via [GradumChatContent]/createChatComposePanel.
 */
internal class ChatDisplacement(
  val toolWindow: ToolWindow,
  val content: Content,
)

internal val CHAT_DISPLACEMENT_KEY: Key<ChatDisplacement> =
  Key.create("gradum.chat.displacement")

/**
 * Moves (or focuses) the single Gradum chat editor tab for [project].
 *
 * This is a move, not a copy: on the first open the tool window content is
 * replaced with a [ChatInEditorHistoryPanel] (the session history manager),
 * so exactly one chat instance exists at a time while the tool window stays
 * useful beside the editor. Closing the editor tab restores the chat into
 * the tool window (see [restoreChatToToolWindow]). Repeated invocations just
 * focus the already-open tab.
 */
fun openGradumChatInEditor(project: Project) {
  val chatSession: GradumChatSession = project.getService(GradumChatSession::class.java)
    ?: error("GradumChatSession is not registered in plugin.xml")
  chatSession.project = project
  chatSession.refreshSessions()

  val editorManager: FileEditorManager = FileEditorManager.getInstance(project)
  val existingFile = editorManager.openFiles.firstOrNull { openFile -> openFile is GradumChatVirtualFile }
  if (existingFile != null) {
    editorManager.openFile(existingFile, true)
    return
  }

  val file = GradumChatVirtualFile()
  val toolWindow: ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow("Gradum")
  val content: Content? = toolWindow?.contentManager?.contents?.firstOrNull()
  val historyPanel = content?.component as? ChatInEditorHistoryPanel
  if (toolWindow == null || content == null || historyPanel != null) {
    // The history-panel branch reuses its file: a second invocation can land
    // here before the first one's openFile ran, and two distinct
    // pseudo-files would stack two editor tabs.
    editorManager.openFile(historyPanel?.file ?: file, true)
    return
  }

  val displacedComponent: JComponent = content.component
  file.putUserData(
    CHAT_DISPLACEMENT_KEY,
    ChatDisplacement(toolWindow = toolWindow, content = content),
  )
  content.component = ChatInEditorHistoryPanel(file = file, project = project)
  disposeComposeHost(component = displacedComponent)
  editorManager.openFile(file, true)
}

/**
 * Puts the tool window content back after the chat editor tab closes.
 * Called from [GradumChatFileEditor.dispose]; does nothing when the chat
 * was never displaced from the tool window.
 */
internal fun restoreChatToToolWindow(project: Project, file: VirtualFile) {
  val displacement: ChatDisplacement = file.getUserData(CHAT_DISPLACEMENT_KEY) ?: return
  file.putUserData(CHAT_DISPLACEMENT_KEY, null)
  if (!displacement.content.isValid) return

  displacement.content.component = createChatComposePanel(
    project = project,
    toolWindow = displacement.toolWindow
  )
  if (displacement.toolWindow.isVisible) {
    val toolWindowComponent: JComponent = displacement.toolWindow.component
    toolWindowComponent.revalidate()
    toolWindowComponent.repaint()
  }
}
