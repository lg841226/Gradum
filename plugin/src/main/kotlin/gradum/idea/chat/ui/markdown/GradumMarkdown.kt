/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdown.kt  2026-08-25 23:42:13 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString.Range
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import gradum.idea.settings.LocalParagraphSpacing
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LinkStyle


/**
 * DSL scope for configuring [GradumMarkdown] rendering.
 *
 * Usage:
 * ```kotlin
 * GradumMarkdown(text = markdown) {
 *     onUrlClick = { browse(it) }
 *     thinkingMode = true
 * }
 * ```
 */
class GradumMarkdownScope {
  /** URL click handler. */
  var onUrlClick: (String) -> Unit = {}

  /**
   * When `true`, renders with muted gray colors (for streaming reasoning).
   * Affects paragraph text, link colors, inline code chips, and code blocks.
   */
  var thinkingMode: Boolean = false

  /**
   * When `true`, code blocks and tables render without toolbars / copy buttons.
   * Useful for inline content where the user hasn't committed to the final answer.
   */
  var isSimplified: Boolean = false

  /**
   * When `true`, segments animate in with a fade + rise effect.
   * Disable for dialogs / static content.
   */
  var animationEnabled: Boolean = true

  /**
   * When `true`, wraps the content in a [SelectionContainer] so text is selectable.
   * Disable for dialogs where selection is not needed.
   */
  var withSelection: Boolean = false

  /** Paragraph text style override. Defaults to [rememberGradumParagraphTextStyle]. */
  var paragraphStyle: TextStyle? = null

  /**
   * Font family override for the AI response body text.
   * When set, the paragraph text (and all derived styles like headings,
   * blockquotes, list items) will use this font family.
   * Inline code, code blocks, and list markers retain the editor monospace font.
   */
  var fontFamily: FontFamily? = null
}

/**
 * Unified Markdown renderer for the Gradum chat UI.
 *
 * Encapsulates the common rendering pipeline:
 * 1. [splitMarkdown] — split raw text into [MarkdownSegment]s
 * 2. [gradum.idea.chat.ui.markdown.MarkdownSegment.Plain] segments → [rememberInlineMarkdownRender] → [Text]
 * 3. [gradum.idea.chat.ui.markdown.MarkdownSegment.Table] segments → [ScrollableTable]
 * 4. [gradum.idea.chat.ui.markdown.MarkdownSegment.NonProseBlock] segments → [RenderNonProseBlock] or [Markdown]
 *
 * Usage:
 * ```kotlin
 * GradumMarkdown(text = markdown) {
 *     onUrlClick = { browse(it) }
 *     thinkingMode = true
 *     isSimplified = true
 * }
 * ```
 */
@Composable
fun GradumMarkdown(
  text: String,
  modifier: Modifier = Modifier,
  builder: @Composable GradumMarkdownScope.() -> Unit = {}
) {
  val config = remember { GradumMarkdownScope() }
  builder(config)
  GradumMarkdownContent(text, config, modifier)
}

/**
 * Internal rendering implementation. Separated from [GradumMarkdown] so
 * [GradumMarkdownScope] properties are stable within the scope function.
 */
@Composable
internal fun GradumMarkdownContent(
  text: String,
  config: GradumMarkdownScope,
  modifier: Modifier = Modifier
) {
  val segments = remember(key1 = text) { splitMarkdown(text) }
  val paragraphStyle = config.paragraphStyle ?: rememberGradumParagraphTextStyle()
  val bodyTextStyle =
    if (config.fontFamily != null) {
      paragraphStyle.copy(fontFamily = config.fontFamily)
    } else {
      paragraphStyle
    }
  val paragraphSpacing: Dp = LocalParagraphSpacing.current

  val content =
    @Composable {
      CompositionLocalProvider(
        LocalThinkingMode provides config.thinkingMode,
        LocalMarkdownBodyTextStyle provides bodyTextStyle
      ) {
        Column(
          modifier = modifier,
          verticalArrangement = Arrangement.spacedBy(paragraphSpacing)
        ) {
          segments.forEach { segment ->
            GradumMarkdownSegment(
              config = config,
              segment = segment,
              paragraphStyle = bodyTextStyle
            )
          }
        }
      }
    }

  if (config.withSelection) {
    SelectionContainer { content() }
  } else {
    content()
  }
}

/**
 * Renders a single [MarkdownSegment] with optional animation.
 */
