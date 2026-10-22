package gradum.idea.chat.ui.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.ui.common.ContextUsageRing
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.common.formatTokenCount
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Toolbar row inside [ChatInputPanel] with add-menu, permission selector, and action buttons. */
@OptIn(ExperimentalFoundationApi::class)
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
      tooltip =
        if (state.isAttachmentLimitReached) message("gradum.add.context.disabled")
        else message("gradum.add.context"),
      iconKey = AllIconsKeys.Actions.Attach,
      onClick = actions.onToggleAddMenu,
      enabled = !state.isAttachmentLimitReached,
      contentDescription = message("gradum.add")
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

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
    ) {
      Tooltip(
        tooltip = {
          Text(
            text = message(
              "gradum.tokens.ring.tooltip",
              formatTokenCount(state.sessionContextTokens),
              formatTokenCount(
                state.selectedModel?.contextLimit?.takeIf { it > 0 }
                  ?: ModelInfo.DEFAULT_CONTEXT_LIMIT
              )
            )
          )
        }
      ) {
        ContextUsageRing(
          usedTokens = state.sessionContextTokens,
          contextLimit = state.selectedModel?.contextLimit?.takeIf { it > 0 }
            ?: ModelInfo.DEFAULT_CONTEXT_LIMIT
        )
      }
      Spacer(modifier = Modifier.width(4.dp))
      Box(
        modifier = Modifier
          .width(1.dp)
          .height(16.dp)
          .background(color = JewelTheme.globalColors.borders.normal)
      )
      IconTooltipButton(
        tooltip = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context"),
        iconKey = if (state.isExpanded) AllIconsKeys.Actions.Share else AllIconsKeys.Actions.Unshare,
        onClick = actions.onToggleExpanded,
        enabled = state.editorContext.currentFile != null,
        contentDescription = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context")
      )
    }

    IconTooltipButton(
      tooltip = message("gradum.clear"),
      iconKey = AllIconsKeys.General.Delete,
      onClick = actions.onClearText,
      enabled = isTextNotEmpty,
      contentDescription = message("gradum.delete")
    )

    Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
      // Hide stop button in debug mode
      if ((state.isSending || state.pendingMessages.isNotEmpty()) && selectedPermission != PermissionMode.DEBUG) {
        IconTooltipButton(
          tooltip = message("gradum.stop"),
          iconKey = AllIconsKeys.Run.Stop,
          onClick = actions.onStop,
          contentDescription = message("gradum.stop.response")
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
        tooltip = sendTooltip,
        iconKey = GradumIcons.Send,
        onClick = actions.onSend,
        enabled = canSend,
        contentDescription = message("gradum.send")
      )
    }
  }
}
