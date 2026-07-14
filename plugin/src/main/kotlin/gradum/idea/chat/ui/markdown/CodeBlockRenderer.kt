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

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val CodeBlockShape: RoundedCornerShape = RoundedCornerShape(8.dp)

/**
 * Custom Markdown code block renderer. Renders a fenced code block as a
 * vertically-stacked panel — a thin toolbar (language tag + copy / insert-as-file /
 * soft-wrap buttons) above the highlighted code. The outer [Column] owns the
 * rounded shape, background, and border so the toolbar + code area read as one
 * continuous panel.
 */
@OptIn(ExperimentalJewelApi::class)
class GradumCodeBlockRenderer(
  styling: MarkdownStyling,
  private val isSimplified: Boolean = false,
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
    val language: String = block.language?.takeUnless { it.isBlank() } ?: DEFAULT_CODE_LANGUAGE

    val annotatedCode: AnnotatedString by LocalCodeHighlighter.current
      .highlight(block.content, language)
      .collectAsState(AnnotatedString(block.content))

    val containerModifier: Modifier = modifier
      .clip(CodeBlockShape)
      .background(styling.background)
      .border(styling.borderWidth, styling.borderColor, CodeBlockShape)
      .then(if (styling.fillWidth) Modifier.fillMaxWidth() else Modifier)

    var isSoftWrap by remember { mutableStateOf(false) }

    if (isSimplified) {
      // Simplified mode (used by ThinkingIndicator) drops the toolbar entirely;
      // the surrounding reasoning text is already greyed and the affordances
      // would compete with the rest of the reasoning block.
      ContainerOrScrollable(isSoftWrap, styling) {
        CodeBlockContent(annotatedCode, styling, isSoftWrap)
      }
    } else {
      Column(modifier = containerModifier) {
        CodeBlockToolbar(
          rawCode = block.content,
          language = language,
          isSoftWrap = isSoftWrap,
          onInsertAsFile = onInsertAsFile,
          onSoftWrapToggle = { isSoftWrap = !isSoftWrap }
        )
        // soft-wrap on → `Box` (finite max width so `Text` wraps)
        // soft-wrap off → `HorizontalScrollContainer` (long lines scroll horizontally)
        // A `horizontalScroll` modifier would also strip the finite width
        // that `softWrap = true` needs, so wrapping a soft-wrap-enabled
        // `Text` in a scrollable container is a silent no-op.
        ContainerOrScrollable(isSoftWrap, styling) {
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

@Composable
private fun ContainerOrScrollable(
  isSoftWrap: Boolean,
  styling: MarkdownStyling.Code.Fenced,
  content: @Composable () -> Unit,
) {
  val showHorizontalScroll: Boolean = !isSoftWrap && styling.scrollsHorizontally
  if (showHorizontalScroll) {
    HorizontalScrollContainer { content() }
  } else {
    Box { content() }
  }
}

@Composable
private fun HorizontalScrollContainer(content: @Composable () -> Unit) {
  val scrollState = rememberScrollState()
  Row(modifier = Modifier.horizontalScroll(scrollState)) {
    content()
  }
  HorizontalScrollbar(
    scrollState = scrollState,
    modifier = Modifier.fillMaxWidth()
  )
}

/**
 * Toolbar above the highlighted code: language tag + copy + insert-as-file +
 * soft-wrap toggle. Same padding/arrangement as the table toolbar so the two
 * read as siblings.
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
      .padding(start = GradumSpacing.md, end = GradumSpacing.sm, top = GradumSpacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    Text(
      text = displayLanguage,
      fontWeight = FontWeight.Medium,
      style = JewelTheme.editorTextStyle
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
