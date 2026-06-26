/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.ui.chat.ChatMessageList
import gradum.idea.chat.ui.input.ChatInputSection
import gradum.idea.chat.model.ChatMessage
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * The active conversation screen: scrollable history on top, input pinned
 * to the bottom. Shown after the user has sent at least one message.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    textState: TextFieldState,
    inputState: ChatInputState,
    inputActions: ChatInputActions,
    onDeleteMessage: (Int) -> Unit,
    onCopyAsContext: (String) -> Unit,
    onRefreshModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ChatMessageList(
            messages = messages,
            isLoading = isLoading,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onDeleteMessage = onDeleteMessage,
            onCopyAsContext = onCopyAsContext
        )
        ChatInputSection(
            modifier = Modifier.widthIn(max = 600.dp),
            state = inputState,
            actions = inputActions,
            textState = textState,
            onRefreshModels = onRefreshModels
        )
    }
}
