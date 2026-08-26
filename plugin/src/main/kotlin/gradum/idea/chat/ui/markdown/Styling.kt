/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Styling.kt  2026-08-26 00:09:56 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gradum.idea.settings.LocalCodeBlockFontSize
import gradum.idea.settings.LocalParagraphFontSize
import gradum.idea.utils.GradumSpacing
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
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.theme.linkStyle
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createBlockQuote
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createCodeStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createInlinesStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createOrderedListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createUnorderedListStyling

private const val BLOCKQUOTE_LINE_WIDTH_DP: Float = 3f
private const val BODY_FONT_SIZE_FALLBACK_SP: Float = 13f

private const val HEADING_H1_SIZE_MULTIPLIER: Float = 1.6f
private const val HEADING_H2_SIZE_MULTIPLIER: Float = 1.4f
private const val HEADING_H3_SIZE_MULTIPLIER: Float = 1.2f
private const val HEADING_H4_SIZE_MULTIPLIER: Float = 1.1f
private const val HEADING_H5_SIZE_MULTIPLIER: Float = 1.0f
private const val HEADING_H6_SIZE_MULTIPLIER: Float = 1.0f
private const val DEFAULT_LINE_HEIGHT_MULTIPLIER: Float = 1.5f
private const val TITLE_LINE_HEIGHT_MULTIPLIER: Float = 1.25f
private const val THINKING_LINE_HEIGHT_MULTIPLIER: Float = 1.5f

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
 * Optional override for the Markdown body/paragraph text style, applied by
 * [rememberGradumMarkdownStyling] when present. Lets a non-chat surface
 * (e.g. the commit-details panel) render the Markdown renderer's body text
 * in a custom font — such as the editor font — while reusing the full
 * block/inline pipeline. When `null`, the chat's default paragraph style
 * ([rememberGradumParagraphTextStyle]) is used.
 */
val LocalMarkdownBodyTextStyle = compositionLocalOf<TextStyle?> { null }

/**
 * Thinking mode flag for the Markdown renderer, set by [GradumMarkdownContent].
 * Read by [rememberGradumMarkdownStyling] to apply muted gray colors.
 */
val LocalThinkingMode = compositionLocalOf { false }

/**
 * The chat panel's body text style. Slightly tighter than the LaF default
 * (line-height 1.5×). Returned as a [TextStyle] so the inline chip parser
 * can read the font size for its placeholder width / height.
 */
@Composable
fun rememberGradumParagraphTextStyle(): TextStyle {
  val labelTextStyle: TextStyle = JewelTheme.typography.labelTextStyle
  val configuredFontSize: TextUnit = LocalParagraphFontSize.current
  val fontSizeValue: Float = if (configuredFontSize.value > 0f) {
    configuredFontSize.value
  } else {
    (labelTextStyle.fontSize.value.takeIf { it > 0f }
      ?: BODY_FONT_SIZE_FALLBACK_SP) + 1f
  }
  val resolvedTextStyle: TextStyle = labelTextStyle.copy(
    fontSize = fontSizeValue.sp,
    lineHeight = (fontSizeValue * DEFAULT_LINE_HEIGHT_MULTIPLIER).sp
  )
  return resolvedTextStyle
}

/**
 * Returns a `LinkColors` with `content` overridden and the other 5
 * scanState colors (disabled / focused / hovered / pressed / visited)
 * carried through unchanged. `LinkColors` is a plain class (not
 * a data class) so it has no `copy(...)` — we hand-roll a small
 * `withContent` for the chat's "thinking-mode link color"
 * override. Used by [rememberGradumMarkdownStyling].
 */
internal fun LinkColors.withContent(newContent: Color): LinkColors = LinkColors(
  content = newContent,
  contentFocused = contentFocused,
  contentHovered = contentHovered,
  contentPressed = contentPressed,
  contentVisited = contentVisited,
  contentDisabled = contentDisabled
)

/**
 * Build an [InlinesStyling] for the [MarkdownStyling] used by
 * [BlockRenderer]'s non-prose block rendering. Link colors are
 * pulled directly from [linkColors] — the official Jewel API
 * already provides 6 scanState-aware Color fields (`content` /
 * `contentDisabled` / `contentFocused` / `contentHovered` /
 * `contentPressed` / `contentVisited`), and there is no need to
 * re-implement them in the chat. Centralized here so the paragraph
 * and heading configurations stay in sync.
 */
