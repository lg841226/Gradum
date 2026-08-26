/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * BlockRenderer.kt  2026-08-26 12:40:46 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString.Range
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumSpacing
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LinkStyle
import org.jetbrains.jewel.ui.theme.linkStyle

internal val blockReparseParser: Parser = Parser.builder()
  .extensions(listOf(StrikethroughExtension.create(), LatexBlockExtension.create()))
  .build()


/** Default language label for code blocks without an explicit language tag. */
internal const val DEFAULT_CODE_LANGUAGE: String = "plain text"


/**
 * Render a [MarkdownSegment.NonProseBlock] using the non-prose block renderer.
 * Reparses the segment's text with commonmark to recover the AST (the
 * segment was originally produced by [splitPlainAtBlocks] which serialized
 * each block to text).
 *
 * @param isSimplified When `true`, code blocks render without toolbars/copy buttons.
 * @param thinkingMode When `true`, code blocks use muted gray colors.
 */
@Composable
fun RenderNonProseBlock(
  onUrlClick: (String) -> Unit = {},
  segment: MarkdownSegment.NonProseBlock,
  isSimplified: Boolean = false,
  thinkingMode: Boolean = false
) {
  val document: Document = remember(key1 = segment.text) {
    blockReparseParser.parse(segment.text) as Document
  }

  val children: NodeChildren = NodeChildren.of(parent = document)
  val firstNode: Node? = children.first
  if (firstNode != null) RenderBlockNode(
    block = firstNode,
    onUrlClick = onUrlClick,
    isSimplified = isSimplified,
    thinkingMode = thinkingMode
  )
  for (childNode: Node in children.rest)
    RenderBlockNode(
      block = childNode,
      onUrlClick = onUrlClick,
      isSimplified = isSimplified,
      thinkingMode = thinkingMode
    )
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
  block: Node, indentDepth: Int = 0, onUrlClick: (String) -> Unit = {},
  isSimplified: Boolean = false, thinkingMode: Boolean = false
) {
  when (block) {
    is ThematicBreak -> RenderThematicBreak()
    is Heading -> RenderHeading(block, onUrlClick)
    is FencedCodeBlock -> RenderFencedCodeBlock(block)
    is BlockQuote -> RenderBlockQuote(block, onUrlClick, isSimplified, thinkingMode)
    is IndentedCodeBlock -> RenderIndentedCodeBlock(block)
    is BulletList -> RenderBulletList(block, indentDepth, onUrlClick, isSimplified, thinkingMode)
    is OrderedList -> RenderOrderedList(block, indentDepth, isSimplified, thinkingMode, onUrlClick)
    is Paragraph -> RenderParagraphWithChips(paragraph = block, onUrlClick)
    is TableBlock -> ScrollableTable(
      table = block.toMarkdownSegmentTable(),
      modifier = Modifier.fillMaxWidth(),
      onUrlClick = onUrlClick
    )

    is LatexBlock -> RenderLatexBlock(
      formula = block.formula,
      modifier = Modifier.fillMaxWidth()
    )

    else -> Text(
      modifier = Modifier.fillMaxWidth(),
      text = serializeMarkdownNode(rootNode = block)
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

  val parseOutcome: InlineMarkdownRenderResult =
    rememberInlineMarkdownRenderFromNode(parentNode = heading)
  RenderInlineRender(
    modifier = Modifier
      .fillMaxWidth()
      .padding(paddingValues = headingStyle.padding)
      .padding(paddingValues = MarkdownStyle.Block.HEADING_EXTRA_PADDING),
    onUrlClick = onUrlClick,
    parseOutcome = parseOutcome,
    style = headingStyle.inlinesStyling.textStyle,
    fallbackText = serializeInlineChildren(containerNode = heading),
  )
}


/**
 * Render an unordered list. Each item is its own [RenderListItem] row; the
 * marker column reserves [MarkdownStyle.Block.UNORDERED_MARKER_MIN_WIDTH]. Nested bullet
 * lists recurse with `indentDepth + 1`. The visible start-padding is
 * "one step if nested, zero otherwise" — not `indentDepth × step` — so
 * deeply-nested lists stay aligned instead of stair-stepping off-screen.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderBulletList(
  list: BulletList, indentDepth: Int = 0, onUrlClick: (String) -> Unit = {},
  isSimplified: Boolean = false, thinkingMode: Boolean = false
) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val unorderedList: MarkdownStyling.List.Unordered = styling.list.unordered
  val listItems: List<ListItem> = collectListItems(listNode = list)
  val bulletStyle: TextStyle = unorderedList.bulletStyle
  val startPadding: Dp = if (indentDepth > 0) MarkdownStyle.Block.NESTED_LIST_INDENT_STEP else 0.dp

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = startPadding)
      .padding(paddingValues = MarkdownStyle.Block.LIST_OUTER_PADDING),
    verticalArrangement = Arrangement.spacedBy(MarkdownStyle.Block.LIST_ITEM_VERTICAL_SPACING)
  ) {
    listItems.forEach { listItem: ListItem ->
      val taskMarker: TaskListMarker? = (listItem.firstChild as? Paragraph)?.let(block = ::extractTaskListMarker)
      if (taskMarker != null) {
        RenderTaskListItem(
          item = listItem,
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          isSimplified = isSimplified,
          thinkingMode = thinkingMode,
          isChecked = taskMarker.checked,
          contentStyle = styling.paragraph.inlinesStyling.textStyle
        )
      } else {
        RenderListItem(
          item = listItem,
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          prefixText = unorderedList.bullet.toString(),
          listStyle = ListItemStyle(
            prefixStyle = bulletStyle,
            prefixContentGap = MarkdownStyle.Block.MARKER_CONTENT_GAP,
            prefixColumnMinWidth = MarkdownStyle.Block.UNORDERED_MARKER_MIN_WIDTH,
            contentStyle = styling.paragraph.inlinesStyling.textStyle
          ),
          isSimplified = isSimplified,
          thinkingMode = thinkingMode
        )
      }
    }
  }
}


/**
 * Render an ordered list. `prefixColumnMinWidth` is pinned to
 * [MarkdownStyle.Block.ORDERED_MARKER_MIN_WIDTH] (24 dp) so single-digit "1." / "2."
 * aren't clipped. Visible indent is a single step if nested.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderOrderedList(
  list: OrderedList,
  indentDepth: Int = 0,
  isSimplified: Boolean = false,
  thinkingMode: Boolean = false,
  onUrlClick: (String) -> Unit = {}
) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val listItems: List<ListItem> = collectListItems(listNode = list)
  val orderedList: MarkdownStyling.List.Ordered = styling.list.ordered
  val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
  val startPadding: Dp = if (indentDepth > 0) MarkdownStyle.Block.NESTED_LIST_INDENT_STEP else 0.dp

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = startPadding)
      .padding(paddingValues = MarkdownStyle.Block.LIST_OUTER_PADDING),
    verticalArrangement = Arrangement.spacedBy(MarkdownStyle.Block.LIST_ITEM_VERTICAL_SPACING)
  ) {
    listItems.forEachIndexed { index: Int, listItem: ListItem ->
      @Suppress("DEPRECATION")
      val number: Int = list.startNumber + index
      val taskMarker: TaskListMarker? = (listItem.firstChild as? Paragraph)
        ?.let(block = ::extractTaskListMarker)
      if (taskMarker != null) {
        RenderTaskListItem(
          item = listItem,
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          contentStyle = contentStyle,
          isChecked = taskMarker.checked,
          isSimplified = isSimplified,
          thinkingMode = thinkingMode
        )
      } else {
        RenderListItem(
          item = listItem,
          prefixText = "$number.",
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          listStyle = ListItemStyle(
            contentStyle = contentStyle,
            prefixContentGap = MarkdownStyle.Block.MARKER_CONTENT_GAP,
            prefixStyle = orderedList.numberStyle,
            prefixColumnMinWidth = MarkdownStyle.Block.ORDERED_MARKER_MIN_WIDTH
          ),
          isSimplified = isSimplified,
          thinkingMode = thinkingMode
        )
      }
    }
  }
}

internal fun collectListItems(listNode: Node): List<ListItem> =
  generateSequence(seed = listNode.firstChild) { it.next }
    .filterIsInstance<ListItem>().toList()

/** A `ListItem` that starts with a GFM task-list marker. */
internal data class TaskListMarker(val checked: Boolean)

/**
 * Returns the task-list marker if [paragraph] starts with `[ ] ` /
 * `[x] ` / `[x] ` (the only forms GFM recognizes), or `null` if it's
 * a normal paragraph. The check looks at the first `Text` child only
 * — commonmark emits the marker as a single Text literal, so a split
 * (e.g. an `Emphasis` node before the marker) would already be a
 * non-task-list paragraph.
 */
internal fun extractTaskListMarker(paragraph: Paragraph): TaskListMarker? {
  val firstText: Text = paragraph.firstChild as? Text ?: return null
  val literal: String = firstText.literal ?: return null

  return when {
    literal == "[ ]" || literal.startsWith(prefix = "[ ] ") -> TaskListMarker(checked = false)
    literal == "[x]" || literal.startsWith(prefix = "[x] ") -> TaskListMarker(checked = true)
    literal == "[X]" || literal.startsWith(prefix = "[X] ") -> TaskListMarker(checked = true)
    else -> null
  }
}

/**
 * Returns a copy of [paragraph] with the `[ ] ` / `[x] ` / `[X] `
 * prefix removed from the first `Text` child. Other children are
 * appended to the copy unchanged. Returns `null` if [paragraph] is
 * not a task-list paragraph.
 */
internal fun stripTaskListMarker(paragraph: Paragraph): Paragraph? {
  val firstText: Text = paragraph.firstChild as? Text ?: return null
  val literal: String = firstText.literal ?: return null
  val stripped: String = when {
    literal == "[ ]" -> ""
    literal.startsWith(prefix = "[ ] ") -> literal.removePrefix("[ ] ")
    literal == "[x]" -> ""
    literal.startsWith(prefix = "[x] ") -> literal.removePrefix("[x] ")
    literal == "[X]" -> ""
    literal.startsWith(prefix = "[X] ") -> literal.removePrefix("[X] ")
    else -> return null
  }
  val strippedParagraph = Paragraph()
  strippedParagraph.appendChild(org.commonmark.node.Text(stripped))

  var currentNode: Node? = firstText.next
  while (currentNode != null) {
    val nextNode: Node? = currentNode.next
    strippedParagraph.appendChild(currentNode)
    currentNode = nextNode
  }

  return strippedParagraph
}


/**
 * Render a single GFM task-list item as `Row { CheckboxRow(text) }`. The
 * checkbox is `enabled = false` — task lists in chat messages are
 * informational, not interactive (the user copies the list out to a
 * todo app if they want to track it). The `[ ]` / `[x]` marker is
 * stripped from a synthetic copy of the first `Paragraph` before
 * the inline walker runs, so the rest of the paragraph (bold, code,
 * links, …) renders through the same path as a normal list item.
 * Subsequent children (nested list, second paragraph, …) recurse
 * through [RenderBlockNode] at `indentDepth + 1`, same as
 * [RenderListItem].
 */
@Composable
private fun RenderTaskListItem(
  item: ListItem,
  isChecked: Boolean,
  indentDepth: Int = 0,
  contentStyle: TextStyle,
  onUrlClick: (String) -> Unit,
  isSimplified: Boolean = false,
  thinkingMode: Boolean = false
) {
  val children: NodeChildren = NodeChildren.of(parent = item)
  if (children.isEmpty) return
  val first: Node? = children.first

  if (first is Paragraph && children.rest.isEmpty()) {
    RenderTaskListItemRow(
      paragraph = first,
      isChecked = isChecked,
      onUrlClick = onUrlClick,
      contentStyle = contentStyle
    )
    return
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    if (first is Paragraph) {
      RenderTaskListItemRow(
        paragraph = first,
        isChecked = isChecked,
        onUrlClick = onUrlClick,
        contentStyle = contentStyle
      )
    } else if (first != null) {
      Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
      ) {
        CheckboxRow(
          enabled = false,
          checked = isChecked,
          onCheckedChange = {}
        ) {}
      }
      RenderBlockNode(block = first, indentDepth + 1, onUrlClick, isSimplified, thinkingMode)
    }
    for (child: Node in children.rest) {
      RenderBlockNode(block = child, indentDepth + 1, onUrlClick, isSimplified, thinkingMode)
    }
  }
}

/**
 * Stripped-paragraph → CheckboxRow helper. The marker is removed
 * from a synthetic `Paragraph` (so the original AST is untouched and
 * the serializer's round-trip is preserved) and the inline children
 * walk through [rememberInlineMarkdownRenderFromNode] — same path
 * the non-task-list `RenderInlineTextInListRow` uses, minus the
 * marker prefix.
 */
@Composable
private fun RenderTaskListItemRow(
  isChecked: Boolean,
  paragraph: Paragraph,
  contentStyle: TextStyle,
  onUrlClick: (String) -> Unit
) {
  val strippedParagraph: Paragraph = stripTaskListMarker(paragraph) ?: paragraph
  val parseOutcome: InlineMarkdownRenderResult =
    rememberInlineMarkdownRenderFromNode(parentNode = strippedParagraph)
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top
  ) {
    CheckboxRow(
      enabled = false,
      checked = isChecked,
      onCheckedChange = {}
    ) {
      RenderInlineRender(
        style = contentStyle,
        onUrlClick = onUrlClick,
        parseOutcome = parseOutcome,
        modifier = Modifier.weight(1f),
        fallbackText = serializeInlineChildren(containerNode = strippedParagraph)
      )
    }
  }
}


