/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CodeBlockRenderer.kt  2026-07-31 09:55:31 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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
  override fun RenderFencedCodeBlock(
    block: FencedCodeBlock,
    styling: MarkdownStyling.Code.Fenced,
    enabled: Boolean,
    modifier: Modifier
  ) {
    val language = block.language?.takeUnless { it.isBlank() } ?: DEFAULT_CODE_LANGUAGE

    val annotatedCode by LocalCodeHighlighter.current
      .highlight(block.content, language)
      .collectAsState(AnnotatedString(block.content))

    val containerModifier = modifier
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
        original.copy(
          lineHeight = TextUnit(lineHeight.value / 0.85f, TextUnitType.Sp)
        )
      } else original
    }

    if (isSimplified) {
      ContainerOrScrollable(isSoftWrap) {
        CodeBlockContent(
          softWrap = isSoftWrap,
          annotatedCode = annotatedCode,
          textStyle = codeTextStyle
        )
      }
    } else {
      val sectionId = remember { Any() }
      val stickyRegistry = LocalStickySectionRegistry.current
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
              Offset(
                coords.size.width.toFloat(),
                coords.size.height.toFloat()
              )
            )
            stickyRegistry.updateBounds(
              sectionEntry, Rect(
                topLeft.x, topLeft.y,
                bottomRight.x, bottomRight.y
              )
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

        Box(
          modifier = Modifier
            .animateContentSize(animationSpec = tween(200))
        ) {
          Column {
            val displayCode =
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
                val lineColor = JewelTheme.globalColors.borders.disabled.copy(alpha = 0.5f)
                Box(
                  modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(lineColor)
                )
              }

              Box(modifier = Modifier.weight(1f)) {
                ContainerOrScrollable(isSoftWrap) {
                  CodeBlockContent(
                    softWrap = isSoftWrap,
                    textStyle = codeTextStyle,
                    annotatedCode = displayCode,
                    onTextLayout = { textLayout = it }
                  )
                }
              }
            }

            if (isCollapsible) {
              DisableSelection {
                val hiddenLinesCount = lineCount - CODE_COLLAPSE_LIMIT
                val collapseBarColor = JewelTheme.globalColors.borders.disabled.copy(alpha = 0.15f)
                val textColor = JewelTheme.globalColors.text.info

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isCollapsed = !isCollapsed }
                    .background(collapseBarColor)
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
    annotatedCode: AnnotatedString,
    textStyle: TextStyle,
    onTextLayout: ((TextLayoutResult) -> Unit) = {}
  ) {
    Text(
      softWrap = softWrap,
      text = annotatedCode,
      style = textStyle,
      onTextLayout = onTextLayout,
      modifier = Modifier
        .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.md)
        .fillMaxWidth()
        .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
    )
  }

  @Composable
  private fun LineNumberColumn(
    textLayout: TextLayoutResult,
    textStyle: TextStyle
  ) {
    val textColor = JewelTheme.globalColors.text.disabled

    val displayText = remember(textLayout) {
      val input = textLayout.layoutInput.text.text
      val lineCount = textLayout.lineCount
      val newlineOffsets = buildList {
        input.forEachIndexed { i, c ->
          if (c == '\n') add(i)
        }
      }

      buildString(lineCount * 4) {
        var nlIndex = 0
        var prevSrcLine = 0

        for (displayLine in 0 until lineCount) {
          val offset = textLayout.getLineStart(displayLine)

          while (nlIndex < newlineOffsets.size && newlineOffsets[nlIndex] < offset) {
            nlIndex++
          }

          val srcLine = nlIndex + 1

          if (srcLine != prevSrcLine) {
            append(srcLine.toString())
            prevSrcLine = srcLine
          }

          if (displayLine < lineCount - 1) {
            append('\n')
          }
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

@Composable
private fun ContainerOrScrollable(
  isSoftWrapEnabled: Boolean,
  content: @Composable () -> Unit
) {
  if (!isSoftWrapEnabled) {
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
  var isCopied by remember { mutableStateOf(false) }
  val displayLanguage = language.replaceFirstChar { it.uppercase() }

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
      key = getLanguageIconKey(language) ?: GradumIcons.FeatCode
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
          key = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy
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
          text = if (isSoftWrap) {
            message("gradum.soft.wrap.disable")
          } else {
            message("gradum.soft.wrap.enable")
          }
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
  val truncateIndex = annotated.text
    .withIndex()
    .filter { it.value == '\n' }
    .drop(CODE_COLLAPSE_LIMIT - 1)
    .firstOrNull()
    ?.index ?: annotated.text.length

  return if (truncateIndex < annotated.text.length) {
    annotated.subSequence(0, truncateIndex)
  } else {
    annotated
  }
}
