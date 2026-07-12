/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ErrorsPanel.kt
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalJewelApi::class)

package gradum.idea.chat.ui.chat.skill.internal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Reserve this much right-side whitespace after `:line` so the
 *  line number doesn't visually stick to the chat column's right
 *  edge. Visual breathing room, not layout-driven — the spacer
 *  is inside the row, not the container, so it scales with the
 *  font size of the line number. */
private val LINE_LABEL_TRAILING_GAP = 20.dp

/**
 * Trailing gap between the row's leftmost `Error` icon and the
 * message body, so a long list of issues scans as "icon · msg"
 *  pairs rather than as one dense run of glyphs.
 */
private val ICON_TO_MESSAGE_GAP = GradumSpacing.sml

/**
 * Soft ceiling on the inline errors panel's vertical height.
 *
 * The ceiling is required by Compose: a `Modifier.verticalScroll`
 * child must be measured against finite `maxHeight` constraints,
 * which the chat column's outer `Column` (wrap-content) cannot
 * provide. Without this bound the runtime throws
 * `Vertically scrollable component was measured with an infinity
 * maximum height constraints`. The 480dp figure was picked
 * deliberately loose: short lists (≤ 24 single-line rows) expand
 * to their natural height and never trigger the scrollbar; only
 * the long-tail "50 unresolved references" cases actually scroll
 * inside the panel, and then the `VerticalScrollbar` on the right
 * is the user-visible signal that there is more to read.
 *
 * The number itself is not a hard product cap — it is the size
 * the chat column's parent (the wrapping layout) would have
 * had to hand us for verticalScroll to be possible. Set higher
 * if a 24-row error list becomes plausible.
 */
private val PANEL_MAX_HEIGHT = 480.dp

/**
 * A single error / warning entry to render inside [ErrorsPanelContent].
 *
 * Mirrors the wire shape `gradum.utils.SyntaxChecker.SyntaxIssue` emits
 * on the server side — `line` / `column` / `errorCode` are nullable
 * because not every backend parser yields them. `message` is the only
 * required field; the panel falls back to a "—" marker when `line`
 * is null so the column layout never collapses.
 */
data class SyntaxErrorEntry(
  val message: String,
  val line: Int? = null,
  val column: Int? = null,
  val errorCode: String? = null,
)

/**
 * Extract a [SyntaxErrorEntry] list from a tool result's raw maps.
 *
 * Two sources are merged in order:
 *  1. `syntaxErrors` — a `List<Map<String, Any?>>` produced by
 *     [gradum.utils.SyntaxChecker] for successful edits that left
 *     the file in a broken state. The map shape is `{ message,
 *     severity, line, column, errorCode }`.
 *  2. A single synthetic entry from `error.message` when the tool
 *     call itself failed (`success == false`); the tool's
 *     `error.code` becomes the entry's `errorCode`.
 *
 * Skips entries with a blank `message` because the panel can't
 * render an empty line meaningfully and a blank message is almost
 * always a parser artifact.
 */
fun parseSyntaxErrors(resultMap: Map<String, Any?>): List<SyntaxErrorEntry> {
  val entries: MutableList<SyntaxErrorEntry> = mutableListOf()

  @Suppress("UNCHECKED_CAST")
  val rawList: List<Map<String, Any?>>? = resultMap["syntaxErrors"] as? List<Map<String, Any?>>
  if (rawList != null) {
    for (raw: Any? in rawList) {
      if (raw !is Map<*, *>) continue
      val entryMap: Map<String, Any?> = raw as? Map<String, Any?> ?: continue
      val message: String = entryMap["message"] as? String ?: continue
      if (message.isBlank()) continue

      val line: Int? = (entryMap["line"] as? Number)?.toInt()
      val column: Int? = (entryMap["column"] as? Number)?.toInt()
      val errorCode: String? = entryMap["errorCode"] as? String
      entries.add(
        SyntaxErrorEntry(
          message = message,
          line = line,
          column = column,
          errorCode = errorCode,
        )
      )
    }
  }

  val isSuccess: Boolean = resultMap["success"] as? Boolean ?: true
  if (!isSuccess) {
    @Suppress("UNCHECKED_CAST")
    val errorMap: Map<String, Any?>? = resultMap["error"] as? Map<String, Any?>
    val errorMessage: String? = errorMap?.get("message") as? String
    val errorCode: String? = errorMap?.get("code") as? String
    if (!errorMessage.isNullOrBlank()) {
      entries.add(
        SyntaxErrorEntry(
          message = errorMessage,
          errorCode = errorCode,
        )
      )
    }
  }

  return entries
}

/**
 * Toggle button rendered next to the existing [ViewDiffButton] /
 * [OpenInEditorButton] on a tool-call capsule. Shows an error icon
 * plus a chevron that flips between down/right based on
 * [isExpanded]. The icon's tooltip carries the error count so the
 * capsule row stays visually compact.
 *
 * Renders nothing when [errors] is empty so a successful edit with
 * no syntax issues stays identical to the pre-existing capsule.
 */
@Composable
internal fun ErrorsToggleButton(
  isExpanded: Boolean,
  onToggle: () -> Unit,
  errors: List<SyntaxErrorEntry>
) {
  if (errors.isEmpty()) return

  val tooltipText: String = if (isExpanded)
    message("gradum.tool.hide.errors")
  else
    message("gradum.tool.errors.count", errors.size)


  Tooltip(tooltip = { Text(text = tooltipText) }) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .clickable(onClick = onToggle)
        .padding(GradumSpacing.xs)
    ) {
      // No error icon next to the chevron — the panel is the
      // visual signal, the toggle is just a fold/unfold control.
      // Mirrors the thinking-mode toggle exactly, only the
      // tooltip text differs.
      Icon(
        contentDescription = null,
        key = if (isExpanded) AllIconsKeys.General.ChevronDown
        else AllIconsKeys.General.ChevronRight,
      )
    }
  }
}

