/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownStyling.kt  2026-07-08 23:10:38 Changed by gwy
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
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createCodeStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createInlinesStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createBlockQuote
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createOrderedListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createUnorderedListStyling

/**
 * Creates a [MarkdownStyling] customized with Gradum-specific colors and typography.
 *
 * @param thinkingMode When `true`, every hard-coded color (inline code tint,
 *   link, blockquote, ordered-list number) collapses to a single muted gray
 *   drawn from `globalColors.text.disabled`. Used by [ThinkingIndicator] so
 *   the streaming reasoning block reads as secondary context rather than a
 *   full chat reply. The base body text color is *not* overridden here —
 *   wrap the call site in `CompositionLocalProvider(LocalContentColor
 *   provides ...)` if you also want the prose body text dimmed.
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
    val thinkingGray: Color = globalColors.text.disabled
    val inlineTint: Color = if (thinkingMode) thinkingGray else badgeBlue
    val inlineCodeTextStyle = editorTextStyle.copy(
        lineHeight = editorTextStyle.fontSize * 1.7f,
        color = inlineTint,
        background = inlineTint.copy(alpha = 0.12f)
    )
    // Markdown body uses `labelTextStyle` so the chat-side Markdown
    // matches the user message's sans-serif font + ~13sp size. The
    // previous `editorTextStyle` choice was the IntelliJ editor's
    // monospace face, which made assistant Markdown blocks feel like
    // they belonged in a code editor rather than a chat panel. The
    // inline-code text style still borrows from `editorTextStyle`
    // because the IDE's monospace + blue tint is exactly what
    // a fenced code block looks like, and we want the same visual
    // for `` `inline code` `` spans inside prose.
    val bodyTextStyle: TextStyle = labelTextStyle
    // Compose `Text` reads `style.color` first and only falls back to
    // `LocalContentColor` when the style's color is `Color.Unspecified`.
    // `labelTextStyle.color` is a hard-coded IDE foreground (not
    // `Unspecified`), so the `LocalContentColor` override on
    // [ThinkingIndicator] would not actually re-tint the prose body —
    // we have to set the color on the text style itself. In thinking
    // mode the whole derived tree (headings, list items, blockquote
    // body, fenced code block text) copies from `paragraphTextStyle`
    // and inherits the gray, which is exactly the "muted reasoning
    // block" look we want.
    val paragraphTextStyle: TextStyle = if (thinkingMode) {
        bodyTextStyle.copy(lineHeight = bodyTextStyle.fontSize * 1.7f, color = thinkingGray)
    } else {
        bodyTextStyle.copy(lineHeight = bodyTextStyle.fontSize * 1.7f)
    }

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
            // Scale lineHeight proportionally with the new fontSize; otherwise
            // an H1 (2.0x) keeps paragraph's 1.5x base-size lineHeight and wraps
            // look squashed against the larger glyphs.
            val headingLineHeight: TextUnit = headingFontSize * 1.5f
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

        // Use the editor's monospace family for the ordered-list marker
        // (1. 2. 3.) so the digits align vertically across rows of a
        // long list. We keep the paragraph's font size and line height
        // (and only borrow `fontFamily`) so the marker still tracks
        // the list item's vertical rhythm instead of jumping to a
        // separate monospace block.
        val numberStyle: TextStyle = paragraphTextStyle.copy(
            fontFamily = editorTextStyle.fontFamily,
            color = if (thinkingMode) thinkingGray else globalColors.text.info
        )

        // 4dp vertical padding between list items — matches the table
        // cell's vertical padding and the inline block spacing
        // (`GradumSpacing.xs`). Default Jewel list items have ~0dp
        // between siblings, which makes dense Markdown lists like
        // task plans and feature lists collapse into a wall of text.
        val listItemPadding: PaddingValues = PaddingValues(vertical = 4.dp)

        // Blockquote: 16dp left indent, no border, no background,
        // muted gray text. The "no border + no background" choice
        // matches the GitHub "loose" blockquote look — when the
        // indent alone is enough to set the quote apart, drawing a
        // left bar stacks noise on top of the chat panel's existing
        // vertical rules. Italic is *not* applied here because
        // `MarkdownStyling.BlockQuote` has no textStyle field —
        // see todo above about wrapping the renderer if italic
        // becomes a hard requirement.
        val blockQuoteTextColor: Color =
            if (thinkingMode) thinkingGray else globalColors.text.info.copy(alpha = 0.85f)
        val blockQuote: MarkdownStyling.BlockQuote = MarkdownStyling.BlockQuote.createBlockQuote(
            padding = PaddingValues(start = 16.dp),
            lineWidth = 0.dp,
            lineColor = Color.Transparent,
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
