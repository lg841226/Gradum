// Detekt defaults disagree with project standards (2-space indent, 200-char
// lines, Compose-PascalCase, 1-line spacing between imports and code, etc.).
@file:Suppress(
  "MaximumLineLength",
  "Indentation",
  "FunctionNaming",
  "SpacingBetweenPackageAndImports",
  "NoConsecutiveBlankLines",
  "NoMultipleSpaces",
  "ArgumentListWrapping",
  "UnstableApiUsage",
)

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H1
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H2
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H3
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H4
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H5
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H6
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Unordered
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

private const val DEFAULT_LINE_HEIGHT_MULTIPLIER: Float = 1.5f
private const val TITLE_LINE_HEIGHT_MULTIPLIER: Float = 1.25f
private const val THINKING_LINE_HEIGHT_MULTIPLIER: Float = 1.5f
private const val BLOCKQUOTE_LINE_WIDTH_DP: Float = 3f
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
 * which is GitHub-flavoured spacing designed for a documentation
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
    lineHeight = (fontSizeValue * DEFAULT_LINE_HEIGHT_MULTIPLIER).sp,
  )
  return resolvedTextStyle
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
    lineHeight = editorTextStyle.fontSize * THINKING_LINE_HEIGHT_MULTIPLIER,
    color = inlineTint,
    background = inlineTint.copy(alpha = 0.12f)
  )
  val bodyTextStyle: TextStyle = labelTextStyle

  val paragraphTextStyle: TextStyle = if (thinkingMode)
    bodyTextStyle.copy(lineHeight = bodyTextStyle.fontSize * THINKING_LINE_HEIGHT_MULTIPLIER, color = thinkingGray)
  else
    bodyTextStyle.copy(lineHeight = bodyTextStyle.fontSize * DEFAULT_LINE_HEIGHT_MULTIPLIER)

  return remember(globalColors, editorTextStyle, linkStyle, inlineTint, paragraphTextStyle, thinkingMode) {
    val activeLinkColor: Color = if (thinkingMode) thinkingGray else linkStyle.colors.content
    val linkSpan: SpanStyle = SpanStyle(color = activeLinkColor)
    val paragraphInlines: InlinesStyling = InlinesStyling(
      textStyle = paragraphTextStyle,
      inlineCode = inlineCodeTextStyle.toSpanStyle(),
      link = linkSpan,
      linkDisabled = SpanStyle(color = activeLinkColor),
      linkFocused = SpanStyle(color = activeLinkColor, textDecoration = TextDecoration.Underline),
      linkHovered = SpanStyle(color = activeLinkColor, textDecoration = TextDecoration.Underline),
      linkPressed = SpanStyle(color = activeLinkColor, textDecoration = TextDecoration.Underline),
      linkVisited = SpanStyle(color = activeLinkColor),
      emphasis = SpanStyle(fontStyle = FontStyle.Italic),
      strongEmphasis = SpanStyle(fontWeight = FontWeight.Bold),
      inlineHtml = SpanStyle()
    )

    fun headingStyle(fontSizeMultiplier: Float, fontWeight: FontWeight, italic: Boolean = false): TextStyle {
      val headingFontSize: TextUnit = paragraphTextStyle.fontSize * fontSizeMultiplier
      val headingLineHeight: TextUnit = headingFontSize * TITLE_LINE_HEIGHT_MULTIPLIER
      return paragraphTextStyle.copy(
        fontSize = headingFontSize,
        lineHeight = headingLineHeight,
        fontWeight = fontWeight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
      )
    }

    fun headingInlines(textStyle: TextStyle): InlinesStyling {
      val headingInlineCode: SpanStyle = textStyle.toSpanStyle().copy(
        background = inlineTint.copy(alpha = 0.12f)
      )
      return InlinesStyling(
        textStyle = textStyle,
        inlineCode = headingInlineCode,
        link = linkSpan,
        linkDisabled = SpanStyle(color = activeLinkColor),
        linkFocused = SpanStyle(
          color = activeLinkColor,
          textDecoration = TextDecoration.Underline
        ),
        linkHovered = SpanStyle(
          color = activeLinkColor,
          textDecoration = TextDecoration.Underline
        ),
        linkPressed = SpanStyle(
          color = activeLinkColor,
          textDecoration = TextDecoration.Underline
        ),
        linkVisited = SpanStyle(color = activeLinkColor),
        emphasis = SpanStyle(fontStyle = FontStyle.Italic),
        strongEmphasis = SpanStyle(fontWeight = FontWeight.Bold),
        inlineHtml = SpanStyle()
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

    val listItemPadding: PaddingValues = PaddingValues(vertical = GradumSpacing.md)

    // Blockquote: muted (disabled) text color + a 3dp link-colored left
    // bar, no inner padding. 2026-07-14: italic was tried but the user
    // reverted it — quotes should read as regular prose, just visually
    // de-emphasized. Padding was tried then dropped the same day — the
    // user wants the quote text flush with the surrounding content.
    val blockQuoteTextColor: Color =
      if (thinkingMode) thinkingGray else globalColors.text.disabled
    val blockQuote: MarkdownStyling.BlockQuote = MarkdownStyling.BlockQuote.createBlockQuote(
      lineWidth = BLOCKQUOTE_LINE_WIDTH_DP.dp,
      lineColor = activeLinkColor,
      textColor = blockQuoteTextColor,
    )

    MarkdownStyling.createCodeStyling(
      paragraphTextStyle,
      paragraphTextStyle,
      paragraphInlines,
      GradumSpacing.xl,
      paragraph = MarkdownStyling.Paragraph.createInlinesStyling(
        paragraphInlines
      ),
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
        )
      )
    )
  }
}
