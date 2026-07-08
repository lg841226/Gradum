/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-08 15:30:36 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
 * keep the button visible. 48 dp is generous — it lets the user
 * scroll the list a couple of paragraphs up before the button
 * shows, so a small "read the line just above" sweep doesn't pop
 * the pill in. With 24 dp the button felt like it was almost
 * always visible at the top of the input section whenever the
 * user was reading anything except the very last bubble; the wider
 * tolerance keeps the chat calm and reserves the button for the
 * "really scrolled away" case.
 */
private val AtBottomToleranceDp: androidx.compose.ui.unit.Dp = 48.dp

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
 *
 * Layout note — the message column and the input section are siblings
 * inside a vertical Column. The messages column is wrapped in a Box
 * that hosts the floating jump-to-bottom button as an overlay. The
 * button's `Alignment.BottomCenter` anchor lives on that Box, so the
 * button always sits right above the input section regardless of how
 * tall the input grows (multi-line input, attachment chips, model
 * selector row, etc.). A small `GradumSpacing.lg` (12 dp) bottom
 * padding gives the pill breathing room from the input top edge.
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

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The message column is wrapped in a Box so the jump-to-bottom
        // button can overlay it without being measured into the
        // verticalScroll's content height. The button's
        // Alignment.BottomCenter anchor lives on THIS Box, not on
        // the outer Column — which is what keeps the button pinned
        // to the input section's top edge regardless of how tall the
        // input grows (multi-line text, attachment chips, model
        // selector row, etc.). A previous implementation anchored the
        // button to the screen's BottomCenter and offset it by a
        // hardcoded 160.dp, which broke the moment the input section
        // exceeded that budget — the pill ended up *inside* the
        // input box. The Box overlay pattern decouples the two.
        Box(
            modifier = Modifier
                .weight(1f)
                .width(680.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                messages.forEachIndexed { index, message ->
                    val shouldShowTimestamp = index == 0 || formatTimestamp(message.timestamp) !=
                        formatTimestamp(messages.getOrNull(index - 1)?.timestamp ?: 0L)
                    val isLastAssistant = index == messages.lastIndex && !message.isUserMessage && isLoading

                    if (shouldShowTimestamp) {
                        MessageTimestamp(
                            timestamp = message.timestamp,
                            modifier = Modifier.padding(vertical = GradumSpacing.lg)
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

            JumpToBottomButton(
                isVisible = !isAtBottom,
                enabled = !isJumpToBottomInFlight,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = GradumSpacing.lg),
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
}
