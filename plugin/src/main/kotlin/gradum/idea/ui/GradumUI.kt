/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumUI.kt  2026-08-20 09:34:20 Changed by gwy
 */

package gradum.idea.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.wm.ToolWindow
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.ui.ChatScreen
import gradum.idea.chat.ui.home.WelcomeScreen
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.editor.EditorContext
import gradum.idea.editor.EditorUtils
import gradum.idea.provider.ProviderSettings
import gradum.idea.settings.ProvideAppearance
import gradum.idea.utils.GradumBundle.message
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
 * - Building input scanState from session data
 * - Rendering either ChatScreen or WelcomeScreen
 *
 * @param toolWindow The IntelliJ tool window (nullable for testing)
 * @param session The chat session scanState manager
 */
@Composable
fun GradumUI(toolWindow: ToolWindow? = null, session: GradumChatSession) {
  val editorContext: EditorContext = toolWindow?.project?.let {
    EditorUtils.getEditorContext(it)
  } ?: EditorContext.EMPTY
  val coroutineScope = rememberCoroutineScope()

  TabNameEffect(session, toolWindow)
  ModelPollingEffect(session, coroutineScope)

  val callbacks = rememberGradumCallbacks(session, toolWindow, coroutineScope)
  val state = rememberGradumState(session, editorContext, callbacks)

  Box(
    modifier = Modifier.fillMaxSize()
      .padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center
  ) {
    ProvideAppearance {
      if (session.hasSentMessage) {
        ChatScreen(
        messages = session.messages,
        textState = session.textState,
        inputState = state.inputState,
        inputActions = state.inputActions,
        onViewDiff = callbacks.onViewDiff,
        onDeleteMessage = callbacks.onDeleteMessage,
        onRetryMessage = callbacks.onRetryMessage,
        onOpenInEditor = callbacks.onOpenInEditor,
        onAttachmentClick = callbacks.onAttachmentClick,
        modifier = Modifier.fillMaxSize(),
        isLoading = session.isSending,
        sendingPhase = session.sendingPhase,
        hasSentMessage = session.hasSentMessage,
        selectedPermission = session.selectedPermission,
        isWaitingForResponse = session.isWaitingForResponse,
        onCopyAsContext = callbacks.eventCallbacks.onCopyAsContext
      )
    } else {
      WelcomeScreen(
        inputState = state.inputState,
        textState = session.textState,
        inputActions = state.inputActions,
        modifier = Modifier.fillMaxSize(),
        sessions = session.sessions.toList(),
        onCancelMerge = { session.exitMergeMode() },
        onStartMerge = { session.enterMergeMode() },
        isMergeModeActive = session.isMergeModeActive,
        suggestionVariants = session.suggestionVariants,
        selectedPermission = session.selectedPermission,
        mergeSelectedIds = session.mergeSelection.toSet(),
        welcomeLayout = gradum.idea.settings.AppearanceSettings.getInstance().snapshot.welcomeLayout,
        onClearMergeSelection = { session.mergeSelection.clear() },
        onDeleteSession = { sessionId -> session.deleteSession(sessionId) },
        onDeleteSelected = { session.deleteSessions(session.mergeSelection.toList()) },
        onMergeSelected = { coroutineScope.launch { session.mergeSelectedSessions() } },
        onRefreshSuggestions = { session.suggestionVariants = List(4) { Random.nextInt(5) } },
        onOpenSession = { sessionId ->
          coroutineScope.launch { session.switchSession(sessionId) }
        },
        onToggleMergeSelection = { sessionId -> session.toggleMergeSelection(sessionId) }
      ) { sessionId, newTitle ->
        coroutineScope.launch { session.renameSession(sessionId, newTitle) }
      }
    }
    }
  }
}

/**
 * Updates the tool window tab name based on session scanState.
 *
 * Shows a spinner animation while sending, otherwise displays
 * "Welcome" or "New Chat" depending on whether messages have been sent.
 * In debug mode, shows "Preview Markdown".
 */
@Composable
private fun TabNameEffect(session: GradumChatSession, toolWindow: ToolWindow?) {
  LaunchedEffect(session.hasSentMessage, session.isSending, session.selectedPermission) {
    val tabContent = toolWindow?.contentManager?.contents?.firstOrNull() ?: return@LaunchedEffect

    if (PermissionMode.isDebugMode(session.selectedPermission)) {
      tabContent.displayName = message("gradum.debug.mode")
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
 * Loads models on first composition and starts polling for model updates,
 * honoring the provider settings (auto-detect toggle + poll interval).
 * Restarts the poll loop whenever either setting changes. Cleans up
 * polling on disposal.
 */
@Composable
private fun ModelPollingEffect(session: GradumChatSession, coroutineScope: CoroutineScope) {
  val settings = remember { ProviderSettings.getInstance() }
  val autoDetect: Boolean = settings.snapshot.autoDetectEnabled
  val pollIntervalSeconds: Int = settings.snapshot.pollIntervalSeconds

  LaunchedEffect(autoDetect, pollIntervalSeconds) {
    if (!session.modelsLoaded)
      coroutineScope.launch { session.loadModels() }
    session.startModelPolling(autoDetect, pollIntervalSeconds * 1000L, coroutineScope)
  }
  DisposableEffect(Unit) {
    onDispose { session.stopModelPolling() }
  }
}
