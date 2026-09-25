/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatSessionState.kt  2026-09-26 00:21:14 Changed by gwy
 */

package gradum.idea.chat.state

import androidx.compose.foundation.text.input.TextFieldState
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.history.SessionMeta
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.PendingMessage

data class ChatSessionState(
  val messages: List<ChatMessage>,
  val textState: TextFieldState,
  val isLoading: Boolean,
  val sendingPhase: String,
  val hasSentMessage: Boolean,
  val selectedPermission: String,
  val isWaitingForResponse: Boolean,

  val inputState: ChatInputState,
  val subAgentState: SubAgentState,
  val sessions: List<SessionMeta>,
  val isMergeModeActive: Boolean,
  val mergeSelectedIds: Set<String>,
  val suggestionVariants: List<Int>,
  val inputActions: ChatInputActions,

  val onSend: () -> Unit,
  val onStop: () -> Unit,
  val onRetryMessage: (Int) -> Unit,
  val onDeleteMessage: (Int) -> Unit,

  val onAttachmentClick: (VirtualFile) -> Unit,
  val onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit,
  val onViewDiff: (filePath: String, original: String, modified: String) -> Unit,

  val onCopyAsContext: (String) -> Unit,
  val onPasteAsContext: (String) -> Unit,

  val onClearText: () -> Unit,
  val onToggleMenu: () -> Unit,
  val onDismissMenu: () -> Unit,
  val onUploadImage: () -> Unit,
  val onToggleAddMenu: () -> Unit,
  val onDismissAddMenu: () -> Unit,
  val onToggleExpanded: () -> Unit,
  val onFocusChange: (Boolean) -> Unit,
  val onSelectPermission: (String) -> Unit,
  val onRemoveFile: (AttachedContext) -> Unit,
  val onSelectModel: (ModelInfo?) -> Unit,
  val onTogglePin: (ModelInfo) -> Unit,
  val onRefreshModels: () -> Unit,
  val onSelectFile: (VirtualFile) -> Unit,
  val onRemovePending: (PendingMessage) -> Unit,
  val onSelectThinkingLevel: (ThinkingLevel) -> Unit,

  val onRefreshSuggestions: () -> Unit,
  val onOpenSession: (String) -> Unit,
  val onStartMerge: () -> Unit,
  val onCancelMerge: () -> Unit,
  val onMergeSelected: () -> Unit,
  val onDeleteSelected: () -> Unit,
  val onClearMergeSelection: () -> Unit,
  val onToggleMergeSelection: (String) -> Unit,
  val onRenameSession: (String, String) -> Unit,
  val onDeleteSession: (String) -> Unit,

  val onRespondToAsk: suspend (
    sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean
  ) -> Unit,

  val dismissedAskRequestIds: Set<String>,
  val onDismissAsk: (String) -> Unit,
  val onSubChatClick: (String) -> Unit,
)
