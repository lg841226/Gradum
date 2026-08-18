/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * BlockRenderer.kt  2026-08-12 12:38:25 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
  // Must mirror BlockSplit.kt's parser: ~~strike~~ is parsed as literal
  // tildes when the extension is missing, even though splitPlainAtBlocks
  // already recognized the Strikethrough node and serialized it back.
  .extensions(listOf(StrikethroughExtension.create(), LatexBlockExtension.create()))
  .build()

private val orderedMarkerColumnMinWidth: Dp = 24.dp
private val unorderedMarkerColumnMinWidth: Dp = 20.dp
private val markerContentGap: Dp = GradumSpacing.sm
private val nestedListIndentStep: Dp = GradumSpacing.xl
private val listItemVerticalSpacing: Dp = GradumSpacing.md
private val listOuterPadding: PaddingValues = PaddingValues(vertical = GradumSpacing.md)
private val headingExtraPadding: PaddingValues =
  PaddingValues(top = GradumSpacing.lg, bottom = GradumSpacing.sm)
private val thematicBreakVerticalSpacing: Dp = GradumSpacing.lg
private const val FALLBACK_FONT_SIZE_SP_NO_STYLE: Float = 13f
private const val BODY_LINE_HEIGHT_MULTIPLIER: Float = 1.3f

/** Default language label for code blocks without an explicit language tag. */
internal const val DEFAULT_CODE_LANGUAGE: String = "plain text"


/**
 * Render a [MarkdownSegment.NonProseBlock] using the non-prose block renderer.
 * Reparses the segment's text with commonmark to recover the AST (the
 * segment was originally produced by [splitPlainAtBlocks] which serialized
 * each block to text).
 */
@Composable
fun RenderNonProseBlock(
  onUrlClick: (String) -> Unit = {}, segment: MarkdownSegment.NonProseBlock
) {
  val document: Document = remember(segment.text) {
    blockReparseParser.parse(segment.text) as Document
  }

  val children: NodeChildren = NodeChildren.of(document)
  val firstNode: Node? = children.first
  if (firstNode != null) RenderBlockNode(firstNode, onUrlClick = onUrlClick)
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
    is ThematicBreak -> RenderThematicBreak()
    is Heading -> RenderHeading(block, onUrlClick)
    is FencedCodeBlock -> RenderFencedCodeBlock(block)
    is BlockQuote -> RenderBlockQuote(block, onUrlClick)
    is IndentedCodeBlock -> RenderIndentedCodeBlock(block)
    is BulletList -> RenderBulletList(block, indentDepth, onUrlClick)
    is OrderedList -> RenderOrderedList(block, indentDepth, onUrlClick)
    is Paragraph -> RenderParagraphWithChips(block, onUrlClick)
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
      text = serializeMarkdownNode(block)
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
  // Walk the heading's inline children directly. The previous
  // flow serialized `### 2. **bold**` to `"2. **bold**"` and
  // reparsed it; commonmark re-interpreted `"2. "` as an
  // OrderedList, the inline parser bailed, and the bold was lost.
  // Walking the AST skips the lossy round-trip.
  val parseOutcome: InlineMarkdownRenderResult = rememberInlineMarkdownRenderFromNode(heading)
  RenderInlineRender(
    modifier = Modifier
      .fillMaxWidth()
      .padding(headingStyle.padding)
      .padding(headingExtraPadding),
    onUrlClick = onUrlClick,
    parseOutcome = parseOutcome,
    style = headingStyle.inlinesStyling.textStyle,
    fallbackText = serializeInlineChildren(heading),
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
  list: BulletList, indentDepth: Int = 0, onUrlClick: (String) -> Unit = {}
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
      val taskMarker: TaskListMarker? = (listItem.firstChild as? Paragraph)?.let(::extractTaskListMarker)
      if (taskMarker != null) {
        RenderTaskListItem(
          item = listItem,
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          isChecked = taskMarker.checked,
          contentStyle = styling.paragraph.inlinesStyling.textStyle
        )
      } else {
        RenderListItem(
          item = listItem,
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          prefixStyle = bulletStyle,
          prefixContentGap = markerContentGap,
          prefixText = unorderedList.bullet.toString(),
          prefixColumnMinWidth = unorderedMarkerColumnMinWidth,
          contentStyle = styling.paragraph.inlinesStyling.textStyle
        )
      }
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
      @Suppress("DEPRECATION")
      val number: Int = list.startNumber + index
      val taskMarker: TaskListMarker? = (listItem.firstChild as? Paragraph)?.let(::extractTaskListMarker)
      if (taskMarker != null) {
        RenderTaskListItem(
          item = listItem,
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          contentStyle = contentStyle,
          isChecked = taskMarker.checked
        )
      } else {
        RenderListItem(
          item = listItem,
          prefixText = "$number.",
          onUrlClick = onUrlClick,
          indentDepth = indentDepth,
          contentStyle = contentStyle,
          prefixContentGap = markerContentGap,
          prefixStyle = orderedList.numberStyle,
          prefixColumnMinWidth = orderedMarkerColumnMinWidth
        )
      }
    }
  }
}

