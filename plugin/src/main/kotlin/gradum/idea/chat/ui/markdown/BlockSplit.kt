/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * BlockSplit.kt  2026-07-14 21:51:35 Changed by gwy
 */

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
)

package gradum.idea.chat.ui.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val blockSplitParser: Parser = Parser.builder()
  .extensions(listOf(StrikethroughExtension.create()))
  .build()

/** Indent step (in columns) for child blocks inside a list item. */
private const val LIST_CHILD_INDENT_COLUMNS: Int = 4

/**
 * Re-serializes a CommonMark node back to its Markdown source. Top-level
 * callers use [serializeMarkdownNode] (zero marker column) or
 * [serializeInlineChildren] (children only, used for paragraph-in-block
 * and list-item rendering paths).
 */
internal fun serializeMarkdownNode(currentNode: Node): String =
  serializeInto(currentNode, markerCol = 0)

internal fun serializeInlineChildren(parentNode: Node): String {
  val out: StringBuilder = StringBuilder()
  serializeChildrenInto(parentNode, out, markerCol = 0)
  return out.toString()
}

/**
 * Splits a Plain segment on top-level CommonMark block boundaries.
 * Each `Paragraph` becomes [MarkdownSegment.Plain] (routed to the
 * inline chip parser); every other top-level block becomes
 * [MarkdownSegment.NonProseBlock] (routed to the non-prose block
 * renderer). [LinkReferenceDefinition] is dropped (the link inside
 * the `Paragraph` already has the resolved URL).
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
    .filter { blockNode -> blockNode !is LinkReferenceDefinition }
    .map { blockNode ->
      val serialized: String = serializeInto(blockNode, markerCol = 0)
      if (blockNode is Paragraph) MarkdownSegment.Plain(text = serialized)
      else MarkdownSegment.NonProseBlock(text = serialized)
    }
}

private fun serializeChildrenInto(
  parentNode: Node, out: StringBuilder, markerCol: Int
) {
  val children: NodeChildren = NodeChildren.of(parentNode)
  val first: Node? = children.first
  if (first != null) serializeInto(first, out, markerCol)
  for (childNode in children.rest) serializeInto(childNode, out, markerCol)
}

private fun serializeInto(currentNode: Node, markerCol: Int): String {
  val out: StringBuilder = StringBuilder()
  serializeInto(currentNode, out, markerCol)
  return out.toString()
}

private fun serializeInto(
  currentNode: Node, out: StringBuilder, markerCol: Int
) {
  when (currentNode) {
    is Document -> serializeChildrenInto(currentNode, out, markerCol)
    is Paragraph -> serializeParagraphInto(currentNode, out)
    is Heading -> serializeHeadingInto(currentNode, out)
    is Text -> out.append(currentNode.literal.orEmpty())
    is Emphasis -> serializeWrapInlineInto("*", "*", currentNode, out)
    is StrongEmphasis -> serializeWrapInlineInto("**", "**", currentNode, out)
    is Code -> out.append('`').append(currentNode.literal.orEmpty()).append('`')
    is Strikethrough -> serializeStrikethroughInto(currentNode, out)
    is Link -> serializeLinkInto(currentNode, out)
    is Image -> serializeImageInto(currentNode, out)
    is SoftLineBreak -> out.append('\n')
    is HardLineBreak -> out.append("\\\n")
    is BulletList -> serializeBulletListInto(currentNode, out)
    is OrderedList -> serializeOrderedListInto(currentNode, out)
    is BlockQuote -> serializeBlockQuoteInto(currentNode, out)
    is FencedCodeBlock -> serializeFencedCodeBlockInto(currentNode, out)
    is IndentedCodeBlock -> out.append(currentNode.literal.orEmpty())
    is ThematicBreak -> out.append("---")
    is HtmlBlock -> out.append(currentNode.literal.orEmpty())
    is HtmlInline -> out.append(currentNode.literal.orEmpty())
    else -> serializeChildrenInto(currentNode, out, markerCol)
  }
}

private fun serializeWrapInlineInto(
  open: String, close: String, parentNode: Node, out: StringBuilder
) {
  out.append(open)
  serializeChildrenInto(parentNode, out, markerCol = 0)
  out.append(close)
}

private fun serializeStrikethroughInto(currentNode: Strikethrough, out: StringBuilder) {
  out.append("~~")
  serializeChildrenInto(currentNode, out, markerCol = 0)
  out.append("~~")
}

private fun serializeParagraphInto(paragraph: Paragraph, out: StringBuilder) {
  serializeChildrenInto(paragraph, out, markerCol = 0)
}

private fun serializeHeadingInto(heading: Heading, out: StringBuilder) {
  repeat(heading.level) { out.append('#') }
  out.append(' ')
  serializeChildrenInto(heading, out, markerCol = 0)
}

private fun serializeLinkInto(link: Link, out: StringBuilder) {
  out.append('[')
  serializeChildrenInto(link, out, markerCol = 0)
  out.append("](")
  out.append(link.destination.orEmpty())
  if (link.title != null) out.append(" \"").append(link.title).append('"')
  out.append(')')
}

private fun serializeImageInto(image: Image, out: StringBuilder) {
  out.append("![")
  serializeChildrenInto(image, out, markerCol = 0)
  out.append("](")
  out.append(image.destination.orEmpty())

  if (image.title != null)
    out.append(" \"").append(image.title).append('"')
  out.append(')')
}

private fun serializeBulletListInto(bulletList: BulletList, out: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(bulletList)
  val first: ListItem? = children.first as? ListItem
  if (first != null) {
    serializeListItemInto(first, marker = "- ", out)
    for (restNode in children.rest) {
      if (restNode is ListItem) {
        out.append('\n')
        serializeListItemInto(restNode, marker = "- ", out)
      }
    }
  }
}

private fun serializeOrderedListInto(orderedList: OrderedList, out: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(orderedList)
  val first: ListItem? = children.first as? ListItem
  if (first != null) {
    val firstMarker = "${orderedList.startNumber}. "
    serializeListItemInto(first, marker = firstMarker, out)
    var itemNumber: Int = orderedList.startNumber + 1

    for (restNode in children.rest) {
      if (restNode is ListItem) {
        out.append('\n')
        serializeListItemInto(restNode, marker = "$itemNumber. ", out)
        itemNumber += 1
      }
    }
  }
}

/**
 * Serialize a `ListItem`: emit `<marker>` then the first child inline
 * (on the same line as the marker); subsequent children (nested list,
 * second paragraph, fenced code) are emitted on the next line(s)
 * indented 4 columns so Markdown recognizes them as continuation
 * content of the list item (not a new top-level list). The 4-column
 * minimum is the CommonMark rule for list-item continuation.
 */