/** Style parameters for a list item row. */
private data class ListItemStyle(
  val prefixContentGap: Dp,
  val prefixStyle: TextStyle,
  val contentStyle: TextStyle,
  val prefixColumnMinWidth: Dp
)

/**
 * Render a single list item as a `Row { marker column; content }`. The marker
 * column is a fixed-width `Box(contentAlignment = CenterEnd)` so multi-digit
 * numbers right-align against single-digit numbers. For multi-block items the
 * FIRST `Paragraph` child shares a row with the marker; subsequent children
 * (nested list, second paragraph, fenced code) recurse through [RenderBlockNode]
 * with `indentDepth + 1`. When the first child isn't a `Paragraph` the marker
 * is drawn on its own line above the children.
 */
@Composable
private fun RenderListItem(
  item: ListItem,
  prefixText: String,
  indentDepth: Int = 0,
  listStyle: ListItemStyle,
  onUrlClick: (String) -> Unit,
  isSimplified: Boolean = false,
  thinkingMode: Boolean = false
) {
  val children: NodeChildren = NodeChildren.of(parent = item)
  if (children.isEmpty) return
  val first: Node? = children.first

  if (first is Paragraph && children.rest.isEmpty()) {
    RenderInlineTextInListRow(
      paragraph = first,
      onUrlClick = onUrlClick,
      prefixText = prefixText,
      listStyle = listStyle,
    )
    return
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    if (first is Paragraph) {
      RenderInlineTextInListRow(
        paragraph = first,
        onUrlClick = onUrlClick,
        prefixText = prefixText,
        listStyle = listStyle,
      )
    } else {
      Text(
        maxLines = 1,
        softWrap = false,
        text = prefixText,
        style = listStyle.prefixStyle
      )
      if (first != null)
        RenderBlockNode(block = first, indentDepth + 1, onUrlClick, isSimplified, thinkingMode)
    }
    for (child: Node in children.rest) {
      RenderBlockNode(block = child, indentDepth + 1, onUrlClick, isSimplified, thinkingMode)
    }
  }
}

