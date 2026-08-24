/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatToolbar.kt  2026-08-25 01:43:26 Changed by gwy
 */

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Toolbar row inside [ChatInputPanel] with add-menu, permission selector, and action buttons.
 */
@Composable
fun ChatToolbar(
  state: ChatInputState,
  actions: ChatInputActions,
  isTextNotEmpty: Boolean,
  hasSentMessage: Boolean = false,
  selectedPermission: String = PermissionMode.READONLY,
  modifier: Modifier = Modifier
) {
  val searchState: TextFieldState = remember { TextFieldState() }

  val searchQuery: String = searchState.text.toString()
  val filteredFiles = if (searchQuery.isBlank()) {
    state.editorContext.allOpenFiles
  } else {
    state.editorContext.allOpenFiles.filter {
      it.name.contains(other = searchQuery, ignoreCase = true)
    }
  }

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconTooltipButton(
      contentDescription = message("gradum.add"),
      onClick = actions.onToggleAddMenu,
      iconKey = AllIconsKeys.Actions.Attach,
      enabled = !state.isAttachmentLimitReached,
      tooltip =
        if (state.isAttachmentLimitReached) message("gradum.add.context.disabled")
        else message("gradum.add.context")
    )
    if (state.showAddMenu) {
      AddContextPopup(
        state = state,
        actions = actions,
        searchState = searchState,
        filteredFiles = filteredFiles
      )
    }

    PermissionSelector(
      onToggle = actions.onToggleMenu,
      hasSentMessage = hasSentMessage,
      onDismiss = actions.onDismissMenu,
      isMenuVisible = state.isMenuVisible,
      onSelect = actions.onSelectPermission,
      selectedPermission = state.selectedPermission,
      isPermissionLocked = hasSentMessage && PermissionMode.isDebugMode(selectedPermission)
    )

    Spacer(modifier = Modifier.weight(1f))

    IconTooltipButton(
      onClick = actions.onToggleExpanded,
      enabled = state.editorContext.currentFile != null,
      tooltip = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context"),
      iconKey = if (state.isExpanded) AllIconsKeys.Actions.Share else AllIconsKeys.Actions.Unshare,
      contentDescription = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context")
    )

    IconTooltipButton(
      enabled = isTextNotEmpty,
      tooltip = message("gradum.clear"),
      onClick = actions.onClearText,
      contentDescription = message("gradum.delete"),
      iconKey = AllIconsKeys.General.Delete
    )

    Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
      // Hide stop button in debug mode
      if ((state.isSending || state.pendingMessages.isNotEmpty()) && selectedPermission != PermissionMode.DEBUG) {
        IconTooltipButton(
          tooltip = message("gradum.stop"),
          iconKey = AllIconsKeys.Run.Stop,
          contentDescription = message("gradum.stop.response"),
          onClick = actions.onStop
        )
      }
      val hasModel: Boolean = state.selectedModel != null
      val isDebug: Boolean = selectedPermission == PermissionMode.DEBUG
      val canSend: Boolean = isTextNotEmpty && !state.isPendingQueueFull && (hasModel || isDebug)
      val sendTooltip: String = when {
        state.isSending && state.isPendingQueueFull -> message("gradum.send.queue.full")
        !hasModel && !isDebug -> message("gradum.send.no.model")
        else -> message("gradum.send")
      }
      IconTooltipButton(
        enabled = canSend,
        tooltip = sendTooltip,
        onClick = actions.onSend,
        iconKey = GradumIcons.Send,
        contentDescription = message("gradum.send")
      )
    }
  }
}
