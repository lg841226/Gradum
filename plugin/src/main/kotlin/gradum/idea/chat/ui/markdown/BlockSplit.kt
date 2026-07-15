/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * BlockSplit.kt  2026-07-15 19:45:45 Changed by gwy
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

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.parser.Parser

private val blockSplitParser: Parser = Parser.builder()
  .extensions(listOf(StrikethroughExtension.create()))
  .build()

/** Indent step (in columns) for child blocks inside a list item. */
private const val LIST_CHILD_INDENT_COLUMNS: Int = 4

/**
 * Re-serializes a CommonMark node back to its Markdown source. Top-level
 * callers use [serializeMarkdownNode] or [serializeInlineChildren]
 * (children only, used for paragraph-in-block and list-item rendering paths).
 */
internal fun serializeMarkdownNode(rootNode: Node): String =
  serializeInto(rootNode)

internal fun serializeInlineChildren(containerNode: Node): String {
  val resultBuilder: StringBuilder = StringBuilder()
  serializeChildrenInto(containerNode, resultBuilder)
  return resultBuilder.toString()
}

/**
 * Splits a Plain segment on top-level CommonMark block boundaries.
 * Each `Paragraph` becomes [MarkdownSegment.Plain] (routed to the
 * inline chip parser); every other top-level block becomes
 * [MarkdownSegment.NonProseBlock] (routed to the non-prose block
 * renderer). [LinkReferenceDefinition] is dropped (the link inside
 * the `Paragraph` already has the resolved URL).
 *
 * A `HtmlBlock` whose text content is non-empty (after stripping the
 * raw `<tag>` markers) is reclassified as [MarkdownSegment.Plain]
 * rather than [MarkdownSegment.NonProseBlock] — Jewel's native
 * `Markdown(...)` renders raw HTML as a fenced code block, which
 * reads as "broken output" in chat. Stripping + reclassifying sends
 * the inner text through the inline path (with bold / italic / links
 * / code chips), so the chat shows clean prose, not the raw tags.
 */
internal fun splitPlainAtBlocks(plainText: String): List<MarkdownSegment> {
  if (plainText.isBlank()) return emptyList()
  val document: Document = blockSplitParser.parse(plainText) as Document
  val children: NodeChildren = NodeChildren.of(document)

  val blocks: List<Node> = buildList {
    if (children.first != null) add(children.first)
    addAll(children.rest)
  }

  return blocks
    .filter { topLevelBlock -> topLevelBlock !is LinkReferenceDefinition }
    .map { topLevelBlock ->
      when (topLevelBlock) {
        is Paragraph -> MarkdownSegment.Plain(text = serializeInto(topLevelBlock))
        is HtmlBlock -> stripHtmlBlockTags(topLevelBlock)?.let { stripped ->
          MarkdownSegment.Plain(text = stripped)
        } ?: MarkdownSegment.NonProseBlock(text = serializeInto(topLevelBlock))

        else -> MarkdownSegment.NonProseBlock(text = serializeInto(topLevelBlock))
      }
    }
}

/**
 * Walk a `HtmlBlock`'s children, collecting only the inline text
 * content (skipping `HtmlInline` opening/closing tags and any other
 * non-text inline children). Returns `null` if the block is empty
 * after stripping (e.g. `<br>`) so the caller can fall back to the
 * raw-literal NonProseBlock path.
 */
private fun stripHtmlBlockTags(htmlBlock: HtmlBlock): String? {
  val resultBuilder: StringBuilder = StringBuilder()
  val children: NodeChildren = NodeChildren.of(htmlBlock)
  val firstChild: Node? = children.first
  if (firstChild != null) appendHtmlBlockChild(firstChild, resultBuilder)
  for (childNode in children.rest) appendHtmlBlockChild(childNode, resultBuilder)
  val text: String = resultBuilder.toString().trim()
  return text.ifEmpty { null }
}

private fun appendHtmlBlockChild(childNode: Node, output: StringBuilder) {
  when (childNode) {
    is HtmlInline -> {
      // strip raw <tag> / </tag> markers
    }
    is Text -> output.append(childNode.literal.orEmpty())
    else -> serializeChildrenInto(childNode, output)
  }
}

private fun serializeChildrenInto(containerNode: Node, output: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(containerNode)
  val firstChild: Node? = children.first
  if (firstChild != null) serializeInto(firstChild, output)
  for (childNode in children.rest) serializeInto(childNode, output)
}

private fun serializeInto(currentNode: Node): String {
  val resultBuilder: StringBuilder = StringBuilder()
  serializeInto(currentNode, resultBuilder)
  return resultBuilder.toString()
}

private fun serializeInto(currentNode: Node, output: StringBuilder) {
  when (currentNode) {
    // top-level container
    is Document -> serializeChildrenInto(currentNode, output)
    // block nodes
    is ThematicBreak -> output.append("---")
    is Heading -> serializeHeadingInto(currentNode, output)
    is BulletList -> serializeBulletListInto(currentNode, output)
    is BlockQuote -> serializeBlockQuoteInto(currentNode, output)
    is OrderedList -> serializeOrderedListInto(currentNode, output)
    is FencedCodeBlock -> serializeFencedCodeBlockInto(currentNode, output)
    is HtmlBlock -> output.append(currentNode.literal.orEmpty())
    is IndentedCodeBlock -> output.append(currentNode.literal.orEmpty())
    is Paragraph -> serializeChildrenInto(currentNode, output)
    // inline nodes
    is Text -> output.append(currentNode.literal.orEmpty())
    is Code -> output.append('`').append(currentNode.literal.orEmpty()).append('`')
    is Emphasis -> serializeWrapInlineInto("*", "*", currentNode, output)
    is StrongEmphasis -> serializeWrapInlineInto("**", "**", currentNode, output)
    is Link -> serializeLinkInto(currentNode, output)
    is Image -> serializeImageInto(currentNode, output)
    is Strikethrough -> serializeStrikethroughInto(currentNode, output)
    is SoftLineBreak -> output.append('\n')
    is HardLineBreak -> output.append("\\\n")
    // stripped / fallback
    is HtmlInline -> {
      // strip raw <tag> / </tag> markers from round-tripped markdown
    }
    else -> serializeChildrenInto(currentNode, output)
  }
}

