/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * FootnoteRegistry.kt  2026-08-24 22:01:29 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * Per-message registry linking footnote labels to their definition positions
 * (in scroll-column coordinates). Reference chips look up their label and
 * ask the scroll owner to animate to the definition's offset.
 *
 * A label may be defined more than once; [scrollToFootnote] jumps to the
 * definition requiring the least scrolling distance from the current offset.
 */
class FootnoteRegistry(
  private val getColumnOrigin: () -> Offset?,
  private val getCurrentScrollOffset: () -> Float = { 0f },
) {
  /** Scrolls column content to position (px), then calls back for label on completion. */
  var scrollToPosition: (position: Float, label: String) -> Unit = { _, _ -> }

  /** The definition chip that should flash right now. `null` when idle. */
  var flashTarget: FlashTarget? by mutableStateOf(null)
    private set

  /** A jump target: the [label] to flash plus a [nonce] so re-clicking the same label re-triggers. */
  data class FlashTarget(val label: String, val nonce: Long)

  /** Each definition chip owns a slot (keyed by its stable chipId) so positions stay fresh. */
  private val definitionPositionsByLabel = mutableMapOf<String, MutableMap<Any, Float>>()

  fun updateDefinitionPosition(label: String, chipId: Any, positionInWindow: Offset) {
    val origin: Offset = getColumnOrigin() ?: return
    definitionPositionsByLabel
      .getOrPut(label) { mutableMapOf() }[chipId] = positionInWindow.y - origin.y
  }

  /** Scrolls to the nearest registered definition for [label]; no-op if none exist. */
  fun scrollToFootnote(label: String) {
    val positions: Collection<Float> = definitionPositionsByLabel[label]?.values ?: return
    val currentScrollOffset: Float = getCurrentScrollOffset()
    val nearestDefinition: Float =
      positions.minByOrNull { abs(it - currentScrollOffset) } ?: return
    scrollToPosition(nearestDefinition, label)
  }

  /** Marks [label] as the definition to flash (after its scroll has finished). */
  fun onJumpComplete(label: String) {
    flashTarget = FlashTarget(label, System.nanoTime())
  }
}

val LocalFootnoteRegistry = staticCompositionLocalOf {
  FootnoteRegistry(getColumnOrigin = { null })
}
