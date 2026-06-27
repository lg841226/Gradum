/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-06-27 18:21:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.ui.rememberGradumMarkdownStyling
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val CONTENT_FADE_IN_MS: Int = 1000

/**
 * Left-aligned assistant message bubble with a copy button.
 *
 * Wraps the Markdown content with [AnimatedVisibility] and [fadeIn]
 * for a smooth fade-in appearance during streaming.
 */
@Composable
fun AssistantChatBubble(message: ChatMessage, isLoading: Boolean = false, onRetry: () -> Unit = {}, onCopyAsContext: (String) -> Unit = {}) {
    var isCopied by remember { mutableStateOf(false) }
    var isSelectedLike by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(horizontalAlignment = Alignment.Start) {
            Spacer(Modifier.height(8.dp))
            if (message.thinking.isNotBlank()) {
                ThinkingIndicator(thinking = message.thinking)
                Spacer(Modifier.height(4.dp))
            }
            AnimatedVisibility(
                visible = message.content.isNotBlank(),
                enter = fadeIn(animationSpec = tween(durationMillis = CONTENT_FADE_IN_MS))
            ) {
                SelectionContainer {
                    Markdown(
                        markdown = message.content,
                        markdownStyling = rememberGradumMarkdownStyling(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    SweepLightText(text = message("gradum.generating"), modifier = Modifier)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row {
                MessageCopyButton(
                    message = message,
                    isCopied = isCopied,
                    onCopy = { isCopied = true },
                    onReset = { isCopied = false }
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