/**
 * Shared list-item row layout: marker column on the left, paragraph
 * inline children on the right. The paragraph is walked via
 * [rememberInlineMarkdownRenderFromNode] so list-marker-shaped content
 * (e.g. `- 1. **bold**`) keeps its formatting — the previous
 * serialize-then-re-parse flow would re-interpret the leading `1. `
 * as an `OrderedList` and bail.
 */
@Composable
private fun RenderInlineTextInListRow(
  prefixText: String,
  paragraph: Paragraph,
  listStyle: ListItemStyle,
  onUrlClick: (String) -> Unit
) {
  val parseOutcome: InlineMarkdownRenderResult = rememberInlineMarkdownRenderFromNode(paragraph)
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top
  ) {
    MarkerColumn(
      prefixText = prefixText,
      prefixStyle = listStyle.prefixStyle,
      prefixContentGap = listStyle.prefixContentGap,
      prefixColumnMinWidth = listStyle.prefixColumnMinWidth
    )
    RenderInlineRender(
      onUrlClick = onUrlClick,
      parseOutcome = parseOutcome,
      style = listStyle.contentStyle,
      modifier = Modifier.weight(1f),
      fallbackText = serializeInlineChildren(containerNode = paragraph)
    )
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
      maxLines = 1,
      softWrap = false,
      text = prefixText,
      style = prefixStyle,
      textAlign = TextAlign.End
    )
  }
}


