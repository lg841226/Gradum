/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatInputState.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.input

import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.model.ModelInfo
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.EditorContext
import gradum.idea.editor.PendingMessage

/** UI scanState for the chat input area. */
data class ChatInputState(
  val models: List<ModelInfo> = emptyList(),
  val pinnedModels: List<ModelInfo> = emptyList(),
  val selectedModel: ModelInfo? = null,
  val isAutoSelected: Boolean = false,
  val modelsLoaded: Boolean = false,
  val editorContext: EditorContext,
  val attachedFiles: List<AttachedContext>,
  val pendingMessages: List<PendingMessage>,
  val isAttachmentLimitReached: Boolean,
  val selectedPermission: String,
  val isFocused: Boolean,
  val isSending: Boolean,
  val isPendingQueueFull: Boolean,
  val isExpanded: Boolean,
  val isMenuVisible: Boolean,
  val showAddMenu: Boolean,
) {
  /**
   * `true` if the currently selected model can accept image
   * attachments. Used by the `Upload Image` row in the add-menu
   * popup to switch to the disabled scanState with a `gradum.model.no.vision`
   * tooltip. Auto-select falls through to the recommended model,
   * which may or may not be a vision model — if it is not, the
   * same rule still applies, so we read directly from
   * [selectedModel] without inspecting [recommendedModel].
   *
   * Defaults to `false` when no model is picked yet, so a brand-new session with an empty roster never exposes the upload
   * button as enabled. The button becomes enabled the moment the
   * server returns a model whose `attachment` flag is true.
   */
  val isCurrentModelSupportsVision: Boolean
    get() = selectedModel?.attachment == true
}

/** Callback actions for the chat input area. */
data class ChatInputActions(
  val onSend: () -> Unit,
  val onStop: () -> Unit,
  val onUploadImage: () -> Unit,
  val onClearText: () -> Unit,
  val onToggleMenu: () -> Unit,
  val onDismissMenu: () -> Unit,
  val onToggleAddMenu: () -> Unit,
  val onDismissAddMenu: () -> Unit,
  val onToggleExpanded: () -> Unit,
  val onFocusChange: (Boolean) -> Unit,
  val onSelectPermission: (String) -> Unit,
  val onRemoveFile: (AttachedContext) -> Unit,
  val onSelectModel: (ModelInfo?) -> Unit = {},
  val onTogglePin: (ModelInfo) -> Unit = {},
  val onSelectAuto: () -> Unit = {},
  val onSelectFile: (VirtualFile) -> Unit,
  val onRemovePending: (PendingMessage) -> Unit = {},
  val onPasteAsContext: (String) -> Unit = {},
)
