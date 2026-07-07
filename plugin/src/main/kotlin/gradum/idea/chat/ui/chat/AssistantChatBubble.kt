/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-07-07 15:54:43 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ErrorCode
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.*
import gradum.idea.chat.ui.GradumSpacing.sml
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.icons.GradumIcons
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.extensions.markdownBlockRenderer
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val FADE_IN_MS: Int = 600
private const val PHASE_FADE_MS: Int = 100
private const val PHASE_FADE_IN_MS: Int = 300
private val RISE_DISTANCE_DP: Dp = 24.dp
private const val RISE_DURATION_MS: Int = 300

/**
 * Left-aligned assistant message bubble.
 *
 * Renders events in order to preserve the conversation flow:
 * thinking, tool calls, responses, and errors appear in the sequence they occurred.
 */
@Composable
fun AssistantChatBubble(
  message: ChatMessage,
  sendingPhase: String = "",
  modifier: Modifier = Modifier,
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

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start
  ) {
    Column(horizontalAlignment = Alignment.Start) {
      if (message.modelName.isNotBlank()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(sml)
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
          Spacer(modifier = Modifier.height(GradumSpacing.lrl))
        }
      }

      if (isLoading || (message.tokenUsage?.totalTokens ?: 0) > 0) {
        Spacer(Modifier.height(GradumSpacing.md))
        TokenStatusRow(
          isLoading = isLoading,
          tokenCount = message.tokenUsage?.totalTokens ?: 0,
          phase = if (isLoading) sendingPhase else message("gradum.done")
        )
      }

      Spacer(modifier = Modifier.height(GradumSpacing.md))
      MessageActionsRow(
        message = message,
        isLoading = isLoading,
        hasContent = hasContent,
        actionsEnabled = actionsEnabled,
        onRetry = onRetry
      )
      Spacer(Modifier.height(GradumSpacing.xxl))
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
  val fadeAlpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    fadeAlpha.animateTo(
      targetValue = 1f,
      animationSpec = tween(durationMillis = FADE_IN_MS)
    )
  }
  ThinkingIndicator(
    thinking = block.content,
    isTaskComplete = !isLoading,
    modifier = Modifier.graphicsLayer {
      this.alpha = fadeAlpha.value
    }
  )
}

@Composable
private fun ResponseBlock(
  block: RenderBlock.Response,
  onUrlClick: (String) -> Unit
) {
  val fadeAlpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    fadeAlpha.animateTo(
      targetValue = 1f,
      animationSpec = tween(durationMillis = FADE_IN_MS)
    )
  }
  /**
   * Pull GFM tables out of the raw Markdown BEFORE handing the
   * rest to `Markdown(...)`. Tables are rendered as plain Compose
   * inside a horizontally scrollable Box (see [ScrollableTable]),
   * which gives a wide table its own horizontal scrollbar without
   * also scrolling the surrounding prose. Trying to wrap the
   * whole `Markdown(...)` in `Box.horizontalScroll(...)` instead
   * had the side effect of making long inline code / URLs
   * horizontally scrollable too — undesirable in a chat panel.
   */
  val segments = remember(block.content) { splitMarkdownAtTables(block.content) }
  SelectionContainer(
    modifier = Modifier.graphicsLayer {
      this.alpha = fadeAlpha.value
    }
  ) {
    Column {
      segments.forEach { segment ->
        when (segment) {
          is MarkdownSegment.Plain -> Markdown(
            onUrlClick = onUrlClick,
            markdown = segment.text,
            modifier = Modifier.fillMaxWidth(),
            markdownStyling = rememberGradumMarkdownStyling(),
            // Jewel's Markdown defaults blockRenderer to a new instance, not the Local.
            // Extensions like GFM Tables would be silently ignored. Read from Local explicitly.
            blockRenderer = JewelTheme.markdownBlockRenderer,
          )

          is MarkdownSegment.Table -> {
            // Skip rendering if the table has no body content. Show placeholder instead.
            if (segment.isRenderable()) {
              ScrollableTable(segment, onUrlClick = onUrlClick)
            } else {
              TableParseFailurePlaceholder()
            }
          }
        }
      }
    }
  }
}

/**
 * Renders the parse-failure placeholder for a [MarkdownSegment.Table]
 * that the caller has determined to be unrenderable
 * ([MarkdownSegment.Table.isRenderable] is `false`). The chat bubble
 * substitutes this for any `Table` whose body is empty / blank — the
 * raw pipe syntax of the original Markdown block is not surfaced
 * here, since it's visually noisy and uninformative.
 */
@Composable
private fun TableParseFailurePlaceholder(modifier: Modifier = Modifier) {
  val globalColors = LocalGlobalColors.current
  Text(
    text = message("gradum.markdown.table.parse.failed"),
    style = JewelTheme.typography.regular,
    color = globalColors.text.disabled,
    modifier = modifier.padding(vertical = GradumSpacing.sm)
  )
}

