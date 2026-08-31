/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MarkdownConstants.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumSpacing

/** Centralized style constants for the Markdown renderer. */
internal object MarkdownStyle {

  /** Inline code chip rendering. */
  object InlineCode {
    val CORNER_RADIUS: Dp = GradumSpacing.sm
    val PADDING_VERTICAL: Dp = GradumSpacing.xs
    val PADDING_HORIZONTAL: Dp = GradumSpacing.sm
    val CHIP_BORDER_WIDTH: Dp = 0.5.dp
    const val BACKGROUND_ALPHA: Float = 0.16f
    const val CHIP_BORDER_ALPHA: Float = 0.3f
    const val CHIP_BASELINE_RATIO: Float = 0.75f
    const val PLACEHOLDER_PADDING_SP: Float = 10f
    const val CHIP_HEIGHT_MULTIPLIER: Float = 1.2f
  }

  /** Image and alt-text rendering. */
  object Image {
    const val ALT_COLOR_ALPHA: Float = 0.6f
    const val ALT_ICON_EM_SCALE: Float = 1.4f
  }

  /** Code block rendering. */
  object CodeBlock {
    const val COLLAPSE_LIMIT: Int = 20
    const val GUTTER_DIVIDER_ALPHA: Float = 0.5f
    const val LINE_HEIGHT_ADJUSTMENT: Float = 0.85f
    val COLLAPSE_STRIP_PADDING: Dp = GradumSpacing.sm
    val CORNER_RADIUS: RoundedCornerShape =
      RoundedCornerShape(
        size = GradumSpacing.md
      )
    val STICKY_SECTION_TOP_CORNERS: RoundedCornerShape =
      RoundedCornerShape(
        topStart = 0.dp, topEnd = 0.dp
      )
  }

  /** Block-level list and heading rendering. */
  object Block {
    val ORDERED_MARKER_MIN_WIDTH: Dp = 24.dp
    val UNORDERED_MARKER_MIN_WIDTH: Dp = 20.dp
    val MARKER_CONTENT_GAP: Dp = GradumSpacing.sm
    val NESTED_LIST_INDENT_STEP: Dp = GradumSpacing.xl
    val LIST_ITEM_VERTICAL_SPACING: Dp = GradumSpacing.md
    val THEMATIC_BREAK_VERTICAL_SPACING: Dp = GradumSpacing.lg
    val LIST_OUTER_PADDING: PaddingValues =
      PaddingValues(
        vertical = GradumSpacing.md
      )
    val HEADING_EXTRA_PADDING: PaddingValues =
      PaddingValues(
        top = GradumSpacing.lg, bottom = GradumSpacing.sm
      )
    const val LIST_CHILD_INDENT_COLUMNS: Int = 4
    const val BODY_LINE_HEIGHT_MULTIPLIER: Float = 1.3f
  }

  /** Table rendering. */
  object Table {
    val MIN_CELL_WIDTH: Dp = 75.dp
    val MAX_CELL_WIDTH: Dp = 620.dp
    const val MAX_LINE_LENGTH: Int = 5_000
    val CELL_HORIZONTAL_PADDING: Dp = 10.dp
    val CELL_VERTICAL_PADDING: Dp = GradumSpacing.md
    val SCROLLBAR_RESERVED_SPACE: Dp = GradumSpacing.md
  }

  /** Inline LaTeX and placeholder measurement. */
  object Latex {
    const val LETTER_RATIO: Float = 0.45f
    const val DIGIT_RATIO: Float = 0.45f
    const val MONOSPACE_CJK_RATIO: Float = 1.0f
    const val NARROW_SYMBOL_RATIO: Float = 0.25f
    const val DEFAULT_SYMBOL_RATIO: Float = 0.35f
    const val MONOSPACE_LATIN_RATIO: Float = 0.6f
    const val PLACEHOLDER_WIDTH_PADDING_SP: Float = 2f
    const val PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 1.0f
    const val INLINE_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 1.4f
    val NARROW_SYMBOLS: Set<Char> =
      setOf(
        ',', '.', ';', ':', '!', '?', '|', '(', ')', '[', ']',
        '{', '}', '<', '>', '/', '\\', '~', '^', '*', '+', '=', '-', '_', '`', '\''
      )
    const val INLINE_PLACEHOLDER_PADDING_SP: Float = PLACEHOLDER_WIDTH_PADDING_SP
  }

  /** Block-level LaTeX rendering. */
  object BlockLatex {
    const val DEFAULT_FONT_SIZE_SP: Float = 14f
    const val VERTICAL_PADDING_DP: Float = 16f
    const val MIN_CONTENT_HEIGHT_DP: Float = 12f
    const val FALLBACK_TEXT_VERTICAL_PADDING_DP: Float = 2f
  }

  /** Font fallback sizes. */
  object FontFallback {
    const val WEIGHT: Int = 500
    const val EM_SIZE_SP: Float = 14f
    const val BODY_SIZE_SP: Float = 14f
    const val BLOCK_SIZE_SP: Float = 13f
  }

  /** Segment fade-in animation. */
  object SegmentAnimation {
    val RISE_DP: Dp = 8.dp
    const val MAX_EXTRA_MS: Int = 400
    const val BASE_DURATION_MS: Int = 150
    const val EXTRA_PER_100DP_MS: Int = 50
  }

  /** Footnote flash animation. */
  object FootnoteAnimation {
    const val FLASH_IN_MS: Int = 200
    const val FLASH_OUT_MS: Int = 300
    const val FLASH_HOLD_MS: Long = 150
  }
}
