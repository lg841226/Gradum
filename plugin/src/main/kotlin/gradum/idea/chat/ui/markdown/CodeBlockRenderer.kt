/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CodeBlockRenderer.kt  2026-07-30 13:00:41 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.editor.getLanguageIconKey
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

internal val CodeBlockShape: RoundedCornerShape = RoundedCornerShape(GradumSpacing.md)

private const val CODE_COLLAPSE_LIMIT: Int = 40
private val CollapseStripPadding = GradumSpacing.sm

@Composable
internal fun Modifier.codeBlockBorder(): Modifier {
  val borderColor = JewelTheme.globalColors.borders.disabled.copy(alpha = 0.5f)
  return this.clip(CodeBlockShape).border(1.dp, borderColor, CodeBlockShape)
}

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
  private val onInsertAsFile: (code: String, language: String) -> Unit = { _, _ -> }
) : DefaultMarkdownBlockRenderer(styling) {

  @OptIn(ExperimentalJewelApi::class)
  @Composable
  override fun RenderFencedCodeBlock(
    block: FencedCodeBlock, styling: MarkdownStyling.Code.Fenced,
    enabled: Boolean, modifier: Modifier
  ) {
    val language: String = block.language?.takeUnless { it.isBlank() } ?: DEFAULT_CODE_LANGUAGE

    val annotatedCode: AnnotatedString by LocalCodeHighlighter.current
      .highlight(block.content, language)
      .collectAsState(AnnotatedString(block.content))

    val containerModifier: Modifier = modifier
      .codeBlockBorder()
      .background(styling.background)
      .then(if (styling.fillWidth) Modifier.fillMaxWidth() else Modifier)

    var isSoftWrap by remember { mutableStateOf(false) }
    val lineCount = block.content.count { it == '\n' } + 1
    val isCollapsible = lineCount > CODE_COLLAPSE_LIMIT
    var isCollapsed by remember(block.content) { mutableStateOf(isCollapsible) }

    if (isSimplified) {
      ContainerOrScrollable(isSoftWrap, {
        CodeBlockContent(isSoftWrap, annotatedCode, styling)
      }, styling)
    } else {
      Column(modifier = containerModifier) {
        CodeBlockToolbar(
          language = language,
          isSoftWrap = isSoftWrap,
          rawCode = block.content,
          onInsertAsFile = onInsertAsFile,
          onSoftWrapToggle = { isSoftWrap = !isSoftWrap }
        )
        Box(modifier = Modifier.animateContentSize(animationSpec = tween(200))) {
          Column {
            val displayCode = if (isCollapsed && isCollapsible)
              truncateAnnotatedString(annotatedCode, CODE_COLLAPSE_LIMIT)
            else annotatedCode
            ContainerOrScrollable(isSoftWrap, {
              CodeBlockContent(isSoftWrap, displayCode, styling)
            }, styling)
            if (isCollapsible) {
              val hiddenLines = lineCount - CODE_COLLAPSE_LIMIT
              val stripColor = JewelTheme.globalColors.borders.disabled.copy(alpha = 0.15f)
              val textColor = JewelTheme.globalColors.text.info
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { isCollapsed = !isCollapsed }
                  .background(stripColor)
                  .padding(vertical = CollapseStripPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  tint = textColor,
                  contentDescription = null,
                  key = if (isCollapsed) AllIconsKeys.General.ChevronDown
                  else AllIconsKeys.General.ChevronUp
                )
                Spacer(Modifier.width(CollapseStripPadding))
                Text(
                  color = textColor,
                  text = if (isCollapsed)
                    message("gradum.code.expand", hiddenLines)
                  else message("gradum.code.collapse", lineCount - CODE_COLLAPSE_LIMIT),
                  fontFamily = JewelTheme.editorTextStyle.fontFamily
                )
              }
            }
          }
        }
      }
    }
  }

  @Composable
  private fun CodeBlockContent(
    softWrap: Boolean,
    annotatedCode: AnnotatedString,
    styling: MarkdownStyling.Code.Fenced
  ) {
    Text(
      softWrap = softWrap,
      text = annotatedCode,
      style = styling.editorTextStyle,
      modifier = Modifier
        .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.md)
        .fillMaxWidth()
        .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
    )
  }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ContainerOrScrollable(
  isSoftWrap: Boolean,
  content: @Composable () -> Unit,
  styling: MarkdownStyling.Code.Fenced,
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
@Composable
private fun CodeBlockToolbar(
  rawCode: String,
  language: String,
  isSoftWrap: Boolean,
  onInsertAsFile: (code: String, language: String) -> Unit,
  onSoftWrapToggle: () -> Unit
) {
  val coroutineScope = rememberCoroutineScope()
  var isCopied: Boolean by remember { mutableStateOf(false) }
  val displayLanguage: String = language.replaceFirstChar { it.uppercase() }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = GradumSpacing.md, end = GradumSpacing.sm, top = GradumSpacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    Icon(
      key = getLanguageIconKey(language) ?: GradumIcons.FeatCode,
      contentDescription = displayLanguage,
    )
    Text(
      text = message("gradum.code"),
      fontFamily = JewelTheme.editorTextStyle.fontFamily,
      modifier = Modifier.weight(1f)
    )
    Tooltip(tooltip = { Text(text = message("gradum.copy.code.tooltip")) }) {
      IconButton(
        onClick = {
          copyToClipboard(
            scope = coroutineScope,
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

private fun truncateAnnotatedString(annotated: AnnotatedString, maxLines: Int): AnnotatedString {
  val truncateIndex = annotated.text
    .withIndex().filter { it.value == '\n' }
    .drop(maxLines - 1).firstOrNull()
    ?.index ?: annotated.text.length

  return if (truncateIndex < annotated.text.length)
    annotated.subSequence(0, truncateIndex)
  else annotated
}
