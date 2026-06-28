/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-06-28 11:34:43 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.ui.rememberGradumMarkdownStyling
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val FADE_IN_MS: Int = 600
private const val AUTO_COLLAPSE_DELAY_MS: Long = 1200L
private const val BLOCK_GAP_DP: Int = 12

/** Pre-aggregated render blocks for stable animation keys. */
private sealed class RenderBlock {
    data class Thinking(val content: String) : RenderBlock()
    data class ToolCall(val alias: String, val success: Boolean, val content: ToolCallContent) : RenderBlock()
    data class Response(val content: String) : RenderBlock()
    data class Error(val message: String) : RenderBlock()
}

/** Coalesces consecutive events of the same type into stable render blocks. */
private fun aggregateRenderBlocks(events: List<ChatEvent>): List<RenderBlock> = buildList {
    var index = 0
    while (index < events.size) {
        when (val event = events[index]) {
            is ChatEvent.Thinking -> {
                val content = StringBuilder(event.content)
                index++
                while (index < events.size && events[index] is ChatEvent.Thinking) {
                    content.append((events[index] as ChatEvent.Thinking).content)
                    index++
                }
                add(RenderBlock.Thinking(content.toString()))
            }

            is ChatEvent.ToolCall -> {
                val content = ToolCallContent.fromArguments(event.info.alias, event.info.arguments)
                add(RenderBlock.ToolCall(event.info.alias, event.info.success, content))
                index++
            }

            is ChatEvent.Response -> {
                val content = StringBuilder(event.content)
                index++
                while (index < events.size && events[index] is ChatEvent.Response) {
                    content.append((events[index] as ChatEvent.Response).content)
                    index++
                }
                add(RenderBlock.Response(content.toString()))
            }

            is ChatEvent.Error -> {
                add(RenderBlock.Error(event.message))
                index++
            }
        }
    }
}

/**
 * Left-aligned assistant message bubble.
 *
 * Renders events in order to preserve the conversation flow:
 * thinking, tool calls, responses, and errors appear in the sequence they occurred.
 */
@Composable
fun AssistantChatBubble(
    message: ChatMessage,
    isLoading: Boolean = false,
    actionsEnabled: Boolean = true,
    onRetry: () -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onOpenInEditor: (String) -> Unit = {}
) {
    val events = message.events
    val hasContent = events.any { it is ChatEvent.Response }
    val renderBlocks = remember(events) { aggregateRenderBlocks(events) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(horizontalAlignment = Alignment.Start) {
            renderBlocks.forEachIndexed { index, block ->
                key(block.key(index)) {
                    when (block) {
                        is RenderBlock.Thinking -> ThinkingBlock(block)
                        is RenderBlock.ToolCall -> ToolCallBlock(block, onOpenInEditor)
                        is RenderBlock.Response -> ResponseBlock(block, onUrlClick)
                        is RenderBlock.Error -> ErrorBlock(block)
                    }
                    Spacer(Modifier.height(BLOCK_GAP_DP.dp))
                }
            }

            if (isLoading) LoadingIndicatorRow()

            Spacer(Modifier.height(BLOCK_GAP_DP.dp))
            MessageActionsRow(
                message = message,
                isLoading = isLoading,
                hasContent = hasContent,
                actionsEnabled = actionsEnabled,
                onRetry = onRetry
            )
        }
    }
}

private fun RenderBlock.key(index: Int): String = when (this) {
    is RenderBlock.Thinking -> "thinking_$index"
    is RenderBlock.ToolCall -> "tool_${index}_$alias"
    is RenderBlock.Response -> "response_$index"
    is RenderBlock.Error -> "error_$index"
}

@Composable
private fun ThinkingBlock(block: RenderBlock.Thinking) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FADE_IN_MS)
        )
    }
    ThinkingIndicator(
        thinking = block.content,
        autoCollapseDelay = AUTO_COLLAPSE_DELAY_MS,
        modifier = Modifier.graphicsLayer { this.alpha = alpha.value }
    )
}

@Composable
private fun ResponseBlock(
    block: RenderBlock.Response,
    onUrlClick: (String) -> Unit
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FADE_IN_MS)
        )
    }
    SelectionContainer(
        modifier = Modifier.graphicsLayer { this.alpha = alpha.value }
    ) {
        Markdown(
            markdown = block.content,
            markdownStyling = rememberGradumMarkdownStyling(),
            onUrlClick = onUrlClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToolCallBlock(
    block: RenderBlock.ToolCall,
    onOpenInEditor: (String) -> Unit
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FADE_IN_MS)
        )
    }
    val animModifier = Modifier.graphicsLayer { this.alpha = alpha.value }
    when (block.content) {
        is ToolCallContent.Ran -> RanToolCallIndicator(
            reason = block.content.reason,
            command = block.content.command,
            success = block.success,
            modifier = animModifier,
            onOpenInEditor = onOpenInEditor
        )
        else -> ToolCallIndicator(
            alias = block.alias,
            success = block.success,
            modifier = animModifier
        )
    }
}

@Composable
private fun ErrorBlock(@Suppress("UNUSED_PARAMETER") block: RenderBlock.Error) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            key = AllIconsKeys.General.Error,
            contentDescription = null,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = message("gradum.tool.failed"),
            color = JewelTheme.globalColors.text.error
        )
    }
}

@Composable
private fun LoadingIndicatorRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        SweepLightText(
            text = message("gradum.generating"),
            modifier = Modifier
        )
    }
}

@Composable
private fun MessageActionsRow(
    message: ChatMessage,
    isLoading: Boolean,
    hasContent: Boolean,
    actionsEnabled: Boolean,
    onRetry: () -> Unit
) {
    var isCopied by remember { mutableStateOf(false) }
    var isSelectedLike by remember { mutableStateOf(false) }

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
                enabled = actionsEnabled && !isLoading && hasContent
            ) {
                Icon(key = AllIconsKeys.Actions.Refresh, contentDescription = message("gradum.reset"))
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(
            onClick = { isSelectedLike = !isSelectedLike },
            enabled = hasContent
        ) {
            Icon(
                key = if (isSelectedLike) GradumIcons.LikeSelected else GradumIcons.Like,
                contentDescription = message("gradum.like")
            )
        }
    }
}