/**
 * Render a block quote as a `Row { pill-shaped rule; padded Column { children } }`.
 *
 * The left rule is a 4-dp-wide `Box` filled with the quote's gray line
 * color and clipped to a `RoundedCornerShape(percent = 50)`. Compose
 * resolves the percent to `min(width, height) / 2` at layout time, so a
 * 4-dp-wide rule becomes a perfect capsule (radius = 2 dp) regardless
 * of how tall the quote runs — no hand-rolled `drawLine` / `StrokeCap`
 * math, no inset coordinates. Same width as
 * `styling.blockQuote.lineWidth` (4 dp / `GradumSpacing.sm`) and same
 * gray as `styling.blockQuote.lineColor` (`globalColors.text.disabled`),
 * so the rule matches the chat's muted-text tone in every theme.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderBlockQuote(
  quote: BlockQuote, onUrlClick: (String) -> Unit,
  isSimplified: Boolean = false, thinkingMode: Boolean = false
) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val quoteTextColor: Color = styling.blockQuote.textColor
  val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle.copy(
    color = quoteTextColor,
    lineHeight = styling.paragraph.inlinesStyling.textStyle.fontSize * 1.6f
  )
  val quotePadding: PaddingValues = styling.blockQuote.padding
  val borderColor: Color = styling.blockQuote.lineColor
  val indentStart: Dp = quotePadding.calculateStartPadding(LayoutDirection.Ltr)
  val children: NodeChildren = NodeChildren.of(parent = quote)

  Row(
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .width(styling.blockQuote.lineWidth)
        .fillMaxHeight()
        .clip(shape = RoundedCornerShape(percent = 50))
        .background(borderColor)
    )
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(
          start = indentStart,
          top = quotePadding.calculateTopPadding(),
          bottom = quotePadding.calculateBottomPadding()
        ),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
    ) {
      val firstChild: Node? = children.first
      if (firstChild != null) RenderBlockQuoteChild(
        node = firstChild, quoteTextStyle = contentStyle, onUrlClick, isSimplified, thinkingMode
      )
      for (childNode: Node in children.rest) {
        RenderBlockQuoteChild(
          childNode, quoteTextStyle = contentStyle, onUrlClick, isSimplified, thinkingMode
        )
      }
    }
  }
}

/** Render one block-quote child with the blockquote text color applied to inline text. */
@Composable
private fun RenderBlockQuoteChild(
  node: Node,
  quoteTextStyle: TextStyle,
  onUrlClick: (String) -> Unit,
  isSimplified: Boolean = false,
  thinkingMode: Boolean = false
) {
  when (node) {
    is Paragraph -> {
      val parseOutcome: InlineMarkdownRenderResult =
        rememberInlineMarkdownRenderFromNode(parentNode = node)
      RenderInlineRender(
        style = quoteTextStyle,
        onUrlClick = onUrlClick,
        parseOutcome = parseOutcome,
        modifier = Modifier.fillMaxWidth(),
        fallbackText = serializeInlineChildren(containerNode = node)
      )
    }

    else -> RenderBlockNode(
      block = node,
      onUrlClick = onUrlClick,
      isSimplified = isSimplified,
      thinkingMode = thinkingMode
    )
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
  val markdownBlock: MarkdownBlock.CodeBlock.FencedCodeBlock = remember(key1 = block) {
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
  val markdownBlock: MarkdownBlock.CodeBlock.IndentedCodeBlock = remember(key1 = block) {
    parseIndentedCodeBlock(block)
  }
  blockRenderer.RenderIndentedCodeBlock(
    enabled = true,
    block = markdownBlock,
    styling = indentedStyling,
    modifier = Modifier.fillMaxWidth()
  )
}

@OptIn(ExperimentalJewelApi::class)
private fun parseFencedCodeBlock(block: FencedCodeBlock): MarkdownBlock.CodeBlock.FencedCodeBlock {
  val markdownSource = "```${block.info ?: ""}\n${block.literal?.trimEnd('\n') ?: ""}\n```"
  val blocks: List<MarkdownBlock> =
    GradumMarkdownProcessor.processMarkdownDocument(rawMarkdown = markdownSource)
  val first: MarkdownBlock = blocks.first()
  require(value = first is MarkdownBlock.CodeBlock.FencedCodeBlock) {
    "Expected FencedCodeBlock from re-parse, got ${first::class.simpleName}"
  }
  return first
}

@OptIn(ExperimentalJewelApi::class)
private fun parseIndentedCodeBlock(
  block: IndentedCodeBlock,
): MarkdownBlock.CodeBlock.IndentedCodeBlock {
  val rawLines: List<String> = (block.literal ?: "").lines()
  val indented: String = buildString {
    append('\n')
    rawLines.forEach { line: String -> append("    ").append(line).append('\n') }
  }
  val blocks: List<MarkdownBlock> = GradumMarkdownProcessor.processMarkdownDocument(rawMarkdown = indented)
  val first: MarkdownBlock = blocks.first()
  require(value = first is MarkdownBlock.CodeBlock.IndentedCodeBlock) {
    "Expected IndentedCodeBlock from re-parse, got ${first::class.simpleName}"
  }
  return first
}

@Composable
private fun RenderThematicBreak() {
  val lineColor: Color = JewelTheme.globalColors.text.info.copy(
    alpha = MarkdownStyle.InlineCode.CHIP_BORDER_ALPHA
  )
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        vertical = MarkdownStyle.Block.THEMATIC_BREAK_VERTICAL_SPACING
      )
      .height(1.dp)
      .background(lineColor)
  )
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderParagraphWithChips(paragraph: Paragraph, onUrlClick: (String) -> Unit) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val baseStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
  val parseOutcome: InlineMarkdownRenderResult = rememberInlineMarkdownRenderFromNode(parentNode = paragraph)
  RenderInlineRender(
    style = baseStyle,
    onUrlClick = onUrlClick,
    parseOutcome = parseOutcome,
    modifier = Modifier.fillMaxWidth(),
    fallbackText = serializeInlineChildren(containerNode = paragraph)
  )
}


