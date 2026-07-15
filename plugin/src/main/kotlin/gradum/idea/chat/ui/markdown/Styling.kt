/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Styling.kt  2026-07-15 22:00:12 Changed by gwy
 */

// Detekt defaults disagree with project standards (2-space indent, 200-char
// lines, Compose-PascalCase, 1-line spacing between imports and code, etc.).
package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gradum.idea.chat.ui.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.*
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Unordered
import org.jetbrains.jewel.ui.component.styling.LinkColors
import org.jetbrains.jewel.ui.component.styling.LinkStyle
import org.jetbrains.jewel.ui.component.styling.LinkUnderlineBehavior
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.theme.linkStyle
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createBlockQuote
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createCodeStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createInlinesStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createOrderedListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createUnorderedListStyling

private const val DEFAULT_LINE_HEIGHT_MULTIPLIER: Float = 1.5f
private const val TITLE_LINE_HEIGHT_MULTIPLIER: Float = 1.25f
private const val THINKING_LINE_HEIGHT_MULTIPLIER: Float = 1.5f

// 4 dp = GradumSpacing.sm — chat's small scale. Picked over `md` (8 dp) to
// keep the quote rule visually slim (a 2-em blockquote shouldn't look like
// a section divider) and over `xs` (2 dp) so the rounded end-caps still
// register against the quote's gray text.
private const val BLOCKQUOTE_LINE_WIDTH_DP: Float = 4f
private const val BODY_FONT_SIZE_FALLBACK_SP: Float = 13f
private const val HEADING_H1_SIZE_MULTIPLIER: Float = 1.6f
private const val HEADING_H2_SIZE_MULTIPLIER: Float = 1.4f
private const val HEADING_H3_SIZE_MULTIPLIER: Float = 1.2f
private const val HEADING_H4_SIZE_MULTIPLIER: Float = 1.1f
private const val HEADING_H5_SIZE_MULTIPLIER: Float = 1.0f
private const val HEADING_H6_SIZE_MULTIPLIER: Float = 1.0f

/**
 * Padding applied to every heading block (H1–H6).
 *
 * Jewel's default is `PaddingValues(top = 24.dp, bottom = 16.dp)`,
 * which is GitHub-flavored spacing designed for a documentation
 * page. In a chat bubble that stack of 24 dp + the heading's own
 * line-height slack + the markdown `blockVerticalSpacing` (16 dp)
 * creates a ~40 sp gap between an H1 and the body text that follows
 * it — roughly 2½ lines of empty space, which reads as a layout
 * bug. Override to 0 so the block-level `blockVerticalSpacing` is
 * the single source of truth for inter-block gaps, matching the
 * spacing between any two regular paragraphs.
 */
private val HeadingBlockPadding: PaddingValues = PaddingValues(0.dp)

/**
 * The chat panel's body text style. Slightly tighter than the LaF default
 * (line-height 1.5×). Returned as a [TextStyle] so the inline chip parser
 * can read the font size for its placeholder width / height.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun rememberGradumParagraphTextStyle(): TextStyle {
  val labelTextStyle: TextStyle = JewelTheme.typography.labelTextStyle
  val fontSizeValue: Float = labelTextStyle.fontSize.value.takeIf { it > 0f }
    ?: BODY_FONT_SIZE_FALLBACK_SP
  val resolvedTextStyle: TextStyle = labelTextStyle.copy(
    fontSize = fontSizeValue.sp,
    lineHeight = (fontSizeValue * DEFAULT_LINE_HEIGHT_MULTIPLIER).sp
  )
  return resolvedTextStyle
}

/**
 * Returns a `LinkColors` with `content` overridden and the other 5
 * state colors (disabled / focused / hovered / pressed / visited)
 * carried through unchanged. `LinkColors` is a plain class (not
 * a data class) so it has no `copy(...)` — we hand-roll a small
 * `withContent` for the chat's "thinking-mode link color"
 * override. Used by [rememberGradumMarkdownStyling].
 */
@OptIn(ExperimentalJewelApi::class)
internal fun LinkColors.withContent(newContent: Color): LinkColors = LinkColors(
  content = newContent,
  contentFocused = contentFocused,
  contentHovered = contentHovered,
  contentPressed = contentPressed,
  contentVisited = contentVisited,
  contentDisabled = contentDisabled,
)

/**
 * Build an [InlinesStyling] for the chat's `Markdown(...)` fallback
 * path. Link colors are pulled directly from [linkColors] — the
 * official Jewel API already provides 6 state-aware Color fields
 * (`content` / `contentDisabled` / `contentFocused` /
 * `contentHovered` / `contentPressed` / `contentVisited`), and
 * there is no need to re-implement them in the chat. Centralized
 * here so the paragraph and heading configurations stay in sync.
 */
