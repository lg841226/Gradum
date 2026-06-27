/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumCodeBlockRenderer.kt  2026-06-27 15:20:00 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.*

class GradumCodeBlockRenderer(
    styling: MarkdownStyling,
) : DefaultMarkdownBlockRenderer(styling) {

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
            .background(styling.background, styling.shape)
            .border(styling.borderWidth, styling.borderColor, styling.shape)
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
