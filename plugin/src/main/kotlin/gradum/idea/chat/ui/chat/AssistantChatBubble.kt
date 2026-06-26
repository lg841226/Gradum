/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Left-aligned assistant message bubble with a copy button.
 *
 * Shows a [CircularProgressIndicator] alongside "Generating Answer" text
 * when [isLoading] is true (typically for the most recent assistant message).
 */
@Composable
fun AssistantChatBubble(message: ChatMessage, isLoading: Boolean = false, onRetry: () -> Unit = {}, onCopyAsContext: (String) -> Unit = {}) {
    var isCopied by remember { mutableStateOf(false) }
    var isSelectedLike by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(horizontalAlignment = Alignment.Start) {
            Spacer(Modifier.height(8.dp))
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    SweepLightText(text = message("gradum.generating"), modifier = Modifier)
                }
            } else
                Text(text = message.content)

            Spacer(Modifier.height(8.dp))
            Row {
                MessageCopyButton(
                    message = message,
                    isCopied = isCopied,
                    onCopy = { isCopied = true },
                    onReset = { isCopied = false },
                    onCopyAsContext = onCopyAsContext
                )
                Spacer(Modifier.width(4.dp))
                Tooltip(tooltip = { Text(text = message("gradum.reset.tooltip")) }) {
                    IconButton(
                        onClick = onRetry,
                        enabled = !isLoading && message.content.isNotBlank()
                    ) {
                        Icon(key = AllIconsKeys.Actions.Refresh, contentDescription = message("gradum.reset"))
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { isSelectedLike = !isSelectedLike },
                    enabled = message.content.isNotBlank()
                ) {
                    Icon(
                        key = if (isSelectedLike) GradumIcons.LikeSelected else GradumIcons.Like,
                        contentDescription = message("gradum.like")
                    )
                }
            }
        }
    }
}
