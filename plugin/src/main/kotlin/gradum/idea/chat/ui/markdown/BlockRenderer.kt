/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * BlockRenderer.kt  2026-07-14 21:27:12 Changed by gwy
 */

@file:Suppress(
  "MaximumLineLength",
  "Indentation",
  "FunctionNaming",
  "SpacingBetweenPackageAndImports",
  "NoConsecutiveBlankLines",
  "NoMultipleSpaces",
  "ArgumentListWrapping",
)

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.GradumSpacing
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.linkStyle

private val commonmarkParser: Parser = Parser.builder().build()

private val orderedMarkerColumnMinWidth: Dp = 24.dp
private val unorderedMarkerColumnMinWidth: Dp = 20.dp
private val markerContentGap: Dp = GradumSpacing.sm
private val nestedListIndentStep: Dp = GradumSpacing.lg
private val listItemVerticalSpacing: Dp = GradumSpacing.xs
private val listOuterPadding: PaddingValues = PaddingValues(vertical = GradumSpacing.xs)
private val headingExtraPadding: PaddingValues =
  PaddingValues(top = GradumSpacing.lg, bottom = GradumSpacing.sm)
private val thematicBreakVerticalSpacing: Dp = GradumSpacing.lg
private const val FALLBACK_FONT_SIZE_SP_NO_STYLE: Float = 13f
private const val BODY_LINE_HEIGHT_MULTIPLIER: Float = 1.3f

/** Default language label for code blocks without an explicit language tag. */
internal const val DEFAULT_CODE_LANGUAGE: String = "plain text"


/**
 * Render a [MarkdownSegment.NonProseBlock] using the non-prose block renderer.
 * Re-parses the segment's text with commonmark to recover the AST (the
 * segment was originally produced by [splitPlainAtBlocks] which serialized
 * each block to text).
 */
@Composable
fun RenderNonProseBlock(
  onUrlClick: (String) -> Unit = {},
  segment: MarkdownSegment.NonProseBlock
) {
  val document: Document = remember(segment.text) {
    commonmarkParser.parse(segment.text) as Document
  }

  val children: NodeChildren = NodeChildren.of(document)
  val first: Node? = children.first
  if (first != null) RenderBlockNode(first, onUrlClick = onUrlClick)
  for (childNode in children.rest) RenderBlockNode(childNode, onUrlClick = onUrlClick)
}

/**
 * Dispatch a single CommonMark block node. Unrecognized / out-of-scope block
 * types (HtmlBlock, extension nodes) fall back to plain
 * `Text(serializeMarkdownNode(node))`.
 *
 * All lists — top-level AND nested — go through [RenderBulletList] /
 * [RenderOrderedList] with `indentDepth + 1` recursion so the chat's bullet
 * style, marker column width, item spacing, and the blue `` `code` `` chip
 * apply uniformly. [indentDepth] is NOT used to compound the visible indent
 * (that would make nested lists look ragged) — the inner indent is the
 * same fixed step regardless of how deep we are.
 */
@Composable
fun RenderBlockNode(
  block: Node, indentDepth: Int = 0, onUrlClick: (String) -> Unit = {}
) {
  when (block) {
    is Heading -> RenderHeading(block, onUrlClick)
    is Paragraph -> RenderParagraphWithChips(block, onUrlClick)
    is BulletList -> RenderBulletList(block, indentDepth, onUrlClick)
    is OrderedList -> RenderOrderedList(block, indentDepth, onUrlClick)
    is BlockQuote -> RenderBlockQuote(block, onUrlClick)
    is FencedCodeBlock -> RenderFencedCodeBlock(block)
    is IndentedCodeBlock -> RenderIndentedCodeBlock(block)
    is ThematicBreak -> RenderThematicBreak()
    is TableBlock -> Text(
      text = serializeMarkdownNode(block),
      modifier = Modifier.fillMaxWidth()
    )

    else -> Text(
      text = serializeMarkdownNode(block),
      modifier = Modifier.fillMaxWidth()
    )
  }
}


