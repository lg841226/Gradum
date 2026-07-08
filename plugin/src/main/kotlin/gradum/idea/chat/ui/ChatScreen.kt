/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-08 15:30:36 Changed by gwy
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

    LaunchedEffect(messages.size) {
        val delta: Int = messages.size - lastSeenMessageCount.intValue
        if (delta <= 0) return@LaunchedEffect

        // We can't use `messages.lastOrNull()?.isUserMessage` to detect
        // a user send: the caller always appends a user message AND an
        // empty assistant placeholder in the same frame, so
        // `lastOrNull()` is the (non-user) assistant bubble and the
        // "isUserSend" check is always false. Instead, scan the newly
        // appended slice for any user-role message — that's the real
        // "user just hit send" signal.
        val newMessages: List<ChatMessage> =
            messages.subList(lastSeenMessageCount.intValue, messages.size)
        val hasUserSend: Boolean = newMessages.any { it.isUserMessage }

        if (hasUserSend) {
            // User just hit send — always follow to the bottom regardless
            // of the current scroll position, so the user sees their own
            // message and the assistant's response without having to
            // manually click "jump to bottom".
            //
            // We must defer one frame before reading scrollState.maxValue:
            // this effect runs in the same recomposition pass that adds
            // the new message to the Column, but the Column's layout pass
            // (which is what updates scrollState.maxValue) hasn't happened
            // yet. Without this wait, maxValue is the *old* value and
            // animateScrollTo lands short of the new bottom.
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
            lastSeenMessageCount.intValue = messages.size
        } else if (isAtBottom) {
            // Assistant message arrived while the user is already at the
            // bottom — smooth-scroll to keep the latest message in view.
            // Same one-frame defer as above: maxValue only catches up
            // after the new bubble is laid out.
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
            lastSeenMessageCount.intValue = messages.size
        } else {
            // Assistant message arrived while the user is reading history —
            // bump the unread count and let the jump-to-bottom button show it.
            unreadCount += delta
        }
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
                            onViewDiff = onViewDiff,
                            isLoading = isLastAssistant,
                            onOpenInEditor = onOpenInEditor,
                            actionsEnabled = !isWaitingForResponse,
                            sendingPhase = if (isLastAssistant) sendingPhase else "",
                            onRetry = { onRetryMessage(index) },
                            onUrlClick = { url ->
                                try {
                                    Desktop.getDesktop().browse(URI(url))
                                } catch (exception: Exception) {
                                    logger.warn("Failed to open URL: $url", exception)
                                }
                            },
                        )
                    }
                }
            }

            ChatInputSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 650.dp)
                    .padding(bottom = GradumSpacing.sml),
                state = inputState,
                actions = inputActions,
                textState = textState
            )
        }

        JumpToBottomButton(
            isVisible = !isAtBottom,
            enabled = !isJumpToBottomInFlight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = JumpToBottomBottomPadding),
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
            }
        )
    }
}