@Composable
private fun GradumMarkdownSegment(
  segment: MarkdownSegment,
  paragraphStyle: TextStyle,
  config: GradumMarkdownScope
) {
  if (config.animationEnabled) {
    AnimatedGradumSegment(
      config = config,
      segment = segment,
      paragraphStyle = paragraphStyle
    )
  } else {
    StaticGradumSegment(
      config = config,
      segment = segment,
      paragraphStyle = paragraphStyle
    )
  }
}

/**
 * Static (non-animated) segment renderer. Used by dialogs and static content.
 */
@Composable
private fun StaticGradumSegment(
  segment: MarkdownSegment,
  paragraphStyle: TextStyle,
  config: GradumMarkdownScope,
) {
  when (segment) {
    is MarkdownSegment.Plain -> {
      val outcome: InlineMarkdownRenderResult = rememberInlineMarkdownRender(
        plainText = segment.text,
        thinkingMode = config.thinkingMode
      )
      val resolvedStyle = resolveParagraphStyle(baseStyle = paragraphStyle, config)
      if (outcome.render != null) {
        val render: InlineMarkdownRender = outcome.render
        val gradumLinkStyle: LinkStyle = rememberGradumLinkStyle()
        val segments = splitIntoInlineSegments(
          annotated = render.annotated,
          urlAnnotations = render.urlAnnotations,
          inlineContent = render.inlineContent,
        )
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Start,
          verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs, Alignment.Top),
        ) {
          segments.forEach { inlineSegment ->
            when (inlineSegment) {
              is InlineSegment.TextSegment -> {
                val codeSpanAnnotations = remember(key1 = inlineSegment.annotated) {
                  inlineSegment.annotated.getStringAnnotations(
                    INLINE_CODE_SPAN_TAG, start = 0, end = inlineSegment.annotated.length
                  )
                }
                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(value = null) }
                val density = LocalDensity.current
                val badgeColor = JewelTheme.globalColors.text.info
                val backgroundColor = badgeColor.copy(alpha = MarkdownStyle.InlineCode.BACKGROUND_ALPHA)
                val borderColor = badgeColor.copy(alpha = MarkdownStyle.InlineCode.CHIP_BORDER_ALPHA)
                Text(
                  style = resolvedStyle,
                  text = inlineSegment.annotated,
                  inlineContent = inlineSegment.inlineContent,
                  onTextLayout = { layoutResult -> textLayoutResult = layoutResult },
                  modifier = Modifier.drawWithContent {
                    drawInlineCodeBackgrounds(
                      density = density,
                      borderColor = borderColor,
                      backgroundColor = backgroundColor,
                      fontSize = resolvedStyle.fontSize,
                      textLayoutResult = textLayoutResult,
                      codeSpanAnnotations = codeSpanAnnotations
                    )
                    drawContent()
                  }
                )
              }

              is InlineSegment.LinkSegment -> ExternalLink(
                style = gradumLinkStyle,
                text = inlineSegment.text,
                textStyle = resolvedStyle,
                onClick = { config.onUrlClick(inlineSegment.url) }
              )
            }
          }
        }
      } else {
        Text(
          text = segment.text,
          style = resolvedStyle,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    is MarkdownSegment.Table -> {
      if (segment.isRenderable()) {
        ScrollableTable(
          table = segment,
          isSimplified = config.isSimplified,
          onUrlClick = config.onUrlClick
        )
      } else {
        TableParseFailurePlaceholder()
      }
    }

    is MarkdownSegment.NonProseBlock -> {
      RenderNonProseBlock(
        segment = segment,
        onUrlClick = config.onUrlClick,
        isSimplified = config.isSimplified,
        thinkingMode = config.thinkingMode
      )
    }
  }
}

/**
 * Animated segment renderer. Used by chat bubble for fade + rise entrance.
 */
@Composable
private fun AnimatedGradumSegment(
  segment: MarkdownSegment,
  paragraphStyle: TextStyle,
  config: GradumMarkdownScope
) {
  val density = LocalDensity.current
  var contentHeightPx by remember { mutableIntStateOf(value = 0) }
  val measuredHeightDp = with(receiver = density) { contentHeightPx.toDp() }

  val alpha = remember { Animatable(initialValue = 0f) }
  val offsetY = remember { Animatable(initialValue = MarkdownStyle.SegmentAnimation.RISE_DP.value) }

  LaunchedEffect(key1 = contentHeightPx) {
    if (contentHeightPx == 0) return@LaunchedEffect
    val durationMs = animationDurationMs(segment, measuredHeightDp)
    val easing = animationEasing(segment)
    launch {
      alpha.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = durationMs, easing = easing)
      )
    }
    launch {
      offsetY.animateTo(
        targetValue = 0f,
        animationSpec = tween(durationMillis = durationMs, easing = easing)
      )
    }
  }

  Box(
    modifier = Modifier
      .onGloballyPositioned { contentHeightPx = it.size.height }
      .graphicsLayer {
        this.alpha = alpha.value
        translationY = offsetY.value
      }
  ) {
    StaticGradumSegment(
      config = config,
      segment = segment,
      paragraphStyle = paragraphStyle
    )
  }
}