/** Render a heading with the full [MarkdownStyling.Heading.HN] style + chat-friendly padding. */
@Suppress("MagicNumber")
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderHeading(heading: Heading, onUrlClick: (String) -> Unit) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val headingStyle: MarkdownStyling.Heading.HN = when (heading.level) {
    1 -> styling.heading.h1
    2 -> styling.heading.h2
    3 -> styling.heading.h3
    4 -> styling.heading.h4
    5 -> styling.heading.h5
    else -> styling.heading.h6
  }
  val inlineText: String = serializeInlineChildren(heading)
  RenderInlineTextWithChips(
    text = inlineText,
    style = headingStyle.inlinesStyling.textStyle,
    modifier = Modifier
      .fillMaxWidth()
      .padding(headingStyle.padding)
      .padding(headingExtraPadding),
    onUrlClick = onUrlClick
  )
}


/**
 * Render an unordered list. Each item is its own [RenderListItem] row; the
 * marker column reserves [unorderedMarkerColumnMinWidth]. Nested bullet
 * lists recurse with `indentDepth + 1`. The visible start-padding is
 * "one step if nested, zero otherwise" — not `indentDepth × step` — so
 * deeply-nested lists stay aligned instead of stair-stepping off-screen.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderBulletList(
  list: BulletList,
  indentDepth: Int = 0,
  onUrlClick: (String) -> Unit = {}
) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val unorderedList: MarkdownStyling.List.Unordered = styling.list.unordered
  val listItems: List<ListItem> = collectListItems(list)
  val bulletStyle: TextStyle = unorderedList.bulletStyle
  val startPadding: Dp = if (indentDepth > 0) nestedListIndentStep else 0.dp

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = startPadding)
      .padding(listOuterPadding),
    verticalArrangement = Arrangement.spacedBy(listItemVerticalSpacing)
  ) {
    listItems.forEach { listItem: ListItem ->
      RenderListItem(
        item = listItem,
        prefixText = unorderedList.bullet?.toString() ?: "•",
        prefixStyle = bulletStyle,
        prefixColumnMinWidth = unorderedMarkerColumnMinWidth,
        prefixContentGap = markerContentGap,
        contentStyle = styling.paragraph.inlinesStyling.textStyle,
        onUrlClick = onUrlClick,
        indentDepth = indentDepth,
      )
    }
  }
}


/**
 * Render an ordered list. `prefixColumnMinWidth` is pinned to
 * [orderedMarkerColumnMinWidth] (24 dp) so single-digit "1." / "2."
 * aren't clipped. Visible indent is a single step if nested.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderOrderedList(
  list: OrderedList,
  indentDepth: Int = 0,
  onUrlClick: (String) -> Unit = {}
) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val orderedList: MarkdownStyling.List.Ordered = styling.list.ordered
  val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
  val listItems: List<ListItem> = collectListItems(list)
  val startPadding: Dp = if (indentDepth > 0) nestedListIndentStep else 0.dp

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = startPadding)
      .padding(listOuterPadding),
    verticalArrangement = Arrangement.spacedBy(listItemVerticalSpacing)
  ) {
    listItems.forEachIndexed { index, listItem: ListItem ->
      val number: Int = list.startNumber + index
      RenderListItem(
        item = listItem,
        prefixText = "$number.",
        prefixStyle = orderedList.numberStyle,
        prefixColumnMinWidth = orderedMarkerColumnMinWidth,
        prefixContentGap = markerContentGap,
        contentStyle = contentStyle,
        onUrlClick = onUrlClick,
        indentDepth = indentDepth,
      )
    }
  }
}

private fun collectListItems(listNode: Node): List<ListItem> =
  generateSequence(listNode.firstChild) { it.next }.filterIsInstance<ListItem>().toList()


/**
 * Render a single list item as a `Row { marker column; content }`. The marker
 * column is a fixed-width `Box(contentAlignment = CenterEnd)` so multi-digit
 * numbers right-align against single-digit numbers. For multi-block items the
 * FIRST `Paragraph` child shares a row with the marker; subsequent children
 * (nested list, second paragraph, fenced code) recurse through [RenderBlockNode]
 * with `indentDepth + 1`. When the first child isn't a `Paragraph` the marker
 * is drawn on its own line above the children.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderListItem(
  item: ListItem,
  prefixText: String,
  indentDepth: Int = 0,
  prefixContentGap: Dp,
  prefixStyle: TextStyle,
  contentStyle: TextStyle,
  prefixColumnMinWidth: Dp,
  onUrlClick: (String) -> Unit,
) {
  val children: NodeChildren = NodeChildren.of(item)
  if (children.isEmpty) return
  val first: Node? = children.first

  if (first is Paragraph && children.rest.isEmpty()) {
    val inlineText: String = serializeInlineChildren(first)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
      MarkerColumn(
        prefixText = prefixText,
        prefixStyle = prefixStyle,
        prefixColumnMinWidth = prefixColumnMinWidth,
        prefixContentGap = prefixContentGap,
      )
      RenderInlineTextWithChips(
        text = inlineText,
        style = contentStyle,
        modifier = Modifier.weight(1f),
        onUrlClick = onUrlClick,
      )
    }
    return
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    if (first is Paragraph) {
      val inlineText: String = serializeInlineChildren(first)
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MarkerColumn(
          prefixText = prefixText,
          prefixStyle = prefixStyle,
          prefixColumnMinWidth = prefixColumnMinWidth,
          prefixContentGap = prefixContentGap,
        )
        RenderInlineTextWithChips(
          text = inlineText,
          style = contentStyle,
          modifier = Modifier.weight(1f),
          onUrlClick = onUrlClick,
        )
      }
    } else {
      Text(
        maxLines = 1,
        softWrap = false,
        text = prefixText,
        style = prefixStyle
      )
      if (first != null)
        RenderBlockNode(first, indentDepth + 1, onUrlClick)
    }
    for (childNode in children.rest) {
      RenderBlockNode(childNode, indentDepth + 1, onUrlClick)
    }
  }
}

/** Marker column slot — fixed-width right-aligned Box that always reserves space for the marker. */
@Composable
private fun MarkerColumn(
  prefixText: String,
  prefixContentGap: Dp,
  prefixStyle: TextStyle,
  prefixColumnMinWidth: Dp
) {
  Box(
    modifier = Modifier
      .width(prefixColumnMinWidth)
      .padding(end = prefixContentGap),
    contentAlignment = Alignment.CenterEnd
  ) {
    Text(
      text = prefixText,
      style = prefixStyle,
      textAlign = TextAlign.End,
      maxLines = 1,
      softWrap = false
    )
  }
}


