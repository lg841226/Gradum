/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-07-03 23:16:43 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ErrorCode
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.chat.ui.rememberGradumMarkdownStyling
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val FADE_IN_MS: Int = 600
private const val PHASE_FADE_MS: Int = 100

/**
 * Left-aligned assistant message bubble.
 *
 * Renders events in order to preserve the conversation flow:
 * thinking, tool calls, responses, and errors appear in the sequence they occurred.
 */
@Composable
fun AssistantChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    sendingPhase: String = "",
    isLoading: Boolean = false,
    actionsEnabled: Boolean = true,
    onRetry: () -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onOpenInEditor: (String) -> Unit = {},
    onViewDiff: (path: String, originalContent: String, modifiedContent: String) ->
    Unit = { _, _, _ -> }
) {
    val renderBlocks = message.renderBlocks
    val hasContent = renderBlocks.isNotEmpty()

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(horizontalAlignment = Alignment.Start) {
            if (message.modelName.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
                ) {
                    Icon(
                        contentDescription = null,
                        key = GradumIcons.ColorLogo
                    )
                    Text(
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        text = formatModelName(message.modelName)
                    )
                    val tokenUsage = message.tokenUsage
                    if (tokenUsage != null && tokenUsage.totalTokens > 0) {
                        Spacer(modifier = Modifier.width(GradumSpacing.sm))
                        TokenUsageBadge(totalTokens = tokenUsage.totalTokens)
                    }
                }
            }
            Spacer(Modifier.height(GradumSpacing.lg))
            renderBlocks.forEachIndexed { index, block ->
                key(block.key(index)) {
                    when (block) {
                        is RenderBlock.Thinking -> ThinkingBlock(block, isLoading)
                        is RenderBlock.ToolCall -> ToolCallBlock(block, onOpenInEditor, onViewDiff)
                        is RenderBlock.Response -> ResponseBlock(block, onUrlClick)
                        is RenderBlock.Error -> ErrorBlock(block)
                    }
                    Spacer(modifier = Modifier.height(GradumSpacing.lg))
                }
            }

            if (isLoading) {
                Spacer(Modifier.height(GradumSpacing.md))
                LoadingIndicatorRow(sendingPhase)
            }

            Spacer(modifier = Modifier.height(GradumSpacing.md))
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
private fun ThinkingBlock(block: RenderBlock.Thinking, isLoading: Boolean) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FADE_IN_MS)
        )
    }
    ThinkingIndicator(
        thinking = block.content,
        isTaskComplete = !isLoading,
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
        }
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
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
        }
    ) {
        Markdown(
            onUrlClick = onUrlClick,
            markdown = block.content,
            modifier = Modifier.fillMaxWidth(),
            markdownStyling = rememberGradumMarkdownStyling()
        )
    }
}

@Composable
private fun ErrorBlock(block: RenderBlock.Error) {
    val isInterrupted = block.code == ErrorCode.INTERRUPTED.code

    val iconKey = if (isInterrupted)
        GradumIcons.Warning
    else AllIconsKeys.Status.FailedInProgress

    val textColor = if (isInterrupted) JewelTheme.globalColors.text.normal
    else JewelTheme.globalColors.text.error

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Icon(
            key = iconKey,
            contentDescription = null
        )
        Text(
            maxLines = 1,
            color = textColor,
            text = friendlyErrorMessage(block.code),
            style = JewelTheme.typography.editorTextStyle
        )
    }
}

@Composable
private fun ToolCallBlock(
    block: RenderBlock.ToolCall,
    onOpenInEditor: (String) -> Unit,
    onViewDiff: (path: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> }
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FADE_IN_MS)
        )
    }
    val animModifier = Modifier.graphicsLayer { this.alpha = alpha.value }
    val content = ToolCallContent.fromArguments(block.alias, block.arguments, block.result)
    when (content) {
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

        is ToolCallContent.Edited -> {
            val hasDiffPayload = content.originalContent != null && content.modifiedContent != null
            FileToolCallIndicator(
                alias = block.alias,
                path = content.path,
                linesAdded = content.linesAdded,
                linesRemoved = content.linesRemoved,
                success = block.success,
                modifier = animModifier,
                errorMessage = block.errorMessage,
                errorDetail = block.errorDetail,
                onOpenInEditor = onOpenInEditor,
                onViewDiff = { onViewDiff(content.path, content.originalContent!!, content.modifiedContent!!) },
                hasDiffPayload = hasDiffPayload
            )
        }

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
private fun LoadingIndicatorRow(phase: String = message("gradum.generating")) {
    val text = phase.ifBlank { message("gradum.generating") }
    var displayText by remember { mutableStateOf(text) }
    var previousText by remember { mutableStateOf(text) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(text) {
        if (text != previousText) {
            alpha.animateTo(0f, tween(PHASE_FADE_MS))
            displayText = text
            previousText = text
            alpha.animateTo(1f, tween(PHASE_FADE_MS))
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        SweepLightText(
            text = displayText,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha.value
            }
        )
    }
}

@Composable
private fun MessageActionsRow(
    onRetry: () -> Unit,
    message: ChatMessage,
    isLoading: Boolean,
    hasContent: Boolean,
    actionsEnabled: Boolean
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
                Icon(
                    contentDescription = message("gradum.reset"),
                    key = AllIconsKeys.Actions.Refresh
                )
            }
        }
        Spacer(modifier = Modifier.width(GradumSpacing.sm))
        IconButton(
            enabled = hasContent,
            onClick = { isSelectedLike = !isSelectedLike }
        ) {
            Icon(
                contentDescription = message("gradum.like"),
                key = if (isSelectedLike) GradumIcons.LikeSelected else GradumIcons.Like
            )
        }
    }
}
