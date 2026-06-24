/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatComponents.kt  2026-06-24 21:10:23 Changed by gwy
 */

package gradum.idea

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import gradum.idea.GradumBundle.message
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.Duration.Companion.milliseconds

/**
 * Represents a single message in the chat history.
 *
 * @property role  Identifies the sender — "user" or "assistant".
 * @property content  The message text.
 */
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Scrollable list of chat bubbles with a bottom spacer.
 *
 * When [isLoading] is true the last assistant bubble shows a progress indicator.
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        messages.forEachIndexed { index, message ->
            val isLastAssistant = index == messages.lastIndex && message.role != "user" && isLoading
            when (message.role) {
                "user" -> UserChatBubble(message = message)
                else -> AssistantChatBubble(message = message, isLoading = isLastAssistant)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * Right-aligned user message bubble with copy and reset buttons.
 *
 * Renders inside a rounded rectangle with a border color background.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun UserChatBubble(message: ChatMessage) {
    var isCopied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .background(
                        color = JewelTheme.globalColors.borders.normal,
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd = 0.dp
                        )
                    )
                    .padding(10.dp)
            ) {
                Text(text = message.content)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                CopyButton(
                    message = message,
                    isCopied = isCopied,
                    onCopy = { isCopied = true },
                    onReset = { isCopied = false }
                )
                Spacer(Modifier.width(4.dp))
                Tooltip(tooltip = { Text(message("gradum.reset.tooltip")) }) {
                    IconButton(onClick = {}) {
                        Icon(
                            key = AllIconsKeys.General.Reset,
                            contentDescription = message("gradum.reset")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Left-aligned assistant message bubble with a copy button.
 *
 * Shows a [CircularProgressIndicator] alongside "Generating Answer" text
 * when [isLoading] is true (typically for the most recent assistant message).
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun AssistantChatBubble(message: ChatMessage, isLoading: Boolean = false) {
    var isCopied by remember { mutableStateOf(false) }
    var isSelectedLike by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Spacer(Modifier.height(8.dp))
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    SweepLightText(
                        text = message("gradum.generating"),
                        modifier = Modifier
                    )
                }
            } else
                Text(text = message.content)

            Spacer(Modifier.height(8.dp))
            Row {
                CopyButton(
                    message = message,
                    isCopied = isCopied,
                    onCopy = { isCopied = true },
                    onReset = { isCopied = false }
                )
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

/**
 * Copy button with tooltip.
 *
 * Shows a check icon briefly after clicking, then reverts.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CopyButton(
    message: ChatMessage,
    isCopied: Boolean,
    onCopy: () -> Unit,
    onReset: () -> Unit
) {
    Tooltip(tooltip = { Text(message("gradum.copy.tooltip")) }) {
        IconButton(
            onClick = {
                copyToClipboard(
                    text = message.content,
                    onCopied = onCopy,
                    onReset = onReset
                )
            },
            enabled = message.content.isNotBlank()
        ) {
            Icon(
                key = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy,
                contentDescription = message("gradum.copy")
            )
        }
    }
}

/**
 * Copies [text] to the system clipboard and fires callbacks.
 *
 * @param onCopied  Invoked immediately after the copy succeeds.
 * @param onReset   Invoked after [delayMillis] to revert any visual state.
 */
fun copyToClipboard(
    text: String,
    onCopied: () -> Unit,
    onReset: () -> Unit,
    delayMillis: Long = 1000
) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val stringSelection = StringSelection(text)
    clipboard.setContents(stringSelection, null)

    onCopied()

    MainScope().launch {
        delay(delayMillis.milliseconds); onReset()
    }
}

@Composable
fun SweepLightText(
    text: String,
    modifier: Modifier = Modifier,
    durationMillis: Int = 1200
) {
    val transition = rememberInfiniteTransition(label = "sweep_light")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_offset"
    )

    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            brush = Brush.linearGradient(
                colors = listOf(
                    JewelTheme.globalColors.text.info.copy(alpha = 0.4f),
                    JewelTheme.globalColors.text.normal.copy(alpha = 0.9f),
                    JewelTheme.globalColors.text.info.copy(alpha = 0.4f)
                ),
                start = Offset(offset * 300f, 0f),
                end = Offset(offset * 300f + 300f, 0f)
            )
        )
    )
}