@OptIn(ExperimentalJewelApi::class)
internal fun gradumInlinesStyling(
  textStyle: TextStyle, inlineCodeStyle: SpanStyle, linkColors: LinkColors,
): InlinesStyling {
  fun linkSpan(content: Color): SpanStyle = SpanStyle(color = content)
  return InlinesStyling(
    textStyle = textStyle,
    inlineHtml = SpanStyle(),
    inlineCode = inlineCodeStyle,
    link = linkSpan(linkColors.content),
    emphasis = SpanStyle(fontStyle = FontStyle.Italic),
    strongEmphasis = SpanStyle(fontWeight = FontWeight.Bold),
    linkFocused = linkSpan(linkColors.contentFocused),
    linkHovered = linkSpan(linkColors.contentHovered),
    linkPressed = linkSpan(linkColors.contentPressed),
    linkVisited = linkSpan(linkColors.contentVisited),
    linkDisabled = linkSpan(linkColors.contentDisabled)
  )
}

/**
 * Chat-panel `LinkStyle` for `ExternalLink`. Same colors / metrics /
 * icons as `JewelTheme.linkStyle`, but with the underline behavior
 * set to [LinkUnderlineBehavior.ShowAlways] so the underline paints
 * at all times — not just on hover. The v1 `ShowOnHover` rule was
 * unreliable in chat-bubble context (Compose `FlowRow` re-layouts
 * and the IDE LaF's hover state sometimes don't fire predictably,
 * leaving the link looking like plain blue text). Permanent
 * underline gives the link a stable visual affordance.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun rememberGradumLinkStyle(): LinkStyle {
  val base: LinkStyle = JewelTheme.linkStyle
  return remember(base) {
    LinkStyle(
      icons = base.icons,
      colors = base.colors,
      metrics = base.metrics,
      underlineBehavior = LinkUnderlineBehavior.ShowAlways
    )
  }
}

/**
 * Creates a [MarkdownStyling] customized with Gradum-specific colors and typography.
 *
 * @param thinkingMode When `true`, all colors collapse to muted gray for streaming reasoning blocks.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun rememberGradumMarkdownStyling(thinkingMode: Boolean = false): MarkdownStyling {
  val globalColors: GlobalColors = LocalGlobalColors.current
  val labelTextStyle: TextStyle = JewelTheme.typography.labelTextStyle
  val editorTextStyle: TextStyle = JewelTheme.editorTextStyle
  val linkStyle: LinkStyle = JewelTheme.linkStyle
  val badgeBlue: Color =
    (JewelTheme.badgeStyle.blue.colors.background as? SolidColor)?.value
      ?: JewelTheme.badgeStyle.blue.colors.content

  val thinkingGray: Color = globalColors.text.info
  val inlineTint: Color = if (thinkingMode) thinkingGray else badgeBlue
  val inlineCodeTextStyle: TextStyle = editorTextStyle.copy(
    color = inlineTint,
    background = inlineTint.copy(alpha = 0.12f),
    lineHeight = editorTextStyle.fontSize * THINKING_LINE_HEIGHT_MULTIPLIER
  )
  val bodyTextStyle: TextStyle = labelTextStyle

  val paragraphTextStyle: TextStyle = if (thinkingMode) {
    bodyTextStyle.copy(lineHeight = bodyTextStyle.fontSize * THINKING_LINE_HEIGHT_MULTIPLIER, color = thinkingGray)
  } else {
    bodyTextStyle.copy(lineHeight = bodyTextStyle.fontSize * DEFAULT_LINE_HEIGHT_MULTIPLIER)
  }

  return remember(globalColors, editorTextStyle, linkStyle, inlineTint, paragraphTextStyle, thinkingMode) {
    val chatLinkColors: LinkColors = if (thinkingMode)
      linkStyle.colors.withContent(thinkingGray)
    else
      linkStyle.colors

    val paragraphInlines: InlinesStyling = gradumInlinesStyling(
      linkColors = chatLinkColors,
      textStyle = paragraphTextStyle,
      inlineCodeStyle = inlineCodeTextStyle.toSpanStyle()
    )

    fun headingStyle(fontSizeMultiplier: Float, fontWeight: FontWeight, italic: Boolean = false): TextStyle {
      val headingFontSize: TextUnit = paragraphTextStyle.fontSize * fontSizeMultiplier
      val headingLineHeight: TextUnit = headingFontSize * TITLE_LINE_HEIGHT_MULTIPLIER
      return paragraphTextStyle.copy(
        fontWeight = fontWeight,
        fontSize = headingFontSize,
        lineHeight = headingLineHeight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
      )
    }

    fun headingInlines(textStyle: TextStyle): InlinesStyling {
      val headingInlineCode: SpanStyle = textStyle.toSpanStyle().copy(
        background = inlineTint.copy(alpha = 0.12f)
      )
      return gradumInlinesStyling(
        textStyle = textStyle,
        linkColors = chatLinkColors,
        inlineCodeStyle = headingInlineCode
      )
    }

    val h1Style: TextStyle = headingStyle(HEADING_H1_SIZE_MULTIPLIER, FontWeight.Bold)
    val h2Style: TextStyle = headingStyle(HEADING_H2_SIZE_MULTIPLIER, FontWeight.Bold)
    val h3Style: TextStyle = headingStyle(HEADING_H3_SIZE_MULTIPLIER, FontWeight.SemiBold)
    val h4Style: TextStyle = headingStyle(HEADING_H4_SIZE_MULTIPLIER, FontWeight.SemiBold)
    val h5Style: TextStyle = headingStyle(HEADING_H5_SIZE_MULTIPLIER, FontWeight.Medium)
    val h6Style: TextStyle = headingStyle(HEADING_H6_SIZE_MULTIPLIER, FontWeight.Medium, italic = true)

    val numberStyle: TextStyle = paragraphTextStyle.copy(
      fontFamily = editorTextStyle.fontFamily,
      color = if (thinkingMode) thinkingGray else globalColors.text.info
    )

    val listItemPadding = PaddingValues(vertical = GradumSpacing.lg)

    val blockQuoteTextColor: Color = if (thinkingMode) thinkingGray else globalColors.text.disabled
    val blockQuoteLineColor: Color = if (thinkingMode) thinkingGray else globalColors.borders.normal
    val blockQuote: MarkdownStyling.BlockQuote = MarkdownStyling.BlockQuote.createBlockQuote(
      padding = PaddingValues(
        start = GradumSpacing.xl,
        top = GradumSpacing.sm,
        end = GradumSpacing.md,
        bottom = GradumSpacing.sm
      ),
      strokeCap = StrokeCap.Round,
      lineColor = blockQuoteLineColor,
      textColor = blockQuoteTextColor,
      lineWidth = BLOCKQUOTE_LINE_WIDTH_DP.dp
    )

    MarkdownStyling.createCodeStyling(
      paragraphTextStyle,
      paragraphTextStyle,
      paragraphInlines,
      GradumSpacing.xl,
      paragraph = MarkdownStyling.Paragraph.createInlinesStyling(paragraphInlines),
      heading = MarkdownStyling.Heading.createInlinesStyling(
        paragraphTextStyle,
        H1.createInlinesStyling(
          baseTextStyle = h1Style,
          inlinesStyling = headingInlines(h1Style),
          padding = HeadingBlockPadding
        ),
        H2.createInlinesStyling(
          baseTextStyle = h2Style,
          inlinesStyling = headingInlines(h2Style),
          padding = HeadingBlockPadding
        ),
        H3.createInlinesStyling(
          baseTextStyle = h3Style,
          inlinesStyling = headingInlines(h3Style),
          padding = HeadingBlockPadding
        ),
        H4.createInlinesStyling(
          baseTextStyle = h4Style,
          inlinesStyling = headingInlines(h4Style),
          padding = HeadingBlockPadding
        ),
        H5.createInlinesStyling(
          baseTextStyle = h5Style,
          inlinesStyling = headingInlines(h5Style),
          padding = HeadingBlockPadding
        ),
        H6.createInlinesStyling(
          baseTextStyle = h6Style,
          inlinesStyling = headingInlines(h6Style),
          padding = HeadingBlockPadding
        )
      ),
      code = MarkdownStyling.Code.createCodeStyling(
        fenced = MarkdownStyling.Code.Fenced.createCodeStyling(
          infoPosition = MarkdownStyling.Code.Fenced.InfoPosition.Hide
        )
      ),
      blockQuote = blockQuote,
      list = MarkdownStyling.List.createListStyling(
        paragraphTextStyle,
        Ordered.createOrderedListStyling(
          numberStyle = numberStyle,
          padding = listItemPadding,
        ),
        Unordered.createUnorderedListStyling(
          bullet = '\u2022',
          padding = listItemPadding,
          bulletStyle = TextStyle(
            color = globalColors.text.info,
            fontFamily = editorTextStyle.fontFamily
          )
        )
      )
    )
  }
}