/**
 * Render a block quote as a `Row { border; padded Column { children } }`. The
 * border is drawn as a vertical `Box` of width [lineWidth] in [lineColor]
 * (a non-zero width and a non-transparent color are required to draw the
 * line). The text color is the blockquote color rendered in italic so quotes
 * are visually distinct from body prose.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderBlockQuote(quote: BlockQuote, onUrlClick: (String) -> Unit) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val quoteTextColor = styling.blockQuote.textColor
  val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
    .copy(color = quoteTextColor)
  val quotePadding: PaddingValues = styling.blockQuote.padding
  val borderColor = styling.blockQuote.lineColor
  val borderWidth: Dp = styling.blockQuote.lineWidth
  val indentStart: Dp = quotePadding.calculateStartPadding(LayoutDirection.Ltr)
  val children: NodeChildren = NodeChildren.of(quote)
  Row(modifier = Modifier.fillMaxWidth()) {
    if (borderWidth.value > 0f && borderColor.alpha > 0f) {
      Box(
        modifier = Modifier
          .width(borderWidth)
          .background(borderColor)
      )
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = indentStart)
    ) {
      val first: Node? = children.first
      if (first != null) RenderBlockQuoteChild(first, contentStyle, onUrlClick)
      for (childNode in children.rest) {
        RenderBlockQuoteChild(childNode, contentStyle, onUrlClick)
      }
    }
  }
}

/** Render one block-quote child with the blockquote text color applied to inline text. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderBlockQuoteChild(
  node: Node,
  quoteTextStyle: TextStyle,
  onUrlClick: (String) -> Unit,
) {
  when (node) {
    is Paragraph -> {
      val inlineText: String = serializeInlineChildren(node)
      RenderInlineTextWithChips(
        text = inlineText,
        style = quoteTextStyle,
        onUrlClick = onUrlClick,
        modifier = Modifier.fillMaxWidth()
      )
    }

    else -> RenderBlockNode(node, onUrlClick = onUrlClick)
  }
}


/**
 * Render a fenced code block via the project's [GradumCodeBlockRenderer]. Fetches
 * the renderer through [LocalMarkdownBlockRenderer] and calls its
 * `RenderFencedCodeBlock` method directly — no `Markdown(...)` wrapper, no
 * double-parse, no loss of toolbar / language tag / copy / insert-as-file.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderFencedCodeBlock(block: FencedCodeBlock) {
  val blockRenderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current
  val markdownStyling: MarkdownStyling = rememberGradumMarkdownStyling()
  val fencedStyling: MarkdownStyling.Code.Fenced = markdownStyling.code.fenced
  val markdownBlock: MarkdownBlock.CodeBlock.FencedCodeBlock = remember(block) {
    parseFencedCodeBlock(block)
  }
  blockRenderer.RenderFencedCodeBlock(
    enabled = true,
    block = markdownBlock,
    styling = fencedStyling,
    modifier = Modifier.fillMaxWidth()
  )
}

/** Render an indented code block — same strategy as [RenderFencedCodeBlock] but no language tag. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderIndentedCodeBlock(block: IndentedCodeBlock) {
  val blockRenderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current
  val markdownStyling: MarkdownStyling = rememberGradumMarkdownStyling()
  val indentedStyling: MarkdownStyling.Code.Indented = markdownStyling.code.indented
  val markdownBlock: MarkdownBlock.CodeBlock.IndentedCodeBlock = remember(block) {
    parseIndentedCodeBlock(block)
  }
  blockRenderer.RenderIndentedCodeBlock(
    block = markdownBlock,
    styling = indentedStyling,
    enabled = true,
    modifier = Modifier.fillMaxWidth()
  )
}

@OptIn(ExperimentalJewelApi::class)
private fun parseFencedCodeBlock(block: FencedCodeBlock): MarkdownBlock.CodeBlock.FencedCodeBlock {
  val markdownSource = "```${block.info ?: ""}\n${block.literal ?: ""}\n```"
  val blocks: List<MarkdownBlock> = GradumMarkdownProcessor.processMarkdownDocument(markdownSource)
  val first: MarkdownBlock = blocks.first()
  require(first is MarkdownBlock.CodeBlock.FencedCodeBlock) {
    "Expected FencedCodeBlock from re-parse, got ${first::class.simpleName}"
  }
  return first
}

@OptIn(ExperimentalJewelApi::class)
private fun parseIndentedCodeBlock(
  block: IndentedCodeBlock,
): MarkdownBlock.CodeBlock.IndentedCodeBlock {
  // Indented code blocks need a leading blank line to be recognized as a separate block.
  val rawLines: List<String> = (block.literal ?: "").lines()
  val indented: String = buildString {
    append('\n')
    rawLines.forEach { line -> append("    ").append(line).append('\n') }
  }
  val blocks: List<MarkdownBlock> = GradumMarkdownProcessor.processMarkdownDocument(indented)
  val first: MarkdownBlock = blocks.first()
  require(first is MarkdownBlock.CodeBlock.IndentedCodeBlock) {
    "Expected IndentedCodeBlock from re-parse, got ${first::class.simpleName}"
  }
  return first
}

/** Render `---` as a vertical spacer only (user feedback: not a horizontal line). */
@Composable
private fun RenderThematicBreak() {
  Spacer(modifier = Modifier.height(thematicBreakVerticalSpacing))
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderParagraphWithChips(paragraph: Paragraph, onUrlClick: (String) -> Unit) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val baseStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
  val inlineText: String = serializeInlineChildren(paragraph)

  RenderInlineTextWithChips(
    text = inlineText,
    style = baseStyle,
    onUrlClick = onUrlClick,
    modifier = Modifier.fillMaxWidth()
  )
}


