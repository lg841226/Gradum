@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import gradum.idea.chat.editor.openGradumChatInEditor
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.utils.GradumBundle.message
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * Factory for creating the Gradum tool window in IntelliJ IDEA.
 *
 * Creates the welcome tab hosting the shared [GradumChatContent] tree and
 * installs the title actions (New Chat, Open in Editor). The same content
 * can also be shown as an editor tab; see
 * [gradum.idea.chat.editor.GradumChatFileEditor].
 */
@OptIn(ExperimentalJewelApi::class)
class GradumToolWindowFactory : ToolWindowFactory {

  @Suppress("UnstableApiUsage")
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val settings = gradum.idea.settings.AppearanceSettings.getInstance()
    if (!settings.snapshot.agentEnabled) {
      toolWindow.isAvailable = false
      return
    }

    val chatSession: GradumChatSession = project.getService(GradumChatSession::class.java)
      ?: error("GradumChatSession is not registered in plugin.xml")
    chatSession.project = project
    chatSession.refreshSessions()

    toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
      GradumChatContent(
        project = project,
        toolWindow = toolWindow,
        session = chatSession,
      )
    }

    val newChatAction = object : AnAction(
      message("gradum.toolwindow.newchat"),
      message("gradum.toolwindow.newchat.action.text"),
      AllIcons.General.Add
    ) {
      override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        val toolWindow: ToolWindow =
          ToolWindowManager.getInstance(project).getToolWindow("Gradum") ?: return
        val session: GradumChatSession = project.getService(GradumChatSession::class.java) ?: return
        val welcomeTabContent = toolWindow.contentManager.contents.firstOrNull() ?: return

        session.reset()
        welcomeTabContent.displayName = message("gradum.toolwindow.welcome")
      }
    }

    val openInEditorAction = object : AnAction(
      message("gradum.toolwindow.openineditor"),
      message("gradum.toolwindow.openineditor.action.text"),
      AllIcons.Actions.OpenNewTab
    ) {
      override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        openGradumChatInEditor(project = project)
      }
    }

    toolWindow.setTitleActions(listOf(newChatAction, openInEditorAction))

    installToolWindowDragToEditor(project = project, toolWindow = toolWindow)
  }
}