/**
 * Reparse the given inline text with the chip parser and render it as
 * `Text(annotated, inlineContent = ...)`. The fontSize is derived from
 * `style.fontSize` so the chip's `Placeholder` width / height matches the
 * surrounding text.
 *
 * [onUrlClick] is invoked when the user taps a [UrlAnnotation] range. Tap
 * position is mapped to a text offset via `TextLayoutResult.getOffsetForPosition`
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
  onUrlClick: (String) -> Unit = {},
  inlineCodeFontSizeSp: Float? = null,
  editorFontFamily: FontFamily = FontFamily.Default
) {
  if (text.isEmpty()) return
  val fontSizeSp: Float = inlineCodeFontSizeSp
    ?: style.fontSize.value.let {
      if (it <= 0f) MarkdownStyle.FontFallback.BLOCK_SIZE_SP else it
    }
  val textColor: Color = style.color.let { colorValue: Color ->
    if (colorValue == Color.Unspecified) JewelTheme.contentColor else colorValue
  }
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val codeColor: Color = JewelTheme.globalColors.text.info
  val imageAltColor: Color = textColor.copy(alpha = MarkdownStyle.Image.ALT_COLOR_ALPHA)
  val parseOutcome: InlineMarkdownRenderResult = remember(key1 = text) {
    parseInlineMarkdown(
      linkColor = linkColor,
      plainText = text,
      fontSizeSp = fontSizeSp,
      imageAltColor = imageAltColor,
      codeColor = codeColor,
      editorFontFamily = editorFontFamily
    )
  }
  RenderInlineRender(
    style = style,
    modifier = modifier,
    fallbackText = text,
    onUrlClick = onUrlClick,
    parseOutcome = parseOutcome
  )
}

/**
 * Shared render path for both [RenderInlineTextWithChips] (text-source)
 * and [RenderHeading] (node-source). The `parseOutcome.render == null`
 * branch paints [fallbackText] in the same style so callers don't
 * drop formatting on a bail.
 */