private fun animationDurationMs(segment: MarkdownSegment, measuredHeightDp: Dp): Int {
  val typeWeight =
    when (segment) {
      is MarkdownSegment.Table -> 1.5f
      is MarkdownSegment.NonProseBlock -> 1.2f
      is MarkdownSegment.Plain -> 1.0f
    }
  val heightDp = measuredHeightDp.value.coerceAtLeast(minimumValue = 0f)
  val extra = (heightDp / 100f * MarkdownStyle.SegmentAnimation.EXTRA_PER_100DP_MS * typeWeight)
    .toInt()
    .coerceAtMost(maximumValue = MarkdownStyle.SegmentAnimation.MAX_EXTRA_MS)
  return MarkdownStyle.SegmentAnimation.BASE_DURATION_MS + extra
}

private fun animationEasing(segment: MarkdownSegment): Easing = when (segment) {
  is MarkdownSegment.Table, is MarkdownSegment.NonProseBlock -> FastOutSlowInEasing
  is MarkdownSegment.Plain -> LinearOutSlowInEasing
}

/**
 * Resolves the paragraph style with thinking-mode color override.
 */
@Composable
private fun resolveParagraphStyle(baseStyle: TextStyle, config: GradumMarkdownScope): TextStyle {
  if (!config.thinkingMode) return baseStyle
  val thinkingColor: Color = LocalGlobalColors.current.text.info
  return baseStyle.copy(color = thinkingColor)
}

/**
 * Draws background + border chips behind inline code spans.
 *
 * Extracted from the `drawWithContent` lambda so the rendering
 * logic is readable and testable independently.
 */
private fun DrawScope.drawInlineCodeBackgrounds(
  density: Density,
  borderColor: Color,
  fontSize: TextUnit,
  backgroundColor: Color,
  textLayoutResult: TextLayoutResult?,
  codeSpanAnnotations: List<Range<String>>
) {
  val layoutResult = textLayoutResult ?: return
  if (codeSpanAnnotations.isEmpty()) return

  val cornerRadiusPx: Float = MarkdownStyle.InlineCode.CORNER_RADIUS.toPx()
  val chipHeightPx: Float = with(receiver = density) {
    fontSize.toPx() * MarkdownStyle.InlineCode.CHIP_HEIGHT_MULTIPLIER
  }
  val borderWidth: Float = MarkdownStyle.InlineCode.CHIP_BORDER_WIDTH.toPx()

  for ((_, start, end) in codeSpanAnnotations) {
    if (start >= end) continue
    val startLine: Int = layoutResult.getLineForOffset(start)
    val endLine: Int = layoutResult.getLineForOffset(end - 1)

    for (lineIndex in startLine..endLine) {
      val lineStartOffset = layoutResult.getLineStart(lineIndex)
      val lineEndOffset = layoutResult.getLineEnd(lineIndex)

      val segmentStartOffset = maxOf(a = start, b = lineStartOffset)
      val segmentEndOffset = minOf(a = end, b = lineEndOffset)

      if (segmentStartOffset >= segmentEndOffset) continue

      val lineBaseline = layoutResult.getLineBaseline(lineIndex)
      val leftX = layoutResult.getBoundingBox(segmentStartOffset).left
      val rightX = layoutResult.getBoundingBox(offset = segmentEndOffset - 1).right
      val chipTopY = lineBaseline - chipHeightPx * MarkdownStyle.InlineCode.CHIP_BASELINE_RATIO

      val chipSize = Size(
        width = rightX - leftX,
        height = chipHeightPx
      )
      val chipCornerRadius = CornerRadius(
        x = cornerRadiusPx,
        y = cornerRadiusPx
      )

      drawRoundRect(
        size = chipSize,
        color = backgroundColor,
        topLeft = Offset(leftX, chipTopY),
        cornerRadius = chipCornerRadius
      )
      drawRoundRect(
        size = chipSize,
        color = borderColor,
        topLeft = Offset(leftX, chipTopY),
        cornerRadius = chipCornerRadius,
        style = Stroke(width = borderWidth)
      )
    }
  }
}
