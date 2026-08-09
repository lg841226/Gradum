/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-08-07 16:04:18 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ErrorCode
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.chat.ui.markdown.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import kotlin.time.Duration.Companion.milliseconds

private val SEGMENT_RISE_DP: Dp = 8.dp
private val RISE_DISTANCE_DP: Dp = 24.dp
private const val FADE_IN_MS: Int = 600
private const val PHASE_FADE_MS: Int = 100
private const val PHASE_FADE_IN_MS: Int = 300
private const val RISE_DURATION_MS: Int = 300

/** Pacing interval between blocks in the shared serial reveal queue. */
private const val BLOCK_REVEAL_STAGGER_MS: Long = 120

private const val SEGMENT_BASE_DURATION_MS: Int = 150
private const val SEGMENT_EXTRA_PER_100DP_MS: Int = 50
private const val SEGMENT_MAX_EXTRA_MS: Int = 400

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
  onRetry: () -> Unit = {},
  onContentChange: () -> Unit = {},
  onUrlClick: (String) -> Unit = {},
  isLoading: Boolean = false,
  animateReveal: Boolean = false,
  actionsEnabled: Boolean = true,
  selectedPermission: String = PermissionMode.READONLY,
  onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { _, _, _ -> },
  onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> }
) {
  val renderBlocks = message.renderBlocks
  val hasContent = renderBlocks.isNotEmpty()

  // Shared serial "reveal" queue for ALL block types (thinking, tool calls,
  // responses, errors). Only the newest AI message animates this way so
  // blocks appear one at a time in event order; historical messages render
  // in full instantly. This keeps interleaved playback scenarios readable
  // instead of letting tool capsules pop in while response text still paces.
  var revealedCount by remember { mutableIntStateOf(0) }
  LaunchedEffect(renderBlocks.size, animateReveal) {
    if (!animateReveal || message.revealComplete) {
      revealedCount = renderBlocks.size
      return@LaunchedEffect
    }
    while (revealedCount < renderBlocks.size) {
      delay(BLOCK_REVEAL_STAGGER_MS.milliseconds)
      revealedCount = revealedCount + 1
      onContentChange()
    }
    // Once a non-empty message has fully stepped through its queue, remember
    // it so reopening the window does not replay the paced reveal.
    if (renderBlocks.isNotEmpty()) {
      message.revealComplete = true
    }
    onContentChange()
  }

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
        if (index >= revealedCount) return@forEachIndexed
        key(block.key(index)) {
          when (block) {
            is RenderBlock.Thinking -> ThinkingBlock(block, isLoading, onUrlClick)
            is RenderBlock.ToolCall -> ToolCallBlock(block, onOpenInEditor, onViewDiff)
            is RenderBlock.Response -> ResponseBlock(block, onUrlClick, onContentChange)
            is RenderBlock.Error -> ErrorBlock(block)
          }
          Spacer(modifier = Modifier.height(GradumSpacing.lrl))
        }
      }

      // Hide TokenStatusRow in debug mode
      if (selectedPermission != PermissionMode.DEBUG && (isLoading || (message.tokenUsage?.totalTokens ?: 0) > 0)) {
        Spacer(Modifier.height(GradumSpacing.md))
        TokenStatusRow(
          isLoading = isLoading,
          tokenCount = message.tokenUsage?.totalTokens ?: 0,
          sendingPhase = if (isLoading) sendingPhase else message("gradum.done")
        )
      }

      if (!isLoading) {
        Spacer(modifier = Modifier.height(GradumSpacing.md))
        MessageActionsRow(
          message = message,
          isLoading = isLoading,
          hasContent = hasContent,
          actionsEnabled = actionsEnabled,
          selectedPermission = selectedPermission,
          onRetry = onRetry,
        )
      }
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
    modifier = Modifier.graphicsLayer {
      this.alpha = fadeAlpha.value
    },
    isTaskComplete = !isLoading,
    onUrlClick = onUrlClick
  )
}

