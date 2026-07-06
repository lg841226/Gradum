/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PreviewText.kt  2026-07-05 16:42:55 Changed by gwy
 */

package gradum.idea.chat.ui.input

private const val MAX_PREVIEW_CODE_POINTS: Int = 30

/**
 * Truncates [text] to at most [maxCodePoints] Unicode code points, appending
 * an ellipsis if any characters were dropped. Used to render short previews
 * of message text where splitting a multibyte emoji or CJK glyph mid-string
 * would be visually wrong.
 */
fun truncateToCodePoints(text: String, maxCodePoints: Int = MAX_PREVIEW_CODE_POINTS): String {
  if (text.length <= maxCodePoints) return text

  val buffer = StringBuilder()

  for ((index, codePoint) in text.codePoints().toArray().withIndex()) {
    if (index >= maxCodePoints) break
    buffer.appendCodePoint(codePoint)
  }

  return buffer.append("...").toString()
}
