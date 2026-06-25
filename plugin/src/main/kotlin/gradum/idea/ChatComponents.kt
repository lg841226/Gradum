/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatComponents.kt  2026-06-26 01:46:47 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private val TimestampSpacing = 2.dp

/**
 * Represents a single message in the chat history.
 *
 * @property role  Identifies the sender — "user" or "assistant".
 * @property content  The message text.
 * @property attachments  Files frozen onto the message at send time and
 *   rendered under the user bubble by [MessageAttachmentList]. Empty for
 *   assistant messages and for user messages sent without attachments.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val attachments: List<AttachedFile> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val isUserMessage: Boolean get() = role == "user"
}

/**
 * Renders the attachments attached to a chat message as a vertical list
 * of chips under the bubble. Each chip shows the file-type icon and the
 * file name. Chips are intentionally immutable — they cannot be removed
 * after the message has been sent.
 */
@Composable
fun MessageAttachmentList(
    attachments: List<AttachedFile>,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Box(modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            attachments.forEach { attachment ->
                AttachmentChip(attachment = attachment)
            }
        }
    }
}

/**
 * A single chip in [MessageAttachmentList]: a small file-type icon and
 * the file name on a subtle background pill. Visual weight is kept lower
 * than the bubble so the message text remains the focus.
 */
@Composable
private fun AttachmentChip(attachment: AttachedFile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(
                color = JewelTheme.globalColors.borders.normal,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            key = attachment.iconKey,
            contentDescription = attachment.file.fileType.name,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = attachment.file.name
        )
    }
}

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
    onDeleteMessage: (Int) -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        messages.forEachIndexed { index, message ->
            val isLastAssistant = index == messages.lastIndex && !message.isUserMessage && isLoading
            val shouldShowTimestamp = index == 0 ||
                    formatTimestamp(message.timestamp) != formatTimestamp(messages[index - 1].timestamp)
            if (shouldShowTimestamp) {
                Spacer(Modifier.height(TimestampSpacing))
                MessageTimestamp(timestamp = message.timestamp)
                Spacer(Modifier.height(TimestampSpacing))
            }
            when {
                message.isUserMessage -> UserChatBubble(
                    message = message,
                    onDeleteMessage = { onDeleteMessage(index) }
                )

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
@Composable
fun UserChatBubble(message: ChatMessage, onDeleteMessage: () -> Unit = {}) {
    var isCopied by remember { mutableStateOf(false) }
    var isAttachmentsExpanded by remember { mutableStateOf(true) }
    var showResetPopup by remember { mutableStateOf(false) }

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
            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clickable { isAttachmentsExpanded = !isAttachmentsExpanded }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        key = if (isAttachmentsExpanded) AllIconsKeys.General.ChevronDown
                        else AllIconsKeys.General.ChevronRight,
                        contentDescription = null
                    )
                    Text(
                        text = message("gradum.attachments"),
                        color = JewelTheme.globalColors.text.normal
                    )
                }
                Spacer(Modifier.height(4.dp))
                AnimatedVisibility(visible = isAttachmentsExpanded) {
                    MessageAttachmentList(
                        attachments = message.attachments,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    IconButton(onClick = { showResetPopup = true }) {
                        Icon(
                            key = AllIconsKeys.General.Reset,
                            contentDescription = message("gradum.reset")
                        )
                    }
                }
                if (showResetPopup) {
                    PopupMenu(
                        onDismissRequest = { showResetPopup = false; true },
                        horizontalAlignment = Alignment.End
                    ) {
                        passiveItem {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    key = GradumIcons.Warning,
                                    contentDescription = message("gradum.delete.confirm"),
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(message("gradum.delete.confirm"))
                            }
                        }
                        separator()
                        selectableItem(
                            selected = false,
                            onClick = {
                                showResetPopup = false
                                onDeleteMessage()
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    key = AllIconsKeys.General.Reset,
                                    contentDescription = message("gradum.delete.action"),
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(message("gradum.delete.action"))
                            }
                        }
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
@Composable
private fun CopyButton(
    message: ChatMessage,
    isCopied: Boolean,
    onCopy: () -> Unit,
    onReset: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Tooltip(tooltip = { Text(message("gradum.copy.tooltip")) }) {
        IconButton(
            onClick = {
                copyToClipboard(
                    text = message.content,
                    onCopied = onCopy,
                    onReset = onReset,
                    scope = scope
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
    scope: CoroutineScope,
    delayMillis: Long = 1000
) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val stringSelection = StringSelection(text)
    clipboard.setContents(stringSelection, null)

    onCopied()

    scope.launch {
        delay(delayMillis.milliseconds); onReset()
    }
}

/**
 * Formats a timestamp into a human-readable relative string.
 *
 * - Today: "14:30"
 * - Yesterday: "Yesterday 14:30"
 * - This year: "Jun 15"
 * - Older: "15 days ago"
 */
fun formatTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val messageTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    val diffMillis = now.timeInMillis - timestamp
    val diffDays = TimeUnit.MILLISECONDS.toDays(diffMillis)

    return when {
        isSameDay(now, messageTime) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }

        isYesterday(now, messageTime) -> {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            "${message("gradum.timestamp.yesterday")} $time"
        }

        now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }

        else -> {
            "$diffDays ${message("gradum.timestamp.days.ago")}"
        }
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, target: Calendar): Boolean {
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return isSameDay(yesterday, target)
}

/**
 * Centered timestamp separator between message groups.
 */
@Composable
fun MessageTimestamp(timestamp: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatTimestamp(timestamp),
            style = JewelTheme.typography.medium,
            color = JewelTheme.globalColors.text.info
        )
    }
}

/**
 * Renders [text] with a "shimmer" overlay that sweeps a translucent linear
 * gradient horizontally across the glyphs, signaling that work is in
 * progress (e.g. the assistant bubble while a response is streaming).
 *
 * The animation drives a single `offset` from -1f to 2f over
 * [durationMillis] with linear easing and infinite restart. The offset is
 * mapped to the start/end x-coordinates of a 3-stop alpha gradient
 * (low → high → low), so the highlight travels left-to-right and
 * seamlessly re-enters from the left on the next cycle. The underlying
 * `Text` re-lays out its `Brush` on every frame.
 *
 * @param text  The string to render.
 * @param modifier  Compose modifier forwarded to the underlying `Text`.
 * @param durationMillis  Length of one full left-to-right sweep; default 1200ms.
 */
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