@Composable
private fun ResponseBlock(
  block: RenderBlock.Response,
  onUrlClick: (String) -> Unit,
  onContentChange: () -> Unit = {},
) {
  val segments = remember(block.content) { splitMarkdown(block.content) }
  val paragraphStyle = rememberGradumParagraphTextStyle()

  SelectionContainer {
    Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)) {
      segments.forEach { segment ->
        AnimatedSegment(segment, paragraphStyle, onUrlClick)
      }
    }
  }
}

@Composable
private fun AnimatedSegment(
  segment: MarkdownSegment,
  paragraphStyle: androidx.compose.ui.text.TextStyle,
  onUrlClick: (String) -> Unit,
) {
  val density = LocalDensity.current
  var contentHeightPx by remember { mutableIntStateOf(0) }
  val measuredHeightDp = with(density) { contentHeightPx.toDp() }

  val alpha = remember { Animatable(0f) }
  val offsetY = remember { Animatable(SEGMENT_RISE_DP.value) }

  LaunchedEffect(contentHeightPx) {
    if (contentHeightPx == 0) return@LaunchedEffect
    val durationMs = animationDurationMs(segment, measuredHeightDp)
    val easing = animationEasing(segment)
    launch { alpha.animateTo(1f, tween(durationMillis = durationMs, easing = easing)) }
    launch { offsetY.animateTo(0f, tween(durationMillis = durationMs, easing = easing)) }
  }

  Box(
    Modifier
      .onGloballyPositioned { contentHeightPx = it.size.height }
      .graphicsLayer {
        this.alpha = alpha.value
        translationY = offsetY.value
      }
  ) {
    when (segment) {
      is MarkdownSegment.Plain -> {
        val outcome: InlineMarkdownRenderResult = rememberInlineMarkdownRender(segment.text)
        if (outcome.render != null) {
          val render: InlineMarkdownRender = outcome.render
          Text(
            style = paragraphStyle,
            text = render.annotated,
            modifier = Modifier.fillMaxWidth(),
            inlineContent = render.inlineContent
          )
        } else {
          Markdown(
            onUrlClick = onUrlClick,
            markdown = segment.text,
            modifier = Modifier.fillMaxWidth()
          )
        }
      }

      is MarkdownSegment.NonProseBlock -> {
        RenderNonProseBlock(onUrlClick = onUrlClick, segment)
      }

      is MarkdownSegment.Table -> {
        if (segment.isRenderable()) {
          ScrollableTable(segment, onUrlClick = onUrlClick)
        } else {
          TableParseFailurePlaceholder()
        }
      }
    }
  }
}

private fun animationDurationMs(segment: MarkdownSegment, measuredHeightDp: Dp): Int {
  val typeWeight = when (segment) {
    is MarkdownSegment.Table -> 1.5f
    is MarkdownSegment.NonProseBlock -> 1.2f
    is MarkdownSegment.Plain -> 1.0f
  }
  val heightDp = measuredHeightDp.value.coerceAtLeast(0f)
  val extra = (heightDp / 100f * SEGMENT_EXTRA_PER_100DP_MS * typeWeight)
    .toInt()
    .coerceAtMost(SEGMENT_MAX_EXTRA_MS)
  return SEGMENT_BASE_DURATION_MS + extra
}

private fun animationEasing(segment: MarkdownSegment): Easing = when (segment) {
  is MarkdownSegment.Table, is MarkdownSegment.NonProseBlock -> FastOutSlowInEasing
  is MarkdownSegment.Plain -> LinearOutSlowInEasing
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
  val fadeAlpha = remember { Animatable(1f) }
  val verticalOffset = remember { Animatable(0f) }
  var displayText by remember { mutableStateOf(sendingPhase) }
  var previousText by remember { mutableStateOf(sendingPhase) }
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
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
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
        this.alpha = fadeAlpha.value
        translationY = verticalOffset.value
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
  selectedPermission: String = PermissionMode.READONLY,
) {
  val isDebug = selectedPermission == PermissionMode.DEBUG
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
    if (!isDebug) {
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
