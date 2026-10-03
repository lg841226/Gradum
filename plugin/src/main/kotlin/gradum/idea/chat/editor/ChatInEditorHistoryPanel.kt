package gradum.idea.chat.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.ui.home.ManageSessionsBoard
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class ChatInEditorHistoryPanel(val file: VirtualFile, private val project: Project) : JPanel(BorderLayout()) {
  init {
    add(createHistoryBoard(project = project, file = file))
  }
}

@OptIn(ExperimentalJewelApi::class)
private fun createHistoryBoard(project: Project, file: VirtualFile): JComponent =
  JewelComposePanel {
    @Suppress("UnstableApiUsage")
    SwingBridgeTheme {
      HistoryBoard(project = project, file = file)
    }
  }

@Composable
private fun HistoryBoard(project: Project, file: VirtualFile) {
  val chatSession: GradumChatSession = project.getService(GradumChatSession::class.java)
    ?: error("GradumChatSession is not registered in plugin.xml")
  val scope = rememberCoroutineScope()
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(color = JewelTheme.globalColors.toolwindowBackground)
      .padding(horizontal = GradumSpacing.xl),
    contentAlignment = Alignment.Center
  ) {
    ManageSessionsBoard(
      onBack = { FileEditorManager.getInstance(project).closeFile(file) },
      sessions = chatSession.sessions.toList(),
      onMerge = { scope.launch { chatSession.mergeSelectedSessions() } },
      selectedIds = chatSession.mergeSelection.toSet(),
      onClearSelection = { chatSession.mergeSelection.clear() },
      onDeleteSession = { sessionId: String -> chatSession.deleteSession(targetSessionId = sessionId) },
      onToggleSelection = { sessionId: String -> chatSession.toggleMergeSelection(sessionId = sessionId) },
      onDeleteSelected = { chatSession.deleteSessions(sessionIds = chatSession.mergeSelection.toList()) },
      onOpenSession = { sessionId: String ->
        scope.launch { chatSession.switchSession(targetSessionId = sessionId) }
      },
      onRenameSession = { sessionId: String, title: String ->
        scope.launch { chatSession.renameSession(sessionId = sessionId, newTitle = title) }
      },
    )
  }
}
