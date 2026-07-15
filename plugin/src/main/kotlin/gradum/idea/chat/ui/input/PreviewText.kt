/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PreviewText.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum.idea.chat.ui.input

private const val MAX_PREVIEW_CODE_POINTS: Int = 30

/**
 * Truncates [sourceText] to at most [maxCodePoints] Unicode code points, appending
 * an ellipsis if any characters were dropped. Used to render short previews
 * of message text where splitting a multibyte emoji or CJK glyph mid-string
 * would be visually wrong.
 */
fun truncateToCodePoints(sourceText: String, maxCodePoints: Int = MAX_PREVIEW_CODE_POINTS): String {
  if (sourceText.length <= maxCodePoints) return sourceText

  val buffer = StringBuilder()

  for ((index, codePoint) in sourceText.codePoints().toArray().withIndex()) {
    if (index >= maxCodePoints) break
    buffer.appendCodePoint(codePoint)
  }

  return buffer.append("...").toString()
}