private fun serializeListItemInto(listItem: ListItem, marker: String, out: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(listItem)
  out.append(marker)
  val first: Node? = children.first
  if (first != null) serializeInto(first, out, markerCol = 0)
  // Indent is fixed at 4 columns. The marker is the leading prefix on the first line.
  // Continuation lines: the first non-space char must align at column 4 (not relative to marker).
  val indent: String = " ".repeat(LIST_CHILD_INDENT_COLUMNS)
  for (childNode in children.rest) {
    out.append('\n')
    out.append(indent)
    serializeInto(childNode, out, markerCol = 0)
  }
}

private fun serializeBlockQuoteInto(blockQuote: BlockQuote, out: StringBuilder) {
  val children: NodeChildren = NodeChildren.of(blockQuote)
  val first: Node? = children.first
  if (first != null) {
    out.append("> ")
    // Only prefix continuation lines with `> `, not the first line — we already wrote it above.
    out.append(prefixEachLineExceptFirst(serializeInto(first, markerCol = 0), prefix = "> "))
  }
  for (childNode in children.rest) {
    out.append('\n')
    out.append("> ")
    out.append(prefixEachLineExceptFirst(serializeInto(childNode, markerCol = 0), prefix = "> "))
  }
}

private fun serializeFencedCodeBlockInto(block: FencedCodeBlock, out: StringBuilder) {
  out.append("```")
  val info: String? = block.info
  if (!info.isNullOrBlank()) out.append(info)

  out.append('\n')
  out.append(block.literal.orEmpty())
  if (!out.endsWith('\n')) out.append('\n')

  out.append("```")
}

/** Prefix every line of [text] except the first with [prefix]. */
private fun prefixEachLineExceptFirst(text: String, prefix: String): String {
  if (!text.contains('\n')) return text
  val lines: List<String> = text.split('\n')

  return lines.mapIndexed { lineIndex, line ->
    if (lineIndex == 0 || line.isEmpty()) line else "$prefix$line"
  }.joinToString(separator = "\n")
}

private fun StringBuilder.endsWith(suffix: Char): Boolean =
  isNotEmpty() && this[length - 1] == suffix
