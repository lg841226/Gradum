/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-07-10 15:12:55 Changed by gwy
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
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.chat.ui.markdown.InlineMarkdownRender
import gradum.idea.chat.ui.markdown.InlineMarkdownRenderResult
import gradum.idea.chat.ui.markdown.MarkdownSegment
import gradum.idea.chat.ui.markdown.RenderNonProseBlock
import gradum.idea.chat.ui.markdown.ScrollableTable
import gradum.idea.chat.ui.markdown.TableParseFailurePlaceholder
import gradum.idea.chat.ui.markdown.isRenderable
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.chat.ui.markdown.rememberInlineMarkdownRender
import gradum.idea.chat.ui.markdown.splitMarkdown
import gradum.idea.icons.GradumIcons
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private val RISE_DISTANCE_DP: Dp = 24.dp
private const val FADE_IN_MS: Int = 600
private const val PHASE_FADE_MS: Int = 100
private const val PHASE_FADE_IN_MS: Int = 300
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
  onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { _, _, _ -> },
  onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) ->
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
        }
      }
      Spacer(Modifier.height(GradumSpacing.lg))
      renderBlocks.forEachIndexed { index, block ->
        key(block.key(index)) {
          when (block) {
            is RenderBlock.Thinking -> ThinkingBlock(block, isLoading, onUrlClick)
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
          sendingPhase = if (isLoading) sendingPhase else message("gradum.done")
        )
      }

      Spacer(modifier = Modifier.height(GradumSpacing.md))
      MessageActionsRow(
        message = message,
        isLoading = isLoading,
        hasContent = hasContent,
        actionsEnabled = actionsEnabled,
        onRetry = onRetry,
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
private fun ThinkingBlock(block: RenderBlock.Thinking, isLoading: Boolean, onUrlClick: (String) -> Unit) {
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
    onUrlClick = onUrlClick,
    modifier = Modifier.graphicsLayer {
      this.alpha = fadeAlpha.value
    }
  )
}

@Composable
private fun ResponseBlock(
  block: RenderBlock.Response,
  onUrlClick: (String) -> Unit,
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
   *
   * Then each Plain segment is split further on top-level block
   * boundaries via [splitMarkdown] → [splitPlainAtBlocks]. The
   * result is a flat list of [MarkdownSegment.Plain] (paragraph
   * prose — routed to the inline chip parser) /
   * [MarkdownSegment.NonProseBlock] (heading / list / blockquote /
   * fenced code / thematic break — routed to `Markdown(...)` so
   * the block renders normally, just not with custom chip
   * styling) / [MarkdownSegment.Table] (existing scrollable
   * table). Without the block-boundary split, a message that
   * contains a fenced code block (very common) would either bail
   * the whole segment to `Markdown(...)` (no chips anywhere) or
   * — the bug fixed 2026-07-14 — silently drop the non-paragraph
   * blocks, rendering only 20 chars of an 804-char message.
   */
  val segments = remember(block.content) { splitMarkdown(block.content) }
  val paragraphStyle = rememberGradumParagraphTextStyle()
  SelectionContainer(
    modifier = Modifier.graphicsLayer {
      this.alpha = fadeAlpha.value
    }
  ) {
    Column {
      segments.forEach { segment ->
        when (segment) {
          is MarkdownSegment.Plain -> {
            // The Plain sub-segment is guaranteed by
            // [splitPlainAtBlocks] to contain only `Paragraph`
            // blocks. The inline chip parser handles those.
            val outcome: InlineMarkdownRenderResult = rememberInlineMarkdownRender(segment.text)
            if (outcome.render != null) {
              val render: InlineMarkdownRender = outcome.render
              Text(
                text = render.annotated,
                inlineContent = render.inlineContent,
                modifier = Modifier.fillMaxWidth(),
                style = paragraphStyle,
              )
            } else {
              Markdown(
                onUrlClick = onUrlClick,
                markdown = segment.text,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }

          is MarkdownSegment.NonProseBlock -> {
            // Non-prose block (heading / list / blockquote / fenced
            // code / thematic break / html). We can't use
            // `Markdown(...)` here because Jewel's `markdownStyling`
            // renders inline code as a `SpanStyle` (monospace text
            // with a background) — not as the rounded
            // `InlineCodeChip` that the inline parser produces. The
            // user wants the chip EVERYWHERE inline code appears, so
            // we use [RenderNonProseBlock] (BlockRenderer.kt)
            // which walks the CommonMark AST, extracts the inline
            // content of each block, and runs it through the chip
            // parser ([parseInlineMarkdown]). Block-level styling
            // (heading size / weight, list bullet / number,
            // blockquote indent + border) comes from the same
            // [rememberGradumMarkdownStyling] that `Markdown(...)`
            // would have used, so the visual look matches except
            // that inline code now renders as chips.
            RenderNonProseBlock(segment, onUrlClick = onUrlClick)
          }

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
private fun ToolCallBlock(
  block: RenderBlock.ToolCall,
  onOpenInEditor: (path: String, startLine: Int, endLine: Int) -> Unit,
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
  val clipboardScope = rememberCoroutineScope()
  val renderer = gradum.idea.chat.ui.chat.skill.spi.ToolCallRendererRegistry.find(block.alias)
  if (renderer == null) {
    val fallbackToolDetails: String = if (!block.success) {
      gradum.idea.chat.ui.chat.skill.internal.formatToolDetails(
        alias = block.alias,
        arguments = block.arguments,
        result = block.result,
        errorMessage = block.errorMessage,
        errorDetail = block.errorDetail
      )
    } else ""
    ToolCallCapsule(
      success = block.success,
      errorDetail = block.errorDetail,
      errorMessage = block.errorMessage,
      toolDetails = fallbackToolDetails,
      iconKey = AllIconsKeys.Nodes.Plugin,
      label = block.alias,
      modifier = animModifier,
    )
    return
  }
  val content: gradum.idea.chat.ui.chat.skill.spi.ToolCallContent =
    renderer.parseContent(
      arguments = block.arguments,
      result = gradum.idea.chat.ui.chat.skill.spi.parseJsonResult(block.result),
    )
  val toolDetails: String? = if (!block.success) {
    gradum.idea.chat.ui.chat.skill.internal.formatToolDetails(
      alias = block.alias,
      arguments = block.arguments,
      result = block.result,
      errorMessage = block.errorMessage,
      errorDetail = block.errorDetail
    )
  } else null
  val ctx: gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext =
    gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext(
      project = null,
      isError = !block.success,
      errorDetail = block.errorDetail,
      toolDetails = toolDetails,
      onCopy = { payload ->
        copyToClipboard(
          text = payload,
          onCopied = {},
          onReset = {},
          scope = clipboardScope,
        )
      },
      onOpenInEditor = onOpenInEditor,
    ) { filePath, originalContent, modifiedContent ->
      onViewDiff(
        filePath,
        originalContent.orEmpty(),
        modifiedContent.orEmpty(),
      )
    }
  Box(modifier = animModifier) {
    renderer.render(content, ctx)
  }
}

@Composable
private fun TokenStatusRow(
  isLoading: Boolean, sendingPhase: String, tokenCount: Int = 0
) {
  val tokenText = if (tokenCount > 0)
    "$sendingPhase & ${message("gradum.tokens.used", formatTokenCount(tokenCount))}"
  else
    sendingPhase.ifEmpty { "..." }

  val density = LocalDensity.current
  var displayText by remember { mutableStateOf(sendingPhase) }
  var previousText by remember { mutableStateOf(sendingPhase) }
  val fadeAlpha = remember { Animatable(1f) }
  val verticalOffset = remember { Animatable(0f) }
  val riseDistancePx: Float = with(density) { -RISE_DISTANCE_DP.toPx() }

  LaunchedEffect(sendingPhase, tokenText) {
    val newText = tokenText.ifEmpty { sendingPhase }
    if (newText != previousText) {
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
      displayText = newText
      previousText = newText
      launch {
        verticalOffset.animateTo(
          targetValue = 0f,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
          ),
        )
      }
      fadeAlpha.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = PHASE_FADE_IN_MS)
      )
    }
  }

  Row(verticalAlignment = Alignment.CenterVertically) {
    if (isLoading) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(GradumSpacing.sml))
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
  actionsEnabled: Boolean,
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

  val textErrorColor = JewelTheme.globalColors.text.error

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
      color = textErrorColor,
      text = block.message.ifBlank { friendlyErrorMessage(block.code) },
      style = JewelTheme.typography.editorTextStyle.copy(
        color = textErrorColor
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
