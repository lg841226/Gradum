/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-08 11:07:23 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.chat.ui.chat.AssistantChatBubble
import gradum.idea.chat.ui.chat.MessageTimestamp
import gradum.idea.chat.ui.chat.UserChatBubble
import gradum.idea.chat.ui.input.ChatInputSection
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import java.awt.Desktop
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/**
 * "At bottom" tolerance in dp. The jump-to-bottom button is hidden
 * whenever the user is within this many dp of the list's max scroll
 * value, so a tiny 1-px jitter from a fresh bubble layout doesn't
 * keep the button visible. 24 dp roughly matches a single line of
 * the chat typography — generous enough to feel responsive, tight
 * enough that "really scrolled away" still shows the button.
 */
private val AtBottomToleranceDp: androidx.compose.ui.unit.Dp = 24.dp

/**
 * Bottom-of-Box padding for the floating jump-to-bottom button.
 * Pushes the button up clear of [ChatInputSection]; the section is
 * roughly 110–130 dp tall (input panel + spacer + model selector)
 * and the button needs another 12 dp of breathing room above it.
 */
private val JumpToBottomBottomPadding: androidx.compose.ui.unit.Dp = 160.dp

/**
 * The active conversation screen: scrollable history on top, input pinned
 * to the bottom. Shown after the user has sent at least one message.
 *
 * A [JumpToBottomButton] floats above the input section. It fades in
 * (150 ms) whenever the user is more than 24 dp away from the bottom
 * of the message list, and fades out (150 ms) once they return to the
 * bottom. When the user is already at the bottom, new messages
 * auto-scroll into view — preserving the read-the-latest flow without
 * pulling focus away from older history the user is reading.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    isWaitingForResponse: Boolean,
    sendingPhase: String,
    textState: TextFieldState,
    inputState: ChatInputState,
    inputActions: ChatInputActions,
    onDeleteMessage: (Int) -> Unit,
    onRetryMessage: (Int) -> Unit,
    onCopyAsContext: (String) -> Unit,
    onOpenInEditor: (String) -> Unit = {},
    onViewDiff: (path: String, originalContent: String, modifiedContent: String)
    -> Unit = { _, _, _ -> },
    onAttachmentClick: (VirtualFile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // `lastSeenMessageCount` snapshots the message-list size the last
    // time the user was confirmed to be at the bottom. Any delta
    // above this snapshot is "unread" — counted into the badge while
    // the user is scrolled away, or auto-scrolled into view (and
    // therefore also cleared) when they're already at the bottom.
    val lastSeenMessageCount = remember { mutableIntStateOf(messages.size) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var isJumpToBottomInFlight by remember { mutableStateOf(false) }

    val isAtBottom: Boolean by remember(scrollState, density) {
        derivedStateOf {
            val tolerancePx: Float = with(density) { AtBottomToleranceDp.toPx() }
            val maxValue: Int = scrollState.maxValue

            maxValue == 0 || scrollState.value >= maxValue - tolerancePx
        }
    }

    // When new messages arrive:
    //  - if the user is at the bottom, smooth-scroll the list to keep
    //    the latest message in view (no badge, no interruption)
    //  - if the user has scrolled up, increment the unread badge so
    //    the jump-to-bottom button shows up with a count
    LaunchedEffect(messages.size) {
        val delta: Int = messages.size - lastSeenMessageCount.intValue
        if (delta <= 0) return@LaunchedEffect

        if (isAtBottom) {
            scrollState.animateScrollTo(scrollState.maxValue)
            lastSeenMessageCount.intValue = messages.size
        } else unreadCount += delta
    }

    // When the user returns to the bottom (either manually, by
    // clicking the button, or by a new message arriving while they
    // were already there), reset the unread badge and snapshot the
    // current message count.
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            unreadCount = 0; lastSeenMessageCount.intValue = messages.size
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
                    val shouldShowTimestamp = index == 0 || formatTimestamp(message.timestamp) !=
                        formatTimestamp(messages.getOrNull(index - 1)?.timestamp ?: 0L)
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
                            onCopyAsContext = onCopyAsContext,
                            onAttachmentClick = onAttachmentClick
                        )

                        else -> AssistantChatBubble(
                            message = message,
                            sendingPhase = if (isLastAssistant) sendingPhase else "",
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
                            onOpenInEditor = onOpenInEditor,
                            onViewDiff = onViewDiff
                        )
                    }
                }
                Spacer(modifier = Modifier.height(500.dp))
            }

            ChatInputSection(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(bottom = GradumSpacing.sml),
                state = inputState,
                actions = inputActions,
                textState = textState
            )
        }

        JumpToBottomButton(
            isVisible = !isAtBottom,
            enabled = !isJumpToBottomInFlight,
            onClick = {
                if (isJumpToBottomInFlight) return@JumpToBottomButton
                isJumpToBottomInFlight = true
                scope.launch {
                    try {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    } finally {
                        isJumpToBottomInFlight = false
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = JumpToBottomBottomPadding)
        )
    }
}
