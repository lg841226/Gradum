/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * StickySection.kt  2026-07-30
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

class StickySectionRegistry {
  private val _entries = mutableStateListOf<StickySectionEntry>()
  val entries: List<StickySectionEntry> get() = _entries

  var columnOriginInWindow: Offset? by mutableStateOf(null)

  fun register(id: Any, toolbar: @Composable () -> Unit): StickySectionEntry {
    val entry = StickySectionEntry(id, toolbar)
    _entries.add(entry)
    return entry
  }

  fun unregister(entry: StickySectionEntry) {
    _entries.remove(entry)
  }

  fun updateBounds(entry: StickySectionEntry, boundsInWindow: Rect) {
    val origin = columnOriginInWindow ?: return
    entry.topInColumn = boundsInWindow.top - origin.y
    entry.bottomInColumn = boundsInWindow.bottom - origin.y
  }
}

class StickySectionEntry(
  val id: Any,
  val toolbar: @Composable () -> Unit,
) {
  var topInColumn by mutableStateOf(0f)
  var bottomInColumn by mutableStateOf(0f)
  var toolbarHeight by mutableStateOf(0f)
}

val LocalStickySectionRegistry = staticCompositionLocalOf { StickySectionRegistry() }