/**
 * Inline expanded panel rendered directly under the tool-call
 * capsule (not as a popup) — mirrors [ThinkingIndicator]'s
 * "expand to reveal thinking" pattern. Each entry is a row of
 * [error icon | message | :line], with `message` in the normal
 * body text color and `line` in the muted `info` color at the
 * same size, exactly per the design brief.
 *
 * `line` is rendered as a clickable affordance: clicking jumps
 * the IDE editor to that line via [onLineClick]. Null lines
 * fall back to a "—" so the column alignment never breaks.
 */
@Composable
internal fun ErrorsPanelContent(
  errors: List<SyntaxErrorEntry>,
  onLineClick: (line: Int) -> Unit
) {
  if (errors.isEmpty()) return

  val textColor = JewelTheme.globalColors.text.normal
  val infoColor = JewelTheme.globalColors.text.info
  val scrollState = rememberScrollState()

  // Box hosts the scrolling Column + a native `VerticalScrollbar`
  // anchored to the right edge. The Box carries a `heightIn(max)`
  // because Compose's `verticalScroll` cannot be measured against
  // infinite max-height constraints — the chat column's outer
  // `Column` is wrap-content and would hand us infinity otherwise.
  // 480dp is deliberately loose: short lists expand to their
  // natural height (well under the cap, no scroll); only the
  // long-tail "50 unresolved references" case actually scrolls,
  // and the scrollbar makes that visible.
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = PANEL_MAX_HEIGHT)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = GradumSpacing.xs, bottom = GradumSpacing.xs)
        .verticalScroll(scrollState),
      // Larger gap between error rows so a long list of compiler
      // issues reads as discrete lines instead of one dense block.
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      for (entry in errors) {
        // Capitalize the first character so the panel matches the
        // convention of editor error tooltips (which are sentence-
        // cased) — Kotlin compiler messages arrive in mixed case
        // ("redundant SAM constructor", "unresolved reference: foo").
        val displayMessage: String = capitalizeErrorMessage(entry.message)
        Row(
          verticalAlignment = Alignment.Top,
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(
            contentDescription = null,
            key = AllIconsKeys.General.Error,
            modifier = Modifier.padding(top = 2.dp)
          )
          Spacer(modifier = Modifier.width(ICON_TO_MESSAGE_GAP))
          Text(
            text = displayMessage,
            color = textColor,
            // Hard-cap each error to a single ellipsized line so a
            // long list of compiler issues reads as a clean column
            // instead of a wall of wrapped text. `weight(1f, fill
            // = false)` shares the row's remaining width with
            // `:line`, which sits flush against the message so the
            // two read as a single "msg:line" unit.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          val lineLabel: String = entry.line?.let { ":$it" } ?: "—"
          if (entry.line != null) {
            Text(
              text = lineLabel,
              color = infoColor,
              modifier = Modifier.clickable { onLineClick(entry.line) }
            )
          } else {
            Text(text = lineLabel, color = infoColor)
          }
          // Visual breathing room on the right of `:line` so the
          // number doesn't pin to the chat column's right edge.
          // 20dp is a rough visual rule of thumb — enough to
          // register as deliberate whitespace without looking
          // like a separate column.
          Spacer(modifier = Modifier.width(LINE_LABEL_TRAILING_GAP))
        }
      }
    }

    // Native `VerticalScrollbar` so the user can see at a glance
    // how much of the error list is hidden, not just on hover.
    VerticalScrollbar(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .fillMaxHeight(),
      adapter = rememberScrollbarAdapter(scrollState = scrollState)
    )
  }
}

/**
 * Convenience wrapper that combines the animated visibility
 * around [ErrorsPanelContent]. Kept as a small composable so
 * every renderer that wants this behavior uses the same
 * expand/fade-in transition without duplicating boilerplate.
 */
@Composable
internal fun ErrorsPanel(
  errors: List<SyntaxErrorEntry>,
  isExpanded: Boolean,
  onLineClick: (line: Int) -> Unit,
) {
  AnimatedVisibility(
    visible = isExpanded,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
  ) {
    ErrorsPanelContent(
      errors = errors,
      onLineClick = onLineClick,
    )
  }
}

/**
 * Helper for renderers that only have a raw `Map<String, Any?>`
 * result on hand — wraps [parseSyntaxErrors] in a `remember` so the
 * conversion isn't repeated on every recomposition.
 */
@Composable
internal fun rememberSyntaxErrors(resultMap: Map<String, Any?>): List<SyntaxErrorEntry> =
  remember(resultMap) { parseSyntaxErrors(resultMap) }

/**
 * Capitalize the first non-whitespace character of a compiler
 * error message so the panel reads as sentence-cased prose
 * (matching the convention of editor tooltips). Idempotent for
 * already-cased strings and blank strings; skips any leading
 * whitespace so messages that begin with a quote or tag still
 * surface their first content character.
 *
 * Examples:
 *  - `"redundant SAM constructor"` → `"Redundant SAM constructor"`
 *  - `"Unresolved reference: foo"` → `"Unresolved reference: foo"` (unchanged)
 *  - `""` → `""`
 *  - `"  bad indent"` → `"  Bad indent"`
 */
internal fun capitalizeErrorMessage(message: String): String {
  val firstNonWs: Int = message.indexOfFirst { !it.isWhitespace() }
  if (firstNonWs == -1) return message
  val firstChar: Char = message[firstNonWs]
  if (!firstChar.isLowerCase()) return message
  return message.substring(0, firstNonWs) +
    firstChar.titlecase() +
    message.substring(firstNonWs + 1)
}
