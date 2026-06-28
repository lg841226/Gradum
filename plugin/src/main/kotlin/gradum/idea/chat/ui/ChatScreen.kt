/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-06-28 11:13:44 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.chat.ui.chat.AssistantChatBubble
import gradum.idea.chat.ui.chat.MessageTimestamp
import gradum.idea.chat.ui.chat.UserChatBubble
import gradum.idea.chat.ui.input.ChatInputSection
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import java.awt.Desktop
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/**
 * The active conversation screen: scrollable history on top, input pinned
 * to the bottom. Shown after the user has sent at least one message.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    isWaitingForResponse: Boolean,
    textState: TextFieldState,
    inputState: ChatInputState,
    inputActions: ChatInputActions,
    onDeleteMessage: (Int) -> Unit,
    onRetryMessage: (Int) -> Unit,
    onCopyAsContext: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onOpenInEditor: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .width(680.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            messages.forEachIndexed { index, message ->
                val shouldShowTimestamp = index == 0 ||
                        formatTimestamp(message.timestamp) != formatTimestamp(messages.getOrNull(index - 1)?.timestamp ?: 0L)
                val isLastAssistant = index == messages.lastIndex && !message.isUserMessage && isLoading

                if (shouldShowTimestamp) {
                    MessageTimestamp(
                        timestamp = message.timestamp,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
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
                        actionsEnabled = !isWaitingForResponse,
                        onRetry = { onRetryMessage(index) },
                        onUrlClick = { url ->
                            try {
                                Desktop.getDesktop().browse(URI(url))
                            } catch (exception: Exception) {
                                logger.warn("Failed to open URL: $url", exception)
                            }
                        },
                        onOpenInEditor = onOpenInEditor
                    )
                }
            }
            Spacer(Modifier.height(500.dp))
        }

        ChatInputSection(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(),
            state = inputState,
            actions = inputActions,
            textState = textState,
            onRefreshModels = onRefreshModels
        )
    }
}
