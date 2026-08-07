/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-08-07 16:04:18 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.ui.markdown.GradumCodeBlockRenderer
import gradum.idea.chat.ui.markdown.GradumMarkdownProcessor
import gradum.idea.chat.ui.markdown.rememberGradumMarkdownStyling
import gradum.idea.editor.EditorUtils
import gradum.idea.ui.GradumUI
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling

/**
 * Factory for creating the Gradum tool window in IntelliJ IDEA.
 *
 * This factory sets up the Compose-based UI with Markdown rendering,
 * code highlighting, and the main [GradumUI] composable.
 */
@OptIn(ExperimentalJewelApi::class)
class GradumToolWindowFactory : ToolWindowFactory {

  @Suppress("UnstableApiUsage")
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val chatSession: GradumChatSession = project.getService(GradumChatSession::class.java)
      ?: error("GradumChatSession is not registered in plugin.xml")
    chatSession.project = project

    toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
      SwingBridgeTheme {
        val uiCoroutineScope: CoroutineScope = rememberCoroutineScope()
        chatSession.scope = uiCoroutineScope
        val codeHighlighter = remember(project, uiCoroutineScope) {
          CodeHighlighterFactory(project, uiCoroutineScope).createHighlighter()
        }
        val markdownStyling = rememberGradumMarkdownStyling()
        val blockRenderer = remember(markdownStyling) {
          GradumCodeBlockRenderer(
            styling = markdownStyling,
            onInsertAsFile = { code, language ->
              EditorUtils.openCodeAsNewFile(project, code, language)
            },
          )
        }
        ProvideMarkdownStyling(
          markdownStyling = markdownStyling,
          markdownProcessor = GradumMarkdownProcessor,
          markdownBlockRenderer = blockRenderer,
          codeHighlighter = codeHighlighter,
        ) {
          CompositionLocalProvider(LocalCodeHighlighter provides codeHighlighter) {
            GradumUI(toolWindow = toolWindow, session = chatSession)
          }
        }
      }
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
    toolWindow.setTitleActions(listOf(newChatAction))
  }
}
