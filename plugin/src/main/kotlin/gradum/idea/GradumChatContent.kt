@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea

import androidx.compose.runtime.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.ui.markdown.GradumCodeBlockRenderer
import gradum.idea.chat.ui.markdown.GradumMarkdownProcessor
import gradum.idea.chat.ui.markdown.rememberGradumMarkdownStyling
import gradum.idea.editor.EditorUtils
import gradum.idea.ui.GradumUI
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import javax.swing.JComponent

/**
 * The Gradum chat Compose tree, shared by the Gradum tool window tab
 * (GradumToolWindowFactory) and the Gradum chat editor tab
 * ([gradum.idea.chat.editor.GradumChatFileEditor]).
 *
 * Both hosts can be open at the same time against the same project-level
 * [GradumChatSession]. Each host attaches its UI coroutine scope to the
 * session on composition and releases it when its composition is disposed,
 * so the session always drives work from a host that is still alive
 * (see GradumChatSession.scope and GradumChatSession.releaseHostScope).
 *
 * [toolWindow] should be the real Gradum tool window whenever one exists:
 * [GradumUI] derives the project and focused-editor context from it, so
 * passing null degrades features such as send-context resolution.
 */
@Composable
fun GradumChatContent(
  project: Project,
  toolWindow: ToolWindow?,
  session: GradumChatSession
) {
  @Suppress("UnstableApiUsage")
  SwingBridgeTheme {
    val uiCoroutineScope: CoroutineScope = rememberCoroutineScope()
    session.scope = uiCoroutineScope
    DisposableEffect(key1 = session, key2 = uiCoroutineScope) {
      onDispose {
        session.releaseHostScope(hostScope = uiCoroutineScope)
      }
    }
    val codeHighlighter = remember(key1 = project, key2 = uiCoroutineScope) {
      CodeHighlighterFactory(project, uiCoroutineScope).createHighlighter()
    }
    val markdownStyling = rememberGradumMarkdownStyling()
    val blockRenderer = remember(key1 = markdownStyling) {
      GradumCodeBlockRenderer(
        styling = markdownStyling,
        onInsertAsFile = { code, language ->
          EditorUtils.openCodeAsNewFile(project, code, language)
        },
      )
    }
    @Suppress("UnstableApiUsage")
    ProvideMarkdownStyling(
      markdownStyling = markdownStyling,
      markdownProcessor = GradumMarkdownProcessor,
      markdownBlockRenderer = blockRenderer,
      codeHighlighter = codeHighlighter,
    ) {
      CompositionLocalProvider(value = LocalCodeHighlighter provides codeHighlighter) {
        GradumUI(toolWindow = toolWindow, session = session)
      }
    }
  }
}

/**
 * Builds [GradumChatContent] as a standalone Swing panel. Used by the chat
 * editor host and to restore the tool window content after the chat moves
 * back from an editor tab: the displaced panel is disposed by the platform
 * (ComposePanel disposes on remove), so restoration always builds a fresh
 * panel instead of reattaching the old one.
 */
fun createChatComposePanel(project: Project, toolWindow: ToolWindow?): JComponent =
  JewelComposePanel {
    val chatSession: GradumChatSession = project.getService(GradumChatSession::class.java)
      ?: error("GradumChatSession is not registered in plugin.xml")
    GradumChatContent(
      project = project,
      session = chatSession,
      toolWindow = toolWindow
    )
  }
