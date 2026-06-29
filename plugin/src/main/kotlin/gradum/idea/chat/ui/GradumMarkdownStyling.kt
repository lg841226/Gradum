/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownStyling.kt  2026-06-29 19:22:38 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import org.jetbrains.jewel.ui.theme.linkStyle
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createCodeStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createInlinesStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createOrderedListStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create as createUnorderedListStyling

/**
 * Creates a [MarkdownStyling] customized with Gradum-specific colors and typography.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun rememberGradumMarkdownStyling(): MarkdownStyling {
    val globalColors: GlobalColors = LocalGlobalColors.current
    val editorTextStyle: TextStyle = JewelTheme.editorTextStyle
    val linkStyle: LinkStyle = JewelTheme.linkStyle
    return remember(globalColors, editorTextStyle, linkStyle) {
        val linkSpan = SpanStyle(color = linkStyle.colors.content)
        val paragraphTextStyle = editorTextStyle.copy(lineHeight = editorTextStyle.fontSize * 1.5f)
        val paragraphInlines = InlinesStyling(
            textStyle = paragraphTextStyle,
            inlineCode = paragraphTextStyle.toSpanStyle().copy(color = globalColors.text.info),
            link = linkSpan,
            linkDisabled = SpanStyle(color = linkStyle.colors.contentDisabled),
            linkFocused = SpanStyle(color = linkStyle.colors.contentFocused, textDecoration = TextDecoration.Underline),
            linkHovered = SpanStyle(color = linkStyle.colors.contentHovered, textDecoration = TextDecoration.Underline),
            linkPressed = SpanStyle(color = linkStyle.colors.contentPressed, textDecoration = TextDecoration.Underline),
            linkVisited = SpanStyle(color = linkStyle.colors.contentVisited),
            emphasis = SpanStyle(fontStyle = FontStyle.Italic),
            strongEmphasis = SpanStyle(fontWeight = FontWeight.Bold),
            inlineHtml = SpanStyle()
        )

        fun headingStyle(fontSizeMultiplier: Float, fontWeight: FontWeight): TextStyle {
            return paragraphTextStyle.copy(
                fontSize = paragraphTextStyle.fontSize * fontSizeMultiplier,
                fontWeight = fontWeight
            )
        }

        fun headingInlines(textStyle: TextStyle): InlinesStyling {
            return InlinesStyling(
                textStyle = textStyle,
                inlineCode = textStyle.toSpanStyle().copy(color = globalColors.text.info),
                link = linkSpan,
                linkDisabled = SpanStyle(color = linkStyle.colors.contentDisabled),
                linkFocused = SpanStyle(
                    color = linkStyle.colors.contentFocused,
                    textDecoration = TextDecoration.Underline
                ),
                linkHovered = SpanStyle(
                    color = linkStyle.colors.contentHovered,
                    textDecoration = TextDecoration.Underline
                ),
                linkPressed = SpanStyle(
                    color = linkStyle.colors.contentPressed,
                    textDecoration = TextDecoration.Underline
                ),
                linkVisited = SpanStyle(color = linkStyle.colors.contentVisited),
                emphasis = SpanStyle(fontStyle = FontStyle.Italic),
                strongEmphasis = SpanStyle(fontWeight = FontWeight.Bold),
                inlineHtml = SpanStyle()
            )
        }

        val h1Style: TextStyle = headingStyle(2.0f, FontWeight.Bold)
        val h2Style: TextStyle = headingStyle(1.6f, FontWeight.Bold)
        val h3Style: TextStyle = headingStyle(1.3f, FontWeight.SemiBold)
        val h4Style: TextStyle = headingStyle(1.1f, FontWeight.SemiBold)
        val h5Style: TextStyle = headingStyle(1.0f, FontWeight.Medium)
        val h6Style: TextStyle = headingStyle(1.0f, FontWeight.Medium)

        val numberStyle: TextStyle = paragraphTextStyle.copy(color = globalColors.text.info)

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
            list = MarkdownStyling.List.createListStyling(
                paragraphTextStyle,
                Ordered.createOrderedListStyling(
                    numberStyle = numberStyle
                ),
                Unordered.createUnorderedListStyling(
                    bullet = '\u2022'
                )
            )
        )
    }
}