@OptIn(ExperimentalJewelApi::class)
@Suppress("UnstableApiUsage")
internal fun gradumInlinesStyling(
  textStyle: TextStyle, inlineCodeStyle: SpanStyle, linkColors: LinkColors
): InlinesStyling {
  fun linkSpan(content: Color): SpanStyle = SpanStyle(color = content)
  return InlinesStyling(
    textStyle = textStyle,
    inlineHtml = SpanStyle(),
    inlineCode = inlineCodeStyle,
    link = linkSpan(linkColors.content),
    emphasis = SpanStyle(
      fontStyle = FontStyle.Italic,
    ),
    strongEmphasis = SpanStyle(fontWeight = FontWeight.SemiBold),
    linkFocused = linkSpan(content = linkColors.contentFocused),
    linkHovered = linkSpan(content = linkColors.contentHovered),
    linkPressed = linkSpan(content = linkColors.contentPressed),
    linkVisited = linkSpan(content = linkColors.contentVisited),
    linkDisabled = linkSpan(content = linkColors.contentDisabled)
  )
}

/**
 * Chat-panel `LinkStyle` for `ExternalLink`. Same colors / metrics /
 * icons as [JewelTheme.linkStyle], but wraps the default in a
 * [remember] so the identity is stable across recompositions.
 */
@Composable
fun rememberGradumLinkStyle(): LinkStyle = JewelTheme.linkStyle

/**
 * The solid blue of the theme's blue badge. Resolved from
 * `JewelTheme.badgeStyle.blue` (the `background` may be a transparent
 * `SolidColor`, so the `content` is the fallback).
 */
@Composable
fun rememberBadgeBlueColor(): Color =
  (JewelTheme.badgeStyle.blue.colors.background as? SolidColor)?.value
    ?: JewelTheme.badgeStyle.blue.colors.content

/**
 * Creates a [MarkdownStyling] customized with Gradum-specific colors and typography.
 *
 * @param thinkingMode When `true`, all colors collapse to muted gray for streaming reasoning blocks.
 */
