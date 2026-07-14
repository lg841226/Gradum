/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * NodeChildren.kt  2026-07-14 21:12:53 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import org.commonmark.node.Node

/**
 * Direct children of a CommonMark [Node] in source order, exposed as a small
 * wrapper so callers don't have to manually walk `firstChild` / `next`. The
 * split between [first] and [rest] is intentional: list-item rendering
 * differentiates "first child is a `Paragraph` (rendered on the marker row)"
 * from "later children (rendered below with extra indent)", and exposing
 * those two groups via named properties reads better than
 * `.firstOrNull()` / `.drop(1)` and is impossible to misuse as raw indices.
 */
internal class NodeChildren private constructor(
  val first: Node?, val rest: List<Node>
) {
  val isEmpty: Boolean get() = first == null
  val isNotEmpty: Boolean get() = first != null

  companion object {
    val Empty: NodeChildren = NodeChildren(first = null, rest = emptyList())

    fun of(parent: Node): NodeChildren {
      val allChildren: List<Node> = walkDirectChildren(parent)
      return if (allChildren.isEmpty()) {
        Empty
      } else {
        NodeChildren(first = allChildren[0], rest = allChildren.drop(1))
      }
    }

    private fun walkDirectChildren(parentNode: Node): List<Node> {
      val collected: MutableList<Node> = mutableListOf()
      var child: Node? = parentNode.firstChild
      while (child != null) {
        collected += child
        child = child.next
      }
      return collected
    }
  }
}