internal fun collectListItems(listNode: Node): List<ListItem> =
  generateSequence(listNode.firstChild) { it.next }.filterIsInstance<ListItem>().toList()

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
    literal == "[ ]" || literal.startsWith("[ ] ") -> TaskListMarker(checked = false)
    literal == "[x]" || literal.startsWith("[x] ") -> TaskListMarker(checked = true)
    literal == "[X]" || literal.startsWith("[X] ") -> TaskListMarker(checked = true)
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
  // Same "no trailing space when content is empty" quirk as in
  // [extractTaskListMarker] — see comment there.
  val stripped: String = when {
    literal == "[ ]" -> ""
    literal.startsWith("[ ] ") -> literal.removePrefix("[ ] ")
    literal == "[x]" -> ""
    literal.startsWith("[x] ") -> literal.removePrefix("[x] ")
    literal == "[X]" -> ""
    literal.startsWith("[X] ") -> literal.removePrefix("[X] ")
    else -> return null
  }
  val strippedParagraph = Paragraph()
  strippedParagraph.appendChild(org.commonmark.node.Text(stripped))

  // Walk the original paragraph's children starting at the
  // sibling after `firstText`. We must capture `currentNode.next`
  // *before* `appendChild` because the call goes through
  // `Node.unlink`, which nulls the moved node's `next` field
  // (see commonmark `org.commonmark.node.Node#unlink`).
  // A `generateSequence { it.next }` over the moved node would
  // terminate after the first sibling — a real bug that dropped
  // every-other-child for paragraphs with a strong/emphasis
  // sibling in a task-list item. The manual loop captures
  // `next` before the destructive append.
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
  onUrlClick: (String) -> Unit
) {
  val children: NodeChildren = NodeChildren.of(item)
  if (children.isEmpty) return
  val first: Node? = children.first

  if (first is Paragraph && children.rest.isEmpty()) {
    RenderTaskListItemRow(
      paragraph = first,
      isChecked = isChecked,
      onUrlClick = onUrlClick,
      contentStyle = contentStyle,
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
      RenderBlockNode(first, indentDepth + 1, onUrlClick)
    }
    for (child in children.rest) {
      RenderBlockNode(child, indentDepth + 1, onUrlClick)
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
    rememberInlineMarkdownRenderFromNode(strippedParagraph)
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
        fallbackText = serializeInlineChildren(strippedParagraph)
      )
    }
  }
}


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
    RenderInlineTextInListRow(
      paragraph = first,
      onUrlClick = onUrlClick,
      prefixText = prefixText,
      prefixStyle = prefixStyle,
      contentStyle = contentStyle,
      prefixContentGap = prefixContentGap,
      prefixColumnMinWidth = prefixColumnMinWidth
    )
    return
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    if (first is Paragraph) {
      RenderInlineTextInListRow(
        paragraph = first,
        onUrlClick = onUrlClick,
        prefixText = prefixText,
        prefixStyle = prefixStyle,
        contentStyle = contentStyle,
        prefixContentGap = prefixContentGap,
        prefixColumnMinWidth = prefixColumnMinWidth
      )
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
    for (child in children.rest) {
      RenderBlockNode(child, indentDepth + 1, onUrlClick)
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
  prefixContentGap: Dp,
  prefixStyle: TextStyle,
  contentStyle: TextStyle,
  prefixColumnMinWidth: Dp,
  onUrlClick: (String) -> Unit
) {
  val parseOutcome: InlineMarkdownRenderResult = rememberInlineMarkdownRenderFromNode(paragraph)
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    MarkerColumn(
      prefixText = prefixText,
      prefixStyle = prefixStyle,
      prefixColumnMinWidth = prefixColumnMinWidth,
      prefixContentGap = prefixContentGap,
    )
    RenderInlineRender(
      parseOutcome = parseOutcome,
      style = contentStyle,
      modifier = Modifier.weight(1f),
      onUrlClick = onUrlClick,
      fallbackText = serializeInlineChildren(paragraph),
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
private fun RenderBlockQuote(quote: BlockQuote, onUrlClick: (String) -> Unit) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val quoteTextColor = styling.blockQuote.textColor
  val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle.copy(
    color = quoteTextColor,
    lineHeight = styling.paragraph.inlinesStyling.textStyle.fontSize * 1.6f
  )
  val quotePadding: PaddingValues = styling.blockQuote.padding
  val borderColor = styling.blockQuote.lineColor
  val indentStart: Dp = quotePadding.calculateStartPadding(LayoutDirection.Ltr)
  val children: NodeChildren = NodeChildren.of(quote)


  Row(
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .width(styling.blockQuote.lineWidth)
        .fillMaxHeight()
        .clip(RoundedCornerShape(percent = 50))
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
      if (firstChild != null) RenderBlockQuoteChild(firstChild, contentStyle, onUrlClick)
      for (childNode in children.rest) {
        RenderBlockQuoteChild(childNode, contentStyle, onUrlClick)
      }
    }
  }
}

/** Render one block-quote child with the blockquote text color applied to inline text. */
@Composable
private fun RenderBlockQuoteChild(
  node: Node,
  quoteTextStyle: TextStyle,
  onUrlClick: (String) -> Unit
) {
  when (node) {
    is Paragraph -> {
      val parseOutcome: InlineMarkdownRenderResult = rememberInlineMarkdownRenderFromNode(node)
      RenderInlineRender(
        style = quoteTextStyle,
        onUrlClick = onUrlClick,
        parseOutcome = parseOutcome,
        modifier = Modifier.fillMaxWidth(),
        fallbackText = serializeInlineChildren(node)
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
    enabled = true,
    block = markdownBlock,
    styling = indentedStyling,
    modifier = Modifier.fillMaxWidth()
  )
}

@OptIn(ExperimentalJewelApi::class)
private fun parseFencedCodeBlock(block: FencedCodeBlock): MarkdownBlock.CodeBlock.FencedCodeBlock {
  val markdownSource = "```${block.info ?: ""}\n${block.literal?.trimEnd('\n') ?: ""}\n```"
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

/** Render `---` as a 1dp horizontal line. */
@Composable
private fun RenderThematicBreak() {
  val lineColor: Color = JewelTheme.globalColors.text.info.copy(alpha = 0.3f)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = thematicBreakVerticalSpacing)
      .height(1.dp)
      .background(lineColor)
  )
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RenderParagraphWithChips(paragraph: Paragraph, onUrlClick: (String) -> Unit) {
  val styling: MarkdownStyling = rememberGradumMarkdownStyling()
  val baseStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
  val parseOutcome: InlineMarkdownRenderResult = rememberInlineMarkdownRenderFromNode(paragraph)
  RenderInlineRender(
    style = baseStyle,
    onUrlClick = onUrlClick,
    parseOutcome = parseOutcome,
    modifier = Modifier.fillMaxWidth(),
    fallbackText = serializeInlineChildren(paragraph)
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
) {
  if (text.isEmpty()) return
  val fontSizeSp: Float = inlineCodeFontSizeSp
    ?: style.fontSize.value.let { if (it <= 0f) FALLBACK_FONT_SIZE_SP_NO_STYLE else it }
  val textColor: Color = style.color.let { colorValue ->
    if (colorValue == Color.Unspecified) JewelTheme.contentColor else colorValue
  }
  val chipTintColor: Color = textColor
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val imageAltColor: Color = textColor.copy(alpha = 0.6f)
  val parseOutcome: InlineMarkdownRenderResult = remember(text) {
    parseInlineMarkdown(
      plainText = text,
      fontSizeSp = fontSizeSp,
      chipTint = chipTintColor,
      linkColor = linkColor,
      imageAltColor = imageAltColor
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
  val resolvedStyle: TextStyle = if (style.lineHeight.value.isNaN() || style.lineHeight.value <= 0f) {
    style.copy(lineHeight = style.fontSize * BODY_LINE_HEIGHT_MULTIPLIER)
  } else {
    style
  }
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
  // Anchor by first baseline so `Text` and `ExternalLink` line up
  // across wrap boundaries — using `FirstBaseline` would force
  // ExternalLink's icon row to bottom-align with the text glyphs,
  // which reads as "links hang below" on multi-line wraps.
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.Start,
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs, Alignment.Top),
  ) {
    segments.forEach { segment: InlineSegment ->
      when (segment) {
        is InlineSegment.TextSegment -> Text(
          style = resolvedStyle,
          text = segment.annotated,
          inlineContent = segment.inlineContent
        )

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