/**
 * Re-parse the given inline text with the chip parser and render it as
 * `Text(annotated, inlineContent = ...)`. The fontSize is derived from
 * `style.fontSize` so the chip's `Placeholder` width / height matches the
 * surrounding text.
 *
 * [onUrlClick] is invoked when the user taps a [UrlAnnotation] range. Tap
 * position is mapped to a text offset via [TextLayoutResult.getOffsetForPosition]
 * and the first covering [UrlAnnotation] is matched (LayoutCoordinates
 * doesn't have `getOffsetForPosition` on this Compose version).
 *
 * Cursor: when the rendered text has URL annotations, a `PointerIcon.Hand`
 * hover icon is set with `overrideDescendants = true` so the user sees the
 * link affordance when hovering anywhere over the link range (not just the
 * exact glyph).
 *
 * Safety-net: if the chip parser bails, render the raw text in the same
 * style.
 */
@Composable
fun RenderInlineTextWithChips(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  onUrlClick: (String) -> Unit = {}
) {
  if (text.isEmpty()) return
  val fontSizeSp: Float = style.fontSize.value.let { if (it <= 0f) FALLBACK_FONT_SIZE_SP_NO_STYLE else it }
  val textColor: Color = style.color.let { colorValue ->
    if (colorValue == Color.Unspecified) JewelTheme.contentColor else colorValue
  }
  val chipTintColor: Color = textColor
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val parseOutcome: InlineMarkdownRenderResult = remember(text) {
    parseInlineMarkdown(
      plainText = text,
      fontSizeSp = fontSizeSp,
      chipTint = chipTintColor,
      linkColor = linkColor,
    )
  }
  val resolvedStyle: TextStyle = style.copy(lineHeight = style.fontSize * BODY_LINE_HEIGHT_MULTIPLIER)
  if (parseOutcome.render != null) {
    val inlineRender: InlineMarkdownRender = parseOutcome.render
    val urlAnnotations: List<UrlAnnotation> = inlineRender.urlAnnotations

    if (urlAnnotations.isNotEmpty() && onUrlClick !== NoOpUrlClick) {
      val layoutResultRef: androidx.compose.runtime.MutableState<androidx.compose.ui.text.TextLayoutResult?> =
        remember(urlAnnotations) { mutableStateOf(null) }

      val gestureModifier: Modifier = Modifier.pointerHoverIcon(
        icon = PointerIcon.Hand,
        overrideDescendants = true,
      ).pointerInput(urlAnnotations) {
        detectTapGestures { offset ->
          val layout: androidx.compose.ui.text.TextLayoutResult? = layoutResultRef.value
          if (layout != null) {
            val textOffset: Int = layout.getOffsetForPosition(offset)
            val match: UrlAnnotation? = urlAnnotations.firstOrNull { ann ->
              textOffset >= ann.start && textOffset < ann.end
            }
            if (match != null) onUrlClick(match.url)
          }
        }
      }

      Text(
        text = inlineRender.annotated,
        inlineContent = inlineRender.inlineContent,
        style = resolvedStyle,
        modifier = modifier.then(gestureModifier),
        onTextLayout = { layoutResultRef.value = it },
      )
    } else {
      Text(
        text = inlineRender.annotated,
        inlineContent = inlineRender.inlineContent,
        style = resolvedStyle,
        modifier = modifier
      )
    }
  } else {
    Text(
      text = text,
      style = resolvedStyle,
      modifier = modifier
    )
  }
}

/** Sentinel default for [RenderInlineTextWithChips.onUrlClick] — lets `!==` distinguish a real handler. */
private val NoOpUrlClick: (String) -> Unit = {}