@Composable
private fun ToolCallBlock(
  block: RenderBlock.ToolCall,
  onOpenInEditor: (String) -> Unit,
  onViewDiff:
    (path: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> }
) {
  val fadeAlpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    fadeAlpha.animateTo(
      targetValue = 1f,
      animationSpec = tween(durationMillis = FADE_IN_MS)
    )
  }
  val animModifier = Modifier.graphicsLayer { this.alpha = fadeAlpha.value }
  val toolContent = ToolCallContent.fromArguments(block.alias, block.arguments, block.result)
  when (toolContent) {
    is ToolCallContent.Ran -> RanToolCallIndicator(
      alias = block.alias,
      reason = toolContent.reason,
      command = toolContent.command,
      success = block.success,
      modifier = animModifier,
      errorMessage = block.errorMessage,
      errorDetail = block.errorDetail,
      onOpenInEditor = onOpenInEditor
    )

    is ToolCallContent.Edited -> {
      val hasDiffPayload = toolContent.originalContent != null && toolContent.modifiedContent != null
      FileToolCallIndicator(
        alias = block.alias,
        path = toolContent.path,
        success = block.success,
        modifier = animModifier,
        errorMessage = block.errorMessage,
        errorDetail = block.errorDetail,
        linesAdded = toolContent.linesAdded,
        linesRemoved = toolContent.linesRemoved,
        onOpenInEditor = onOpenInEditor,
        onViewDiff = {
          onViewDiff(
            toolContent.path,
            toolContent.originalContent!!,
            toolContent.modifiedContent!!
          )
        },
        hasDiffPayload = hasDiffPayload
      )
    }

    is ToolCallContent.Read -> FileToolCallIndicator(
      alias = block.alias,
      path = toolContent.path,
      success = block.success,
      modifier = animModifier,
      errorMessage = block.errorMessage,
      errorDetail = block.errorDetail,
      onOpenInEditor = onOpenInEditor
    )

    is ToolCallContent.Saved -> FileToolCallIndicator(
      alias = block.alias,
      path = toolContent.path,
      sizeText = if (toolContent.sizeBytes > 0L) formatBytes(toolContent.sizeBytes) else null,
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
private fun TokenStatusRow(
  isLoading: Boolean, phase: String, tokenCount: Int = 0
) {
  val tokenText = if (tokenCount > 0) {
    "$phase & ${message("gradum.tokens.used", formatTokenCount(tokenCount))}"
  } else {
    phase.ifEmpty { "..." }
  }
  var displayText by remember { mutableStateOf(phase) }
  var previousText by remember { mutableStateOf(phase) }
  val fadeAlpha = remember { Animatable(1f) }
  val verticalOffset = remember { Animatable(0f) }
  val density = LocalDensity.current
  val riseDistancePx: Float = with(density) { -RISE_DISTANCE_DP.toPx() }

  LaunchedEffect(phase, tokenText) {
    val newText = tokenText.ifEmpty { phase }
    if (newText != previousText) {
      // Phase 1 — old text rises and fades out in parallel. The rise is a
      // straight tween (no spring) so the lift feels intentional, not
      // physics-y; the bounce is reserved for the drop.
      launch {
        verticalOffset.animateTo(
          targetValue = riseDistancePx,
          animationSpec = tween(
            durationMillis = RISE_DURATION_MS,
            easing = FastOutSlowInEasing,
          ),
        )
      }
      fadeAlpha.animateTo(0f, tween(durationMillis = PHASE_FADE_MS))
      // Phase 2 — swap text at the apex. With alpha = 0 the swap is
      // invisible; the new glyph is "revealed" by the fade-in below.
      displayText = newText
      previousText = newText
      // Phase 3 — new text drops and bounces. A spring from above the
      // resting line to 0 with medium-bouncy damping overshoots below 0
      // (the "ground" the user described), bounces back, overshoots again,
      // and settles — visible 2–3 oscillations. Medium-low stiffness slows
      // the fall so the bounce reads as intentional, not snappy.
      launch {
        verticalOffset.animateTo(
          targetValue = 0f,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
          ),
        )
      }
      // Phase 4 — overlay a subtle fade-in during the drop so the new
      // text "materializes" while it's still in the air.
      fadeAlpha.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = PHASE_FADE_IN_MS)
      )
    }
  }

  Row(verticalAlignment = Alignment.CenterVertically) {
    if (isLoading) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(sml))
    }
    SweepLightText(
      text = displayText,
      enabled = isLoading,
      modifier = Modifier.graphicsLayer {
        translationY = verticalOffset.value
        this.alpha = fadeAlpha.value
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
  var isSelectedDislike by remember { mutableStateOf(false) }

  Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
    MessageCopyButton(
      message = message,
      isCopied = isCopied,
      onCopy = { isCopied = true },
      onReset = { isCopied = false }
    )
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
    IconButton(
      enabled = hasContent,
      onClick = {
        isSelectedLike = !isSelectedLike
        if (isSelectedLike) isSelectedDislike = false
      }
    ) {
      Icon(
        contentDescription = message("gradum.like"),
        key = if (isSelectedLike) GradumIcons.LikeSelected else GradumIcons.Like
      )
    }
    IconButton(
      enabled = hasContent,
      onClick = {
        isSelectedDislike = !isSelectedDislike
        if (isSelectedDislike) isSelectedLike = false
      }
    ) {
      Icon(
        contentDescription = message("gradum.dislike"),
        key = if (isSelectedDislike) GradumIcons.DislikeSelected else GradumIcons.Dislike
      )
    }
  }
}

@Composable
private fun ErrorBlock(block: RenderBlock.Error) {
  val isInterrupted = block.code == ErrorCode.INTERRUPTED.code
  if (isInterrupted) return

  val textColor = JewelTheme.globalColors.text.error

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
  ) {
    Icon(
      contentDescription = null,
      key = AllIconsKeys.Status.FailedInProgress
    )
    Text(
      maxLines = 1,
      color = textColor,
      text = block.message.ifBlank { friendlyErrorMessage(block.code) },
      style = JewelTheme.typography.editorTextStyle.copy(
        color = textColor
      )
    )
  }
}

private fun formatTokenCount(count: Int): String {
  return when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
  }
}
