/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-06-29 18:05:51 Changed by gwy
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
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.rememberGradumMarkdownStyling
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val FADE_IN_MS: Int = 600
private const val AUTO_COLLAPSE_DELAY_MS: Long = 1000L

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
    onOpenInEditor: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val renderBlocks = message.renderBlocks
    val hasContent = message.hasResponse

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(horizontalAlignment = Alignment.Start) {
            renderBlocks.forEachIndexed { index, block ->
                key(block.key(index)) {
                    when (block) {
                        is RenderBlock.Thinking -> ThinkingBlock(block)
                        is RenderBlock.ToolCall -> ToolCallBlock(block, onOpenInEditor)
                        is RenderBlock.Response -> ResponseBlock(block, onUrlClick)
                    }
                    Spacer(modifier = Modifier.height(GradumSpacing.lg))
                }
            }

            if (isLoading) LoadingIndicatorRow()

            Spacer(modifier = Modifier.height(GradumSpacing.lg))
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
    when (val content = ToolCallContent.fromArguments(block.alias, block.arguments, block.result)) {
        is ToolCallContent.Ran -> RanToolCallIndicator(
            alias = block.alias,
            reason = content.reason,
            command = content.command,
            success = block.success,
            modifier = animModifier,
            errorMessage = block.errorMessage,
            errorDetail = block.errorDetail,
            onOpenInEditor = onOpenInEditor
        )

        is ToolCallContent.Edited -> FileToolCallIndicator(
            alias = block.alias,
            path = content.path,
            linesAdded = content.linesAdded,
            linesRemoved = content.linesRemoved,
            success = block.success,
            modifier = animModifier,
            errorMessage = block.errorMessage,
            errorDetail = block.errorDetail,
            onOpenInEditor = onOpenInEditor
        )

        is ToolCallContent.Read -> FileToolCallIndicator(
            alias = block.alias,
            path = content.path,
            success = block.success,
            modifier = animModifier,
            errorMessage = block.errorMessage,
            errorDetail = block.errorDetail,
            onOpenInEditor = onOpenInEditor
        )

        else -> ToolCallIndicator(
            alias = block.alias,
            success = block.success,
            modifier = animModifier,
            errorMessage = block.errorMessage,
            errorDetail = block.errorDetail
        )
    }
}

@Composable
private fun LoadingIndicatorRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
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
        Spacer(modifier = Modifier.width(GradumSpacing.sm))
        Tooltip(tooltip = { Text(text = message("gradum.reset.tooltip")) }) {
            IconButton(
                onClick = onRetry,
                enabled = actionsEnabled && !isLoading && hasContent
            ) {
                Icon(key = AllIconsKeys.Actions.Refresh, contentDescription = message("gradum.reset"))
            }
        }
        Spacer(modifier = Modifier.width(GradumSpacing.sm))
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
