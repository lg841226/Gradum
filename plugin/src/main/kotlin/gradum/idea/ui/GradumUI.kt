/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.wm.ToolWindow
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.ui.ChatScreen
import gradum.idea.chat.ui.home.WelcomeScreen
import gradum.idea.editor.EditorContext
import gradum.idea.editor.EditorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Main composable for the Gradum chat interface.
 *
 * This function orchestrates the entire chat UI by:
 * - Setting up effects for tab name updates and model polling
 * - Creating callbacks for user interactions
 * - Building input state from session data
 * - Rendering either ChatScreen or WelcomeScreen
 *
 * @param toolWindow The IntelliJ tool window (nullable for testing)
 * @param session The chat session state manager
 */
@Composable
fun GradumUI(toolWindow: ToolWindow? = null, session: GradumChatSession) {
  val editorContext: EditorContext = toolWindow?.project?.let {
    EditorUtils.getEditorContext(it)
  } ?: EditorContext.EMPTY
  val coroutineScope = rememberCoroutineScope()

  // Effects
  TabNameEffect(session, toolWindow)
  ModelPollingEffect(session, coroutineScope)

  // Create callbacks and state
  val callbacks = rememberGradumCallbacks(session, toolWindow, coroutineScope)
  val state = rememberGradumState(session, editorContext, callbacks)

  // Render UI
  Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    if (session.hasSentMessage) {
      ChatScreen(
        messages = session.messages,
        isLoading = session.isSending,
        isWaitingForResponse = session.isWaitingForResponse,
        sendingPhase = session.sendingPhase,
        selectedPermission = session.selectedPermission,
        textState = session.textState,
        inputState = state.inputState,
        inputActions = state.inputActions,
        onDeleteMessage = callbacks.onDeleteMessage,
        onRetryMessage = callbacks.onRetryMessage,
        onCopyAsContext = callbacks.eventCallbacks.onCopyAsContext,
        onOpenInEditor = callbacks.onOpenInEditor,
        onViewDiff = callbacks.onViewDiff,
        onAttachmentClick = callbacks.onAttachmentClick,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      WelcomeScreen(
        inputState = state.inputState,
        textState = session.textState,
        suggestionVariants = session.suggestionVariants,
        modifier = Modifier.fillMaxSize(),
        inputActions = state.inputActions,
        selectedPermission = session.selectedPermission
      ) { session.suggestionVariants = List(4) { Random.nextInt(5) } }
    }
  }
}

/**
 * Updates the tool window tab name based on session state.
 *
 * Shows a spinner animation while sending, otherwise displays
 * "Welcome" or "New Chat" depending on whether messages have been sent.
 * In debug mode, shows "Preview Markdown".
 */
@Composable
private fun TabNameEffect(session: GradumChatSession, toolWindow: ToolWindow?) {
  LaunchedEffect(session.hasSentMessage, session.isSending, session.selectedPermission) {
    val tabContent = toolWindow?.contentManager?.contents?.firstOrNull() ?: return@LaunchedEffect

    if (session.selectedPermission == "debug" && !session.hasSentMessage) {
      tabContent.displayName = message("gradum.debug")
      return@LaunchedEffect
    }

    val tabName: String = if (session.hasSentMessage) message("gradum.toolwindow.newchat")
    else message("gradum.toolwindow.welcome")

    if (session.isSending) {
      val spinnerFrames: CharArray =
        charArrayOf('\u280B', '\u2819', '\u2839', '\u2838', '\u283C', '\u2834', '\u2826', '\u2827')
      var frameIndex = 0
      while (session.isSending) {
        tabContent.displayName = "$tabName  ${spinnerFrames[frameIndex]}"
        frameIndex = (frameIndex + 1) % spinnerFrames.size; delay(100.milliseconds)
      }
    }
    tabContent.displayName = tabName
  }
}

/**
 * Manages model loading and periodic polling.
 *
 * Loads models on first composition and starts polling for model updates.
 * Cleans up polling on disposal.
 */
@Composable
private fun ModelPollingEffect(session: GradumChatSession, coroutineScope: CoroutineScope) {
  LaunchedEffect(Unit) {
    if (!session.modelsLoaded)
      coroutineScope.launch { session.loadModels() }
    session.startModelPolling(coroutineScope)
  }
  DisposableEffect(Unit) {
    onDispose { session.stopModelPolling() }
  }
}