@Composable
private fun RenderInlineRender(
  style: TextStyle,
  modifier: Modifier,
  fallbackText: String,
  onUrlClick: (String) -> Unit,
  parseOutcome: InlineMarkdownRenderResult
) {
  val resolvedStyle: TextStyle =
    if (style.lineHeight.value.isNaN() || style.lineHeight.value <= 0f)
      style.copy(lineHeight = style.fontSize * MarkdownStyle.Block.BODY_LINE_HEIGHT_MULTIPLIER)
    else style

  if (parseOutcome.render == null) {
    Text(text = fallbackText, style = resolvedStyle, modifier = modifier)
    return
  }
  val inlineRender: InlineMarkdownRender = parseOutcome.render
  val segments: List<InlineSegment> = splitIntoInlineSegments(
    annotated = inlineRender.annotated,
    urlAnnotations = inlineRender.urlAnnotations,
    inlineContent = inlineRender.inlineContent,
  )
  val gradumLinkStyle: LinkStyle = rememberGradumLinkStyle()

  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.Start,
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs, Alignment.Top),
  ) {
    segments.forEach { segment: InlineSegment ->
      when (segment) {
        is InlineSegment.TextSegment -> {
          val codeSpanAnnotations = remember(key1 = segment.annotated) {
            segment.annotated.getStringAnnotations(INLINE_CODE_SPAN_TAG, start = 0, end = segment.annotated.length)
          }
          var textLayoutResult by remember {
            mutableStateOf<TextLayoutResult?>(value = null)
          }
          val density: Density = LocalDensity.current
          val badgeColor: Color = JewelTheme.globalColors.text.info
          val backgroundColor: Color = badgeColor.copy(alpha = MarkdownStyle.InlineCode.BACKGROUND_ALPHA)
          val borderColor: Color = badgeColor.copy(alpha = MarkdownStyle.InlineCode.CHIP_BORDER_ALPHA)
          Text(
            style = resolvedStyle,
            text = segment.annotated,
            inlineContent = segment.inlineContent,
            onTextLayout = { layoutResult: TextLayoutResult -> textLayoutResult = layoutResult },
            modifier = Modifier.drawWithContent {
              textLayoutResult
                ?.takeIf { codeSpanAnnotations.isNotEmpty() }
                ?.run {
                  drawInlineCodeChips(
                    density = density,
                    layoutResult = this,
                    style = resolvedStyle,
                    borderColor = borderColor,
                    codeSpans = codeSpanAnnotations,
                    backgroundColor = backgroundColor
                  )
                }
              drawContent()
            }
          )
        }

        is InlineSegment.LinkSegment -> ExternalLink(
          text = segment.text,
          style = gradumLinkStyle,
          textStyle = resolvedStyle,
          onClick = { onUrlClick(segment.url) }
        )
      }
    }
  }
}

