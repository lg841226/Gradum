/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumCodeBlockRenderer.kt  2026-06-29 10:02:39 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.HorizontallyScrollableContainer
import org.jetbrains.jewel.ui.component.Text

/**
 * Custom Markdown code block renderer that applies Gradum styling to fenced code blocks.
 */
@OptIn(ExperimentalJewelApi::class)
class GradumCodeBlockRenderer(
    styling: MarkdownStyling,
) : DefaultMarkdownBlockRenderer(styling) {

    private val codeBlockShape = RoundedCornerShape(8.dp)

    @OptIn(ExperimentalJewelApi::class)
    @Composable
    override fun RenderFencedCodeBlock(
        block: FencedCodeBlock,
        styling: MarkdownStyling.Code.Fenced,
        enabled: Boolean,
        modifier: Modifier,
    ) {
        val language: String = block.language ?: "text"

        val annotatedCode: AnnotatedString by LocalCodeHighlighter.current
            .highlight(block.content, language)
            .collectAsState(AnnotatedString(block.content))

        val containerModifier = modifier
            .clip(codeBlockShape)
            .background(styling.background)
            .border(styling.borderWidth, styling.borderColor, codeBlockShape)
            .then(if (styling.fillWidth) Modifier.fillMaxWidth() else Modifier)

        if (styling.scrollsHorizontally) {
            HorizontallyScrollableContainer(containerModifier) {
                CodeBlockContent(annotatedCode, styling)
            }
        } else {
            Box(containerModifier) {
                CodeBlockContent(annotatedCode, styling)
            }
        }
    }

    @Composable
    private fun CodeBlockContent(
        annotatedCode: AnnotatedString,
        styling: MarkdownStyling.Code.Fenced,
    ) {
        Text(
            text = annotatedCode,
            style = styling.editorTextStyle,
            modifier = Modifier
                .padding(styling.padding)
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true),
        )
    }
}