@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class)
@Composable
fun rememberGradumMarkdownStyling(): MarkdownStyling {
  val linkStyle: LinkStyle = JewelTheme.linkStyle
  val badgeBlue: Color = rememberBadgeBlueColor()
  val globalColors: GlobalColors = LocalGlobalColors.current
  val editorTextStyle: TextStyle = JewelTheme.editorTextStyle
  val codeBlockFontSize: Float = LocalCodeBlockFontSize.current
  val bodyTextStyle: TextStyle = LocalMarkdownBodyTextStyle.current
    ?: rememberGradumParagraphTextStyle()
  val thinkingMode: Boolean = LocalThinkingMode.current

  val codeBlockTextStyle: TextStyle = editorTextStyle.copy(
    fontSize =
      (if (codeBlockFontSize > 0f) codeBlockFontSize
      else bodyTextStyle.fontSize.value).sp
  )

  val thinkingGray: Color = globalColors.text.info
  val inlineTint: Color = thinkingGray
  val inlineCodeTextStyle: TextStyle = editorTextStyle.copy(
    color = inlineTint,
    background = inlineTint.copy(alpha = MarkdownStyle.InlineCode.BACKGROUND_ALPHA),
    lineHeight = editorTextStyle.fontSize * THINKING_LINE_HEIGHT_MULTIPLIER
  )

  val paragraphTextStyle: TextStyle = if (thinkingMode) {
    bodyTextStyle.copy(
      lineHeight = bodyTextStyle.fontSize * THINKING_LINE_HEIGHT_MULTIPLIER,
      color = thinkingGray
    )
  } else {
    bodyTextStyle.copy(
      lineHeight = bodyTextStyle.fontSize * DEFAULT_LINE_HEIGHT_MULTIPLIER
    )
  }

  return remember(
    globalColors, editorTextStyle, linkStyle,
    inlineTint, paragraphTextStyle, thinkingMode, codeBlockTextStyle
  ) {
    val chatLinkColors: LinkColors =
      if (thinkingMode)
        linkStyle.colors.withContent(newContent = thinkingGray)
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
        fontStyle =
          if (italic) FontStyle.Italic
          else FontStyle.Normal
      )
    }

    fun headingInlines(textStyle: TextStyle): InlinesStyling {
      val headingInlineCode: SpanStyle = textStyle.toSpanStyle().copy(
        background = inlineTint.copy(alpha = MarkdownStyle.InlineCode.BACKGROUND_ALPHA)
      )
      return gradumInlinesStyling(
        textStyle = textStyle,
        linkColors = chatLinkColors,
        inlineCodeStyle = headingInlineCode
      )
    }

    val h1Style: TextStyle = headingStyle(fontSizeMultiplier = HEADING_H1_SIZE_MULTIPLIER, FontWeight.SemiBold)
    val h2Style: TextStyle = headingStyle(fontSizeMultiplier = HEADING_H2_SIZE_MULTIPLIER, FontWeight.SemiBold)
    val h3Style: TextStyle = headingStyle(fontSizeMultiplier = HEADING_H3_SIZE_MULTIPLIER, FontWeight.SemiBold)
    val h4Style: TextStyle = headingStyle(fontSizeMultiplier = HEADING_H4_SIZE_MULTIPLIER, FontWeight.Medium)
    val h5Style: TextStyle = headingStyle(fontSizeMultiplier = HEADING_H5_SIZE_MULTIPLIER, FontWeight.Medium)
    val h6Style: TextStyle = headingStyle(fontSizeMultiplier = HEADING_H6_SIZE_MULTIPLIER, FontWeight.Medium, italic = true)

    val numberStyle: TextStyle = paragraphTextStyle.copy(
      color =
        if (thinkingMode) thinkingGray
        else globalColors.text.info,
      fontFamily = editorTextStyle.fontFamily
    )

    val listItemPadding = PaddingValues(vertical = GradumSpacing.lg)

    val listItemStyle: TextStyle = paragraphTextStyle.copy(
      fontFamily = editorTextStyle.fontFamily
    )

    val blockQuoteTextColor: Color =
      if (thinkingMode) thinkingGray
      else globalColors.text.normal
    val blockQuoteLineColor: Color =
      if (thinkingMode) thinkingGray
      else badgeBlue.copy(alpha = 0.6f)
    val blockQuote: MarkdownStyling.BlockQuote = MarkdownStyling.BlockQuote.createBlockQuote(
      padding = PaddingValues(
        start = GradumSpacing.md,
        top = GradumSpacing.md,
        end = GradumSpacing.md,
        bottom = GradumSpacing.md
      ),
      strokeCap = StrokeCap.Round,
      lineColor = blockQuoteLineColor,
      textColor = blockQuoteTextColor,
      lineWidth = BLOCKQUOTE_LINE_WIDTH_DP.dp
    )

    MarkdownStyling.createCodeStyling(
      baseTextStyle = paragraphTextStyle,
      editorTextStyle = paragraphTextStyle,
      inlinesStyling = paragraphInlines,
      blockVerticalSpacing = GradumSpacing.lrl,
      paragraph = MarkdownStyling.Paragraph.createInlinesStyling(paragraphInlines),
      heading = MarkdownStyling.Heading.createInlinesStyling(
        baseTextStyle = paragraphTextStyle,
        H1.createInlinesStyling(
          baseTextStyle = h1Style,
          inlinesStyling = headingInlines(textStyle = h1Style),
          padding = HeadingBlockPadding
        ),
        H2.createInlinesStyling(
          baseTextStyle = h2Style,
          inlinesStyling = headingInlines(textStyle = h2Style),
          padding = HeadingBlockPadding
        ),
        H3.createInlinesStyling(
          baseTextStyle = h3Style,
          inlinesStyling = headingInlines(textStyle = h3Style),
          padding = HeadingBlockPadding
        ),
        H4.createInlinesStyling(
          baseTextStyle = h4Style,
          inlinesStyling = headingInlines(textStyle = h4Style),
          padding = HeadingBlockPadding
        ),
        H5.createInlinesStyling(
          baseTextStyle = h5Style,
          inlinesStyling = headingInlines(textStyle = h5Style),
          padding = HeadingBlockPadding
        ),
        H6.createInlinesStyling(
          baseTextStyle = h6Style,
          inlinesStyling = headingInlines(textStyle = h6Style),
          padding = HeadingBlockPadding
        )
      ),
      code = MarkdownStyling.Code.createCodeStyling(
        fenced = MarkdownStyling.Code.Fenced.createCodeStyling(
          textStyle =
            if (thinkingMode) codeBlockTextStyle.copy(color = thinkingGray)
            else codeBlockTextStyle,
          infoPosition = MarkdownStyling.Code.Fenced.InfoPosition.Hide
        )
      ),
      blockQuote = blockQuote,
      list = MarkdownStyling.List.createListStyling(
        baseTextStyle = listItemStyle,
        Ordered.createOrderedListStyling(
          numberStyle = numberStyle,
          padding = listItemPadding
        ),
        Unordered.createUnorderedListStyling(
          bullet = '\u2022',
          padding = listItemPadding,
          bulletStyle = TextStyle(
            color =
              if (thinkingMode) thinkingGray
              else globalColors.text.info,
          )
        )
      )
    )
  }
}