private fun serializeWrapInlineInto(
  openingMarker: String,
  closingMarker: String,
  containerNode: Node,
  output: StringBuilder,
) {
  output.append(openingMarker)
  serializeChildrenInto(containerNode, output)
  output.append(closingMarker)
}

private fun serializeStrikethroughInto(strikethroughNode: Strikethrough, output: StringBuilder) {
  output.append("~~")
  serializeChildrenInto(strikethroughNode, output)
  output.append("~~")
}

private fun serializeHeadingInto(heading: Heading, output: StringBuilder) {
  repeat(heading.level) { output.append('#') }
  output.append(' ')
  serializeChildrenInto(heading, output)
}

// TODO: Consolidate serializeLinkInto and serializeImageInto into a single generic function
private fun serializeLinkInto(link: Link, output: StringBuilder) {
  output.append('[')
  serializeChildrenInto(link, output)
  output.append("](")
  output.append(link.destination.orEmpty())
  if (link.title != null) output.append(" \"").append(link.title).append('"')
  output.append(')')
}

private fun serializeImageInto(image: Image, output: StringBuilder) {
  output.append("![")
  serializeChildrenInto(image, output)
  output.append("](")
  output.append(image.destination.orEmpty())

  if (image.title != null) {
    output.append(" \"").append(image.title).append('"')
  }
  output.append(')')
}

private fun serializeBulletListInto(bulletList: BulletList, output: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(bulletList)
  val firstListItem: ListItem? = children.first as? ListItem
  if (firstListItem != null) {
    serializeListItemInto(firstListItem, listMarker = "- ", output)
    for (childNode in children.rest) {
      if (childNode is ListItem) {
        output.append('\n')
        serializeListItemInto(childNode, listMarker = "- ", output)
      }
    }
  }
}

@Suppress("DEPRECATION")
private fun serializeOrderedListInto(orderedList: OrderedList, output: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(orderedList)
  val firstListItem: ListItem? = children.first as? ListItem
  if (firstListItem != null) {
    val firstMarker = "${orderedList.startNumber}. "
    serializeListItemInto(firstListItem, listMarker = firstMarker, output)
    var nextNumber: Int = orderedList.startNumber + 1

    for (childNode in children.rest) {
      if (childNode is ListItem) {
        output.append('\n')
        serializeListItemInto(childNode, listMarker = "$nextNumber. ", output)
        nextNumber += 1
      }
    }
  }
}

/**
 * Serialize a `ListItem`: emit `<listMarker>` then the first child inline
 * (on the same line as the listMarker); subsequent children (nested list,
 * second paragraph, fenced code) are emitted on the next line(s)
 * indented 4 columns so Markdown recognizes them as continuation
 * content of the list item (not a new top-level list). The 4-column
 * minimum is the CommonMark rule for list-item continuation.
 */
private fun serializeListItemInto(listItem: ListItem, listMarker: String, output: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(listItem)
  output.append(listMarker)
  val firstChildNode: Node? = children.first
  if (firstChildNode != null) serializeInto(firstChildNode, output)
  // Indent is fixed at 4 columns. The listMarker is the leading prefix on the first line.
  // Continuation lines: the first non-space char must align at column 4 (not relative to listMarker).
  val indent: String = " ".repeat(LIST_CHILD_INDENT_COLUMNS)
  for (childNode in children.rest) {
    output.append('\n')
    output.append(indent)
    serializeInto(childNode, output)
  }
}

private fun serializeBlockQuoteInto(blockQuote: BlockQuote, output: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(blockQuote)
  val firstChildNode: Node? = children.first
  if (firstChildNode != null) {
    output.append("> ")
    output.append(prefixEachLineExceptFirst(serializeInto(firstChildNode)))
  }
  for (childNode in children.rest) {
    output.append('\n')
    output.append("> ")
    output.append(prefixEachLineExceptFirst(serializeInto(childNode)))
  }
}

private fun serializeFencedCodeBlockInto(codeBlock: FencedCodeBlock, output: StringBuilder) {
  output.append("```")
  val codeLanguage: String? = codeBlock.info
  if (!codeLanguage.isNullOrBlank()) output.append(codeLanguage)

  output.append('\n')
  output.append(codeBlock.literal.orEmpty())
  if (!output.endsWith('\n')) output.append('\n')

  output.append("```")
}

/** Prefix every line of [content] except the first with `> `. */
private fun prefixEachLineExceptFirst(content: String): String {
  if (!content.contains('\n')) return content
  val lines: List<String> = content.split('\n')

  return lines.mapIndexed { lineNumber, line ->
    if (lineNumber == 0 || line.isEmpty()) line else "> $line"
  }.joinToString(separator = "\n")
}

private fun StringBuilder.endsWith(suffix: Char): Boolean =
  isNotEmpty() && this[length - 1] == suffix
