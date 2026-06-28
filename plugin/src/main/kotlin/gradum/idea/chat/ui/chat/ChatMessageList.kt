/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatMessageList.kt  2026-06-28 11:13:44 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.formatTimestamp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

private val TimestampSpacing = 2.dp

/**
 * Scrollable list of chat bubbles with a bottom spacer.
 *
 * When [isLoading] is true the last assistant bubble shows a progress indicator.
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onDeleteMessage: (Int) -> Unit = {},
    onRetryMessage: (Int) -> Unit = {},
    onCopyAsContext: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        messages.forEachIndexed { index, message ->
            val isLastAssistant: Boolean = index == messages.lastIndex && !message.isUserMessage && isLoading
            val shouldShowTimestamp: Boolean = index == 0 ||
                    formatTimestamp(message.timestamp) != formatTimestamp(messages[index - 1].timestamp)
            if (shouldShowTimestamp) {
                Spacer(Modifier.height(TimestampSpacing))
                MessageTimestamp(timestamp = message.timestamp)
                Spacer(Modifier.height(TimestampSpacing))
            }
            when {
                message.isUserMessage -> UserChatBubble(
                    message = message,
                    onDeleteMessage = { onDeleteMessage(index) },
                    onCopyAsContext = onCopyAsContext
                )

                else -> AssistantChatBubble(
                    message = message,
                    isLoading = isLastAssistant,
                    onRetry = { onRetryMessage(index) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
