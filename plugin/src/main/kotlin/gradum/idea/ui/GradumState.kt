/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumState.kt  2026-07-31 15:54:30 Changed by gwy
 */

package gradum.idea.ui

import androidx.compose.runtime.Composable
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.editor.EditorContext

/**
 * Holds all input-related scanState for the Gradum chat interface.
 */
data class GradumState(
  val inputState: ChatInputState,
  val inputActions: ChatInputActions,
)

/**
 * Creates and remembers all Gradum input scanState.
 *
 * @param session The chat session scanState
 * @param editorContext The current editor context (focused file, open files)
 * @param callbacks All UI event callbacks
 */
@Composable
fun rememberGradumState(
  session: GradumChatSession,
  editorContext: EditorContext,
  callbacks: GradumCallbacks,
): GradumState {
  val inputState = rememberInputState(session, editorContext)
  val inputActions = rememberInputActions(session, callbacks)

  return GradumState(
    inputState = inputState,
    inputActions = inputActions,
  )
}

@Composable
private fun rememberInputState(
  session: GradumChatSession,
  editorContext: EditorContext,
): ChatInputState {
  return ChatInputState(
    models = session.models.toList(),
    pinnedModels = session.pinnedModels.toList(),
    selectedModel = session.selectedModel,
    isAutoSelected = session.isAutoSelected,
    modelsLoaded = session.modelsLoaded,
    editorContext = editorContext,
    attachedFiles = session.attachedFiles,
    pendingMessages = session.pendingMessages,
    isAttachmentLimitReached = session.isAttachmentLimitReached,
    selectedPermission = session.selectedPermission,
    isFocused = session.isFocused,
    isSending = session.isSending,
    isPendingQueueFull = session.isPendingQueueFull,
    isExpanded = session.isExpanded,
    isMenuVisible = session.isMenuVisible,
    showAddMenu = session.showAddMenu
  )
}

@Composable
private fun rememberInputActions(
  session: GradumChatSession,
  callbacks: GradumCallbacks,
): ChatInputActions {
  return ChatInputActions(
    onSend = callbacks.onSend,
    onStop = callbacks.onStop,
    onUploadImage = callbacks.eventCallbacks.onUploadImage,
    onClearText = callbacks.eventCallbacks.onClearText,
    onToggleMenu = callbacks.eventCallbacks.onToggleMenu,
    onDismissMenu = callbacks.eventCallbacks.onDismissMenu,
    onToggleAddMenu = callbacks.eventCallbacks.onToggleAddMenu,
    onDismissAddMenu = callbacks.eventCallbacks.onDismissAddMenu,
    onToggleExpanded = callbacks.eventCallbacks.onToggleExpanded,
    onFocusChange = callbacks.eventCallbacks.onFocusChange,
    onSelectPermission = callbacks.eventCallbacks.onSelectPermission,
    onRemoveFile = callbacks.eventCallbacks.onRemoveFile,
    onSelectModel = { model ->
      session.selectedModel = model
      session.isAutoSelected = false
    },
    onTogglePin = { model ->
      if (session.pinnedModels.any { it.name == model.name && it.serverName == model.serverName })
        session.pinnedModels.removeAll { it.name == model.name && it.serverName == model.serverName }
      else
        session.pinnedModels.add(model)
    },
    onSelectAuto = {
      session.selectedModel = session.recommendedModel ?: session.models.firstOrNull()
      session.isAutoSelected = true
    },
    onSelectFile = callbacks.eventCallbacks.onSelectFile,
    onRemovePending = callbacks.eventCallbacks.onRemovePending,
    onPasteAsContext = callbacks.eventCallbacks.onPasteAsContext
  )
}
