/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CodeBlockRenderer.kt  2026-07-30 19:44:44 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
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

private const val CODE_COLLAPSE_LIMIT: Int = 20
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
    var showLineNumbers by remember { mutableStateOf(false) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val codeTextStyle = remember(styling) {
      val original = styling.editorTextStyle
      val lineHeight = original.lineHeight
      if (lineHeight.value > 0f) {
        // Bridge multiplies editorTextStyle.lineHeight by 0.85 by default to tighten line spacing.
        // Divide it back here to obtain the actual editor line height, so that line numbers align with code lines.
        original.copy(lineHeight = TextUnit(lineHeight.value / 0.85f, TextUnitType.Sp))
      } else original
    }

    if (isSimplified) {
      ContainerOrScrollable(isSoftWrap, {
        CodeBlockContent(isSoftWrap, annotatedCode, styling, codeTextStyle, showIndentGuides = isCollapsible)
      }, styling)
    } else {
      val stickyRegistry = LocalStickySectionRegistry.current
      val sectionId = remember { Any() }
      val toolbarProvider: @Composable () -> Unit = {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(styling.background)
        ) {
          DisableSelection {
            CodeBlockToolbar(
              language = language,
              isSoftWrap = isSoftWrap,
              isCollapsible = isCollapsible,
              rawCode = block.content,
              onInsertAsFile = onInsertAsFile,
              onSoftWrapToggle = { isSoftWrap = !isSoftWrap },
              onLineNumbersToggle = { showLineNumbers = !showLineNumbers }
            )
          }
        }
      }
      val sectionEntry = remember { stickyRegistry.register(sectionId, toolbarProvider) }
      DisposableEffect(sectionEntry) {
        onDispose { stickyRegistry.unregister(sectionEntry) }
      }
      Column(
        modifier = containerModifier
          .onGloballyPositioned { coords ->
            val topLeft = coords.localToWindow(Offset.Zero)
            val bottomRight = coords.localToWindow(
              Offset(coords.size.width.toFloat(), coords.size.height.toFloat())
            )
            stickyRegistry.updateBounds(
              sectionEntry,
              Rect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            )
          }
      ) {
        DisableSelection {
          CodeBlockToolbar(
            language = language,
            isSoftWrap = isSoftWrap,
            isCollapsible = isCollapsible,
            rawCode = block.content,
            onInsertAsFile = onInsertAsFile,
            onSoftWrapToggle = { isSoftWrap = !isSoftWrap },
            onLineNumbersToggle = { showLineNumbers = !showLineNumbers }
          )
        }
        Box(modifier = Modifier.animateContentSize(animationSpec = tween(200))) {
          Column {
            val displayCode = if (isCollapsed && isCollapsible)
              truncateAnnotatedString(annotatedCode, CODE_COLLAPSE_LIMIT)
            else annotatedCode
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
              if (isCollapsible && showLineNumbers && textLayout != null) {
                DisableSelection {
                  LineNumberColumn(
                    textLayout = textLayout!!,
                    styling = styling,
                    textStyle = codeTextStyle
                  )
                }
                val lineColor = JewelTheme.globalColors.borders.disabled.copy(alpha = 0.5f)
                Box(
                  modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(lineColor)
                )
              }
              Box(modifier = Modifier.weight(1f)) {
                ContainerOrScrollable(isSoftWrap, {
                  CodeBlockContent(isSoftWrap, displayCode, styling, codeTextStyle, showIndentGuides = isCollapsible, onTextLayout = { textLayout = it })
                }, styling)
              }
            }
            if (isCollapsible) {
              DisableSelection {
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
                    text = if (isCollapsed) message("gradum.code.expand", hiddenLines)
                    else message("gradum.code.collapse", lineCount - CODE_COLLAPSE_LIMIT)
                  )
                }
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
    styling: MarkdownStyling.Code.Fenced,
    textStyle: TextStyle = styling.editorTextStyle,
    showIndentGuides: Boolean = false,
    onTextLayout: ((TextLayoutResult) -> Unit) = {},
  ) {
    val textMeasurer = rememberTextMeasurer()
    val indentInfo = remember(annotatedCode.text) { if (showIndentGuides) analyzeIndent(annotatedCode.text) else null }
    val charWidth = remember(textStyle, textMeasurer) {
      if (showIndentGuides && indentInfo != null)
        textMeasurer.measure(AnnotatedString(" "), style = textStyle).size.width.toFloat()
      else 0f
    }

    Box(
      modifier = Modifier
        .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.md)
        .fillMaxWidth()
    ) {
      Text(
        softWrap = softWrap,
        text = annotatedCode,
        style = textStyle,
        onTextLayout = onTextLayout,
        modifier = Modifier
          .fillMaxWidth()
          .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
          .then(
            if (showIndentGuides && indentInfo != null && charWidth > 0f) {
              val lineColor = JewelTheme.globalColors.text.disabled.copy(alpha = 0.18f)
              Modifier.drawWithContent {
                val stepPx = indentInfo.step * charWidth
                for (level in 1..indentInfo.maxLevel) {
                  val x = level * stepPx
                  if (x < size.width)
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                drawContent()
              }
            } else Modifier
          )
      )
    }
  }

  @Composable
  private fun LineNumberColumn(
    textLayout: TextLayoutResult,
    styling: MarkdownStyling.Code.Fenced,
    textStyle: TextStyle = styling.editorTextStyle
  ) {
    val textColor = JewelTheme.globalColors.text.disabled
    val displayText = remember(textLayout) {
      val input = textLayout.layoutInput.text.text
      val lineCount = textLayout.lineCount
      val newlineOffsets = buildList { input.forEachIndexed { i, c -> if (c == '\n') add(i) } }
      buildString(lineCount * 4) {
        var nlIndex = 0
        var prevSrcLine = 0
        for (displayLine in 0 until lineCount) {
          val offset = textLayout.getLineStart(displayLine)
          while (nlIndex < newlineOffsets.size && newlineOffsets[nlIndex] < offset) nlIndex++
          val srcLine = nlIndex + 1
          if (srcLine != prevSrcLine) {
            append(srcLine.toString())
            prevSrcLine = srcLine
          }
          if (displayLine < lineCount - 1) append('\n')
        }
      }
    }
    Text(
      text = displayText,
      color = textColor,
      style = textStyle,
      textAlign = TextAlign.End,
      modifier = Modifier
        .width(IntrinsicSize.Min)
        .padding(start = GradumSpacing.lg)
        .padding(vertical = GradumSpacing.md)
        .padding(end = GradumSpacing.xxl)
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
  isCollapsible: Boolean,
  onSoftWrapToggle: () -> Unit,
  onLineNumbersToggle: () -> Unit,
  onInsertAsFile: (code: String, language: String) -> Unit
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
    if (isCollapsible) {
      Tooltip(tooltip = {
        Text(text = message("gradum.line.numbers"))
      }) {
        IconButton(onClick = onLineNumbersToggle) {
          Icon(
            key = AllIconsKeys.General.Show,
            contentDescription = message("gradum.line.numbers")
          )
        }
      }
    }
  }
}

private data class IndentInfo(val step: Int, val maxLevel: Int)

private fun analyzeIndent(code: String): IndentInfo? {
  val lines = code.lines()
  val counts = lines.mapNotNull { line ->
    if (line.isBlank()) null
    else line.length - line.trimStart().length
  }.filter { it > 0 }.distinct()
  if (counts.isEmpty()) return null
  val step = counts.reduce { a, b -> gcd(a, b) }
  val maxLevel = counts.maxOrNull()!! / step
  return IndentInfo(step, maxLevel)
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun truncateAnnotatedString(annotated: AnnotatedString, maxLines: Int)
  : AnnotatedString {
  val truncateIndex = annotated.text.withIndex()
    .filter { it.value == '\n' }.drop(maxLines - 1).firstOrNull()
    ?.index ?: annotated.text.length
  return if (truncateIndex < annotated.text.length) annotated.subSequence(0, truncateIndex) else annotated
}
