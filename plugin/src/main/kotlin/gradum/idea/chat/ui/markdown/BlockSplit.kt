/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * BlockSplit.kt  2026-08-24 21:56:54 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val blockSplitParser: Parser = Parser.builder()
  .extensions(listOf(StrikethroughExtension.create(), LatexBlockExtension.create()))
  .build()

/** Indent step (in columns) for child blocks inside a list item. */
private const val LIST_CHILD_INDENT_COLUMNS: Int = 4

/**
 * Re-serializes a CommonMark node back to its Markdown source. Top-level
 * callers use [serializeMarkdownNode] or [serializeInlineChildren]
 * (children only, used for paragraph-in-block and list-item rendering paths).
 */
internal fun serializeMarkdownNode(rootNode: Node): String =
  serializeInto(currentNode = rootNode)

internal fun serializeInlineChildren(containerNode: Node): String {
  val resultBuilder: StringBuilder = StringBuilder()
  serializeChildrenInto(containerNode, output = resultBuilder)
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
    addAll(elements = children.rest)
  }

  return blocks
    .filter { topLevelBlock: Node -> topLevelBlock !is LinkReferenceDefinition }
    .map { topLevelBlock: Node ->
      when (topLevelBlock) {
        is Paragraph -> MarkdownSegment.Plain(text = serializeInto(currentNode = topLevelBlock))
        is HtmlBlock -> stripHtmlBlockTags(htmlBlock = topLevelBlock)?.let { stripped: String ->
          MarkdownSegment.Plain(text = stripped)
        } ?: MarkdownSegment.NonProseBlock(text = serializeInto(currentNode = topLevelBlock))

        is LatexBlock -> MarkdownSegment.NonProseBlock(
          text = serializeInto(currentNode = topLevelBlock)
        )

        else -> MarkdownSegment.NonProseBlock(
          text = serializeInto(currentNode = topLevelBlock)
        )
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
  for (childNode: Node in children.rest) appendHtmlBlockChild(childNode, resultBuilder)
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
  val children: NodeChildren = NodeChildren.of(parent = containerNode)
  val firstChild: Node? = children.first
  if (firstChild != null) serializeInto(currentNode = firstChild, output)
  for (childNode: Node in children.rest) serializeInto(currentNode = childNode, output)
}

internal fun serializeInto(currentNode: Node): String {
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
    is Paragraph -> serializeChildrenInto(currentNode, output)
    is LatexBlock -> serializeLatexBlockInto(currentNode, output)

    // nodes serialized as their raw literal text
    is HtmlBlock -> appendLiteral(currentNode, output)
    is IndentedCodeBlock -> appendLiteral(currentNode, output)
    is Text -> appendLiteral(currentNode, output)

    // inline nodes
    is Code -> output.append('`').append(currentNode.literal.orEmpty()).append('`')
    is Emphasis -> serializeWrapInlineInto("*", "*", currentNode, output)
    is StrongEmphasis -> serializeWrapInlineInto("**", "**", currentNode, output)
    is Link -> serializeLinkInto(currentNode, output)
    is Image -> serializeImageInto(currentNode, output)
    is Strikethrough -> serializeStrikethroughInto(currentNode, output)
    is SoftLineBreak -> output.append('\n')
    is HardLineBreak -> output.append("\\\n")
    is HtmlInline -> Unit // strip raw markers from round-tripped markdown

    else -> serializeChildrenInto(currentNode, output)
  }
}

/** Appends a node's literal text, if any. */
private fun appendLiteral(node: Node, output: StringBuilder) {
  val literalValue: String? = when (node) {
    is HtmlBlock -> node.literal
    is IndentedCodeBlock -> node.literal
    is Text -> node.literal
    else -> null
  }
  output.append(literalValue.orEmpty())
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

private fun serializeLinkLikeNode(
  openingMarker: String,
  containerNode: Node,
  destination: String?,
  title: String?,
  output: StringBuilder,
) {
  output.append(openingMarker)
  serializeChildrenInto(containerNode, output)
  output.append("](")
  output.append(destination.orEmpty())
  if (title != null) output.append(" \"").append(title).append('"')
  output.append(')')
}

private fun serializeLinkInto(link: Link, output: StringBuilder) =
  serializeLinkLikeNode("[", link, link.destination, link.title, output)

private fun serializeImageInto(image: Image, output: StringBuilder) =
  serializeLinkLikeNode("![", image, image.destination, image.title, output)

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
  val indent: String = " ".repeat(LIST_CHILD_INDENT_COLUMNS)
  for (childNode in children.rest) {
    output.append('\n')
    output.append(indentEveryLine(serializeInto(childNode), indent))
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


private fun serializeLatexBlockInto(latexBlock: LatexBlock, output: StringBuilder) {
  output.append("$$\n")
  output.append(latexBlock.formula)
  output.append("\n$$")
}

/** Prefix every line of [content] with [indent]. */
private fun indentEveryLine(content: String, indent: String): String {
  if (content.isEmpty()) return content
  val lines: List<String> = content.split('\n')
  return lines.joinToString(separator = "\n") { line -> indent + line }
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
