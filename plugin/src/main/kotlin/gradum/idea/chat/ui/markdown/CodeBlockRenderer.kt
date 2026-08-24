/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CodeBlockRenderer.kt  2026-08-24 23:39:10 Changed by gwy
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.editor.getLanguageIconKey
import gradum.idea.settings.LocalEnableStickySections
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys


internal val CodeBlockCornerRadius: RoundedCornerShape =
  RoundedCornerShape(GradumSpacing.md)

/** Top-only corner radius for the sticky toolbar / header overlays. */
internal val StickySectionTopCorners: RoundedCornerShape =
  RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp)

private const val CODE_COLLAPSE_LIMIT: Int = 20
private val CollapseStripPadding = GradumSpacing.sm

@OptIn(ExperimentalJewelApi::class)
class GradumCodeBlockRenderer(
  styling: MarkdownStyling,
  private val isSimplified: Boolean = false,
  private val onInsertAsFile: (code: String, language: String) -> Unit = { _, _ -> }
) : DefaultMarkdownBlockRenderer(styling) {

  @OptIn(ExperimentalJewelApi::class)
  @Composable
  override fun RenderFencedCodeBlock(block: FencedCodeBlock, styling: MarkdownStyling.Code.Fenced, enabled: Boolean, modifier: Modifier) {
    val language: String = block.language?.takeUnless {
      it.isBlank()
    } ?: DEFAULT_CODE_LANGUAGE

    val rawAnnotatedCode: AnnotatedString by LocalCodeHighlighter.current
      .highlight(code = block.content, language)
      .collectAsState(initial = AnnotatedString(text = block.content))

    val thinkingMode: Boolean = LocalThinkingMode.current
    val thinkingGray: Color =
      if (thinkingMode) JewelTheme.globalColors.text.info
      else Color.Unspecified
    val annotatedCode: AnnotatedString = remember(
      key1 = rawAnnotatedCode,
      key2 = thinkingMode,
      key3 = thinkingGray
    ) {
      if (thinkingMode) {
        overrideAnnotatedStringColors(
          annotated = rawAnnotatedCode,
          overrideColor = thinkingGray
        )
      } else {
        rawAnnotatedCode
      }
    }

    val containerModifier: Modifier = modifier
      .clip(shape = CodeBlockCornerRadius)
      .background(Color.Transparent)
      .then(other = if (styling.fillWidth) Modifier.fillMaxWidth() else Modifier)

    val lineCount: Int = block.content.count { it == '\n' } + 1
    val isCollapsible: Boolean = lineCount > CODE_COLLAPSE_LIMIT
    var isSoftWrap: Boolean by remember { mutableStateOf(value = false) }
    var showLineNumbers: Boolean by remember { mutableStateOf(value = false) }
    var textLayout: TextLayoutResult? by remember { mutableStateOf(value = null) }
    var isCollapsed: Boolean by remember(key1 = block.content) { mutableStateOf(value = isCollapsible) }

    val codeTextStyle: TextStyle = remember(key1 = styling) {
      val original: TextStyle = styling.editorTextStyle
      val lineHeight = original.lineHeight
      if (lineHeight.value > 0f) {
        original.copy(
          lineHeight = TextUnit(
            value = lineHeight.value / 0.85f,
            type = TextUnitType.Sp
          )
        )
      } else original
    }

    if (isSimplified) {
      ContainerOrScrollable(isSoftWrapEnabled = isSoftWrap) {
        CodeBlockContent(
          softWrap = isSoftWrap,
          textStyle = codeTextStyle,
          annotatedCode = annotatedCode
        )
      }
    } else {
      val sectionId = remember { Any() }
      val stickyRegistry: StickySectionRegistry = LocalStickySectionRegistry.current
      val enableSticky: Boolean = LocalEnableStickySections.current
      val toolbarProvider: @Composable () -> Unit = {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(StickySectionTopCorners)
            .background(Color.Transparent)
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

      val sectionEntry = if (enableSticky) remember {
        stickyRegistry.register(sectionId, toolbarProvider)
      } else null

      if (enableSticky) {
        DisposableEffect(key1 = sectionEntry) {
          onDispose {
            if (sectionEntry != null) stickyRegistry.unregister(sectionEntry)
          }
        }
      }

      Column(
        modifier = containerModifier
          .then(
            other = if (enableSticky && sectionEntry != null) {
              Modifier.onGloballyPositioned { coords ->
                val topLeft: Offset = coords.localToWindow(Offset.Zero)
                val bottomRight: Offset = coords.localToWindow(
                  relativeToLocal = Offset(
                    x = coords.size.width.toFloat(),
                    y = coords.size.height.toFloat()
                  )
                )
                stickyRegistry.updateBounds(
                  sectionEntry, boundsInWindow = Rect(
                    left = topLeft.x, topLeft.y,
                    right = bottomRight.x, bottomRight.y
                  )
                )
              }
            } else Modifier)
      ) {
        DisableSelection {
          CodeBlockToolbar(
            language = language,
            isSoftWrap = isSoftWrap,
            rawCode = block.content,
            isCollapsible = isCollapsible,
            onInsertAsFile = onInsertAsFile,
            onSoftWrapToggle = { isSoftWrap = !isSoftWrap },
            onLineNumbersToggle = { showLineNumbers = !showLineNumbers }
          )
        }

        Box(
          modifier = Modifier
            .animateContentSize(animationSpec = tween(delayMillis = 200))
        ) {
          Column {
            val displayCode: AnnotatedString =
              if (isCollapsed && isCollapsible) truncateAnnotatedString(annotatedCode)
              else annotatedCode

            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
              if (isCollapsible && showLineNumbers && textLayout != null) {
                DisableSelection {
                  LineNumberColumn(
                    textLayout = textLayout!!,
                    textStyle = codeTextStyle
                  )
                }
                val lineColor: Color = JewelTheme.globalColors.borders.disabled.copy(alpha = 0.5f)
                Box(
                  modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(lineColor)
                )
              }

              Box(modifier = Modifier.weight(1f)) {
                ContainerOrScrollable(isSoftWrapEnabled = isSoftWrap) {
                  CodeBlockContent(
                    softWrap = isSoftWrap,
                    textStyle = codeTextStyle,
                    annotatedCode = displayCode
                  ) { textLayout = it }
                }
              }
            }

            if (isCollapsible) {
              DisableSelection {
                val textColor: Color = JewelTheme.globalColors.text.info
                val hiddenLinesCount: Int = lineCount - CODE_COLLAPSE_LIMIT

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isCollapsed = !isCollapsed }
                    .padding(vertical = CollapseStripPadding),
                  horizontalArrangement = Arrangement.Center,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    tint = textColor,
                    contentDescription = null,
                    key =
                      if (isCollapsed) AllIconsKeys.General.ChevronDown
                      else AllIconsKeys.General.ChevronUp
                  )
                  Spacer(Modifier.width(CollapseStripPadding))
                  Text(
                    color = textColor,
                    text = if (isCollapsed) {
                      message("gradum.code.expand", hiddenLinesCount)
                    } else {
                      message("gradum.code.collapse", lineCount - CODE_COLLAPSE_LIMIT)
                    }
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
    textStyle: TextStyle,
    annotatedCode: AnnotatedString,
    onTextLayout: ((TextLayoutResult) -> Unit) = {}
  ) {
    Text(
      style = textStyle,
      softWrap = softWrap,
      text = annotatedCode,
      onTextLayout = onTextLayout,
      modifier = Modifier
        .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.md)
        .fillMaxWidth()
        .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
    )
  }

  @Composable
  private fun LineNumberColumn(
    textLayout: TextLayoutResult, textStyle: TextStyle
  ) {
    val lineNumberColor: Color = JewelTheme.globalColors.text.disabled

    val displayText: String = remember(textLayout) {
      val sourceText: String = textLayout.layoutInput.text.text
      val totalDisplayLines: Int = textLayout.lineCount
      val newlinePositions: List<Int> = buildList {
        sourceText.forEachIndexed { index: Int, char: Char ->
          if (char == '\n')
            add(index)
        }
      }

      buildString(capacity = totalDisplayLines * 4) {
        var newlineIndex = 0
        var previousSourceLine = 0

        for (displayLineIndex: Int in 0 until totalDisplayLines) {
          val lineStartOffset: Int = textLayout.getLineStart(displayLineIndex)

          while (newlineIndex < newlinePositions.size && newlinePositions[newlineIndex] < lineStartOffset) {
            newlineIndex++
          }

          val sourceLineNumber: Int = newlineIndex + 1

          if (sourceLineNumber != previousSourceLine) {
            append(sourceLineNumber.toString())
            previousSourceLine = sourceLineNumber
          }

          if (displayLineIndex < totalDisplayLines - 1) append('\n')
        }
      }
    }

    Text(
      text = displayText,
      color = lineNumberColor,
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

@Composable
private fun ContainerOrScrollable(
  isSoftWrapEnabled: Boolean, content: @Composable () -> Unit
) {
  if (!isSoftWrapEnabled) HorizontalScrollContainer { content() }
  else Box { content() }
}

@Composable
private fun HorizontalScrollContainer(content: @Composable () -> Unit) {
  val scrollState: ScrollState = rememberScrollState()
  Row(modifier = Modifier.horizontalScroll(scrollState)) {
    content()
  }
  HorizontalScrollbar(
    scrollState = scrollState,
    modifier = Modifier.fillMaxWidth()
  )
}

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
  val coroutineScope: CoroutineScope = rememberCoroutineScope()
  var isCopied: Boolean by remember { mutableStateOf(value = false) }
  val displayLanguage: String = language.replaceFirstChar { it.uppercase() }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        start = GradumSpacing.md,
        end = GradumSpacing.sm,
        top = GradumSpacing.sm
      ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    Icon(
      contentDescription = displayLanguage,
      key = getLanguageIconKey(extension = language) ?: GradumIcons.FeatCode
    )
    Text(
      modifier = Modifier.weight(1f),
      text = message("gradum.code"),
      fontFamily = JewelTheme.editorTextStyle.fontFamily
    )
    Tooltip(tooltip = { Text(message("gradum.copy.code.tooltip")) }) {
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
          key =
            if (isCopied) AllIconsKeys.Actions.Checked
            else AllIconsKeys.General.Copy
        )
      }
    }

    Tooltip(tooltip = { Text(message("gradum.insert.file")) }) {
      IconButton(
        onClick = { onInsertAsFile(rawCode, language) }
      ) {
        Icon(
          key = AllIconsKeys.FileTypes.AddAny,
          contentDescription = message("gradum.new.file")
        )
      }
    }

    Tooltip(
      tooltip = {
        Text(
          text =
            if (isSoftWrap) message("gradum.soft.wrap.disable")
            else message("gradum.soft.wrap.enable")
        )
      }
    ) {
      IconButton(onClick = onSoftWrapToggle) {
        Icon(
          key = GradumIcons.SoftWarp,
          contentDescription = message("gradum.soft.wrap")
        )
      }
    }

    if (isCollapsible) {
      Tooltip(tooltip = { Text(message("gradum.line.numbers")) }) {
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

private fun truncateAnnotatedString(annotated: AnnotatedString): AnnotatedString {
  val collapseThreshold: Int = CODE_COLLAPSE_LIMIT - 1
  val newlineIndices: List<Int> = annotated.text
    .withIndex()
    .filter { it.value == '\n' }
    .map { it.index }

  val truncateIndex: Int =
    if (newlineIndices.size >= CODE_COLLAPSE_LIMIT)
      newlineIndices[collapseThreshold]
    else annotated.text.length

  return if (truncateIndex < annotated.text.length)
    annotated.subSequence(0, truncateIndex)
  else annotated
}

/**
 * Returns a copy of [annotated] with every [SpanStyle.color] replaced by
 * [overrideColor]. All other span properties (fontSize, fontWeight,
 * fontFamily, background, etc.) are preserved verbatim.
 *
 * Used in thinking mode to override syntax-highlighting colors so the
 * entire code block renders in a uniform muted gray.
 */
private fun overrideAnnotatedStringColors(annotated: AnnotatedString, overrideColor: Color): AnnotatedString {
  if (annotated.spanStyles.isEmpty()) {
    return AnnotatedString(annotated.text, SpanStyle(color = overrideColor))
  }
  val builder = AnnotatedString.Builder(annotated.text)
  for ((item: SpanStyle, start: Int, end: Int) in annotated.spanStyles) {
    builder.addStyle(item.copy(color = overrideColor), start, end)
  }
  return builder.toAnnotatedString()
}