/**
 * Draws the rounded background + border chip behind each inline-code span.
 *
 * Works at line granularity so a multi-line code span gets one chip per line.
 * [layoutResult] is the Text layout used to resolve offsets to geometry; [style]
 * drives the chip height via its line-height so all chips in the paragraph
 * render at a uniform height regardless of the glyphs they contain.
 */
private fun DrawScope.drawInlineCodeChips(
  density: Density,
  style: TextStyle,
  borderColor: Color,
  backgroundColor: Color,
  codeSpans: List<Range<String>>,
  layoutResult: TextLayoutResult
) {
  if (codeSpans.isEmpty()) return

  val cornerRadiusPx: Float = MarkdownStyle.InlineCode.CORNER_RADIUS.toPx()
  val chipHeightPx: Float = with(receiver = density) {
    style.fontSize.toPx() * MarkdownStyle.InlineCode.CHIP_HEIGHT_MULTIPLIER
  }

  for ((_, start: Int, end: Int) in codeSpans) {
    if (start >= end) continue
    val startLine: Int = layoutResult.getLineForOffset(start)
    val endLine: Int = layoutResult.getLineForOffset(end - 1)

    for (line: Int in startLine..endLine) {
      val lineStart: Int = layoutResult.getLineStart(lineIndex = line)
      val lineEnd: Int = layoutResult.getLineEnd(lineIndex = line)
      val segmentStart: Int = maxOf(a = start, b = lineStart)
      val segmentEnd: Int = minOf(a = end, b = lineEnd)
      if (segmentStart >= segmentEnd) continue

      val left: Float = layoutResult.getBoundingBox(offset = segmentStart).left
      val right: Float = layoutResult.getBoundingBox(offset = segmentEnd - 1).right
      val baseline: Float = layoutResult.getLineBaseline(lineIndex = line)
      val chipTop: Float = baseline - chipHeightPx * MarkdownStyle.InlineCode.CHIP_BASELINE_RATIO
      val chipOrigin = Offset(x = left, y = chipTop)
      val chipSize = Size(width = right - left, height = chipHeightPx)

      drawRoundRect(
        color = backgroundColor,
        topLeft = chipOrigin,
        size = chipSize,
        cornerRadius = CornerRadius(cornerRadiusPx, y = cornerRadiusPx)
      )
      drawRoundRect(
        color = borderColor,
        topLeft = chipOrigin,
        size = chipSize,
        cornerRadius = CornerRadius(cornerRadiusPx, y = cornerRadiusPx),
        style = Stroke(width = MarkdownStyle.InlineCode.CHIP_BORDER_WIDTH.toPx())
      )
    }
  }
}
