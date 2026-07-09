/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownStyling.kt  2026-07-09 12:43:35 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui

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
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.*
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

private const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.7f
private const val TITLE_LINE_HEIGHT_MULTIPLIER = 1.5f
private const val THINKING_LINE_HEIGHT_MULTIPLIER = 1.6f

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
    val inlineCodeTextStyle = editorTextStyle.copy(
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
        val linkSpan = SpanStyle(color = activeLinkColor)
        val paragraphInlines = InlinesStyling(
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

        fun headingStyle(fontSizeMultiplier: Float, fontWeight: FontWeight): TextStyle {
            val headingFontSize: TextUnit = paragraphTextStyle.fontSize * fontSizeMultiplier
            val headingLineHeight: TextUnit = headingFontSize * TITLE_LINE_HEIGHT_MULTIPLIER
            return paragraphTextStyle.copy(
                fontSize = headingFontSize,
                lineHeight = headingLineHeight,
                fontWeight = fontWeight
            )
        }

        fun headingInlines(textStyle: TextStyle): InlinesStyling {
            val headingInlineCode = textStyle.toSpanStyle().copy(
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

        val h1Style: TextStyle = headingStyle(1.6f, FontWeight.Bold)
        val h2Style: TextStyle = headingStyle(1.4f, FontWeight.Bold)
        val h3Style: TextStyle = headingStyle(1.2f, FontWeight.SemiBold)
        val h4Style: TextStyle = headingStyle(1.1f, FontWeight.SemiBold)
        val h5Style: TextStyle = headingStyle(1.0f, FontWeight.Medium)
        val h6Style: TextStyle = headingStyle(1.0f, FontWeight.Medium)

        // Use editor's monospace family for ordered-list markers to align digits vertically
        val numberStyle: TextStyle = paragraphTextStyle.copy(
            fontFamily = editorTextStyle.fontFamily,
            color = if (thinkingMode) thinkingGray else globalColors.text.info
        )

        // Vertical padding between list items
        val listItemPadding = PaddingValues(vertical = GradumSpacing.sml)

        // Blockquote: left indent only, no border/background
        val blockQuoteTextColor: Color =
            if (thinkingMode) thinkingGray else globalColors.text.info
        val blockQuote: MarkdownStyling.BlockQuote = MarkdownStyling.BlockQuote.createBlockQuote(
            lineWidth = 0.dp,
            lineColor = Color.Transparent,
            textColor = blockQuoteTextColor,
            padding = PaddingValues(start = GradumSpacing.xl)
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
                H1.createInlinesStyling(h1Style, headingInlines(h1Style)),
                H2.createInlinesStyling(h2Style, headingInlines(h2Style)),
                H3.createInlinesStyling(h3Style, headingInlines(h3Style)),
                H4.createInlinesStyling(h4Style, headingInlines(h4Style)),
                H5.createInlinesStyling(h5Style, headingInlines(h5Style)),
                H6.createInlinesStyling(h6Style, headingInlines(h6Style))
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
