/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatToolbar.kt  2026-06-30 23:35:47 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

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
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Toolbar row inside [ChatInputPanel] with add-menu, permission selector, and action buttons.
 */
@Composable
fun ChatToolbar(
    state: ChatInputState,
    actions: ChatInputActions,
    isTextNotEmpty: Boolean,
    modifier: Modifier = Modifier
) {
    val searchState = remember { TextFieldState() }

    val searchQuery: String = searchState.text.toString()
    val filteredFiles = if (searchQuery.isBlank()) {
        state.editorContext.allOpenFiles
    } else {
        state.editorContext.allOpenFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTooltipButton(
            tooltip = if (state.isAttachmentLimitReached) message("gradum.add.context.disabled") else message("gradum.add.context"),
            iconKey = AllIconsKeys.General.Add,
            contentDescription = message("gradum.add"),
            onClick = actions.onToggleAddMenu,
            enabled = !state.isAttachmentLimitReached
        )
        if (state.showAddMenu) {
            AddContextPopup(
                searchState = searchState,
                filteredFiles = filteredFiles,
                state = state,
                actions = actions
            )
        }

        PermissionSelector(
            selectedPermission = state.selectedPermission,
            isMenuVisible = state.isMenuVisible,
            onToggle = actions.onToggleMenu,
            onSelect = actions.onSelectPermission,
            onDismiss = actions.onDismissMenu
        )

        Spacer(modifier = Modifier.weight(1f))

        IconTooltipButton(
            tooltip = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context"),
            iconKey = if (state.isExpanded) AllIconsKeys.Actions.Share else AllIconsKeys.Actions.Unshare,
            contentDescription = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context"),
            onClick = actions.onToggleExpanded
        )

        IconTooltipButton(
            tooltip = message("gradum.clear"),
            iconKey = AllIconsKeys.General.Delete,
            contentDescription = message("gradum.delete"),
            onClick = actions.onClearText,
            enabled = isTextNotEmpty
        )

        Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
            if (state.isSending || state.pendingMessages.isNotEmpty()) {
                IconTooltipButton(
                    tooltip = message("gradum.stop"),
                    iconKey = AllIconsKeys.Run.Stop,
                    contentDescription = message("gradum.stop.response"),
                    onClick = actions.onStop
                )
            }
            val hasModel = state.selectedModel != null || state.isAutoSelected
            val canSend = isTextNotEmpty && !state.isPendingQueueFull && hasModel
            val sendTooltip = when {
                state.isSending && state.isPendingQueueFull -> message("gradum.send.queue.full")
                !hasModel -> message("gradum.send.no.model")
                else -> message("gradum.send")
            }
            IconTooltipButton(
                tooltip = sendTooltip,
                iconKey = GradumIcons.Send,
                contentDescription = message("gradum.send"),
                onClick = actions.onSend,
                enabled = canSend
            )
        }
    }
}
