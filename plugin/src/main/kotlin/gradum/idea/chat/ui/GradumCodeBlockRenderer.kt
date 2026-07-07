/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumCodeBlockRenderer.kt  2026-07-07 15:19:07 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Shared shape used for the code block panel (and for the inner top row clip). */
private val CodeBlockShape: RoundedCornerShape = RoundedCornerShape(8.dp)

/**
 * Custom Markdown code block renderer that applies Gradum styling to fenced code blocks.
 *
 * Layout: a vertically-stacked panel — a thin top row holding a copy button
 * aligned to the start, then the highlighted code below. The top row reserves
 * its own vertical space so the button never overlaps the first line of code.
 * The outer [Column] owns the rounded shape, background, and border, so the
 * top row + code area read as one continuous panel.
 *
 * The top row also shows the fenced language tag (e.g. `kotlin`, `text`) on
 * the left as muted editor-style text, and a second toolbar button that
 * forwards `(rawCode, language)` to [onInsertAsFile] so the caller can open
 * the snippet as a new untitled editor tab.
 */
@OptIn(ExperimentalJewelApi::class)
class GradumCodeBlockRenderer(
  styling: MarkdownStyling,
  private val onInsertAsFile: (code: String, language: String) -> Unit = { _, _ -> },
) : DefaultMarkdownBlockRenderer(styling) {

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

    val containerModifier: Modifier = modifier
      .clip(CodeBlockShape)
      .background(styling.background)
      .border(styling.borderWidth, styling.borderColor, CodeBlockShape)
      .then(if (styling.fillWidth) Modifier.fillMaxWidth() else Modifier)

    var isSoftWrap by remember { mutableStateOf(false) }

    Column(modifier = containerModifier) {
      CodeBlockToolbar(
        rawCode = block.content,
        language = language,
        isSoftWrap = isSoftWrap,
        onInsertAsFile = onInsertAsFile,
        onSoftWrapToggle = { isSoftWrap = !isSoftWrap }
      )
      // The container choice must follow `isSoftWrap`:
      //  - soft-wrap on → `Box` with a finite max width so `Text` actually wraps
      //  - soft-wrap off → `HorizontallyScrollableContainer` so long lines stay
      //    on a single line and the user can scroll them horizontally
      // `Modifier.horizontalScroll` on a scrollable container removes the
      // finite width constraint that `softWrap = true` needs to fold long
      // lines, so wrapping a soft-wrap-enabled `Text` in a scrollable
      // container is a silent no-op.
      val showHorizontalScroll: Boolean = !isSoftWrap && styling.scrollsHorizontally
      if (showHorizontalScroll) {
        HorizontallyScrollableContainer {
          CodeBlockContent(annotatedCode, styling, isSoftWrap)
        }
      } else {
        Box {
          CodeBlockContent(annotatedCode, styling, isSoftWrap)
        }
      }
    }
  }

  @Composable
  private fun CodeBlockContent(
    annotatedCode: AnnotatedString,
    styling: MarkdownStyling.Code.Fenced,
    softWrap: Boolean,
  ) {
    Text(
      text = annotatedCode,
      style = styling.editorTextStyle,
      modifier = Modifier
        .padding(styling.padding)
        .fillMaxWidth()
        .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true),
      softWrap = softWrap
    )
  }
}

/**
 * Top-row toolbar above the highlighted code.
 *
 * Layout from left to right:
 *  1. **Language tag** — small muted editor-style text (`kotlin`, `python`, …).
 *  2. **Copy button** — copies [rawCode] to the system clipboard, briefly
 *     swaps icon to `Checked` for 1.5 s as click feedback.
 *  3. **Insert-as-file button** — forwards `(rawCode, language)` to
 *     [onInsertAsFile] so the host (which has `Project` access) can open
 *     the snippet as a new untitled editor tab.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun CodeBlockToolbar(
  rawCode: String,
  language: String,
  isSoftWrap: Boolean,
  onInsertAsFile: (code: String, language: String) -> Unit,
  onSoftWrapToggle: () -> Unit
) {
  val scope = rememberCoroutineScope()
  var isCopied: Boolean by remember { mutableStateOf(false) }
  val displayLanguage: String = language.replaceFirstChar { it.uppercase() }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 0.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    Text(
      text = displayLanguage,
      fontWeight = FontWeight.Medium
    )
    Tooltip(tooltip = { Text(text = message("gradum.copy.code.tooltip")) }) {
      IconButton(
        onClick = {
          copyToClipboard(
            scope = scope,
            text = rawCode,
            onCopied = { isCopied = true },
            onReset = { isCopied = false }
          )
        }
      ) {
        Icon(
          contentDescription = message("gradum.copy.code"),
          key = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy
        )
      }
    }
    Tooltip(tooltip = { Text(text = message("gradum.insert.file")) }) {
      IconButton(
        onClick = { onInsertAsFile(rawCode, language) }
      ) {
        Icon(
          key = AllIconsKeys.FileTypes.AddAny,
          contentDescription = message("gradum.new.file")
        )
      }
    }
    Tooltip(tooltip = {
      Text(
        text =
          if (isSoftWrap) message("gradum.soft.wrap.disable")
          else message("gradum.soft.wrap.enable")
      )
    }) {
      IconButton(onClick = onSoftWrapToggle) {
        Icon(
          key = GradumIcons.SoftWarp,
          contentDescription = message("gradum.soft.wrap")
        )
      }
    }
  }
}
