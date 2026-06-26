/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatInputState.kt  2026-06-26 23:55:00 Changed by gwy
 */

package gradum.idea.chat.input

import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.model.ModelInfo
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.EditorContext
import gradum.idea.editor.PendingMessage

/** UI state for the chat input area. */
data class ChatInputState(
    val isFocused: Boolean,
    val isSending: Boolean,
    val isPendingQueueFull: Boolean,
    val isMenuVisible: Boolean,
    val isExpanded: Boolean,
    val showAddMenu: Boolean,
    val isAttachmentLimitReached: Boolean,
    val selectedPermission: String,
    val editorContext: EditorContext,
    val attachedFiles: List<AttachedContext>,
    val pendingMessages: List<PendingMessage>,
    val models: List<ModelInfo> = emptyList(),
    val selectedModel: ModelInfo? = null,
    val pinnedModels: List<ModelInfo> = emptyList(),
    val isAutoSelected: Boolean = false
)

/** Callback actions for the chat input area. */
data class ChatInputActions(
    val onFocusChange: (Boolean) -> Unit,
    val onToggleMenu: () -> Unit,
    val onSelectPermission: (String) -> Unit,
    val onDismissMenu: () -> Unit,
    val onToggleExpanded: () -> Unit,
    val onClearText: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onToggleAddMenu: () -> Unit,
    val onDismissAddMenu: () -> Unit,
    val onSelectFile: (VirtualFile) -> Unit,
    val onRemoveFile: (AttachedContext) -> Unit,
    val onUploadImage: () -> Unit,
    val onRemovePending: (PendingMessage) -> Unit = {},
    val onPasteAsContext: (String) -> Unit = {},
    val onSelectModel: (ModelInfo?) -> Unit = {},
    val onTogglePin: (ModelInfo) -> Unit = {},
    val onSelectAuto: () -> Unit = {}
)
