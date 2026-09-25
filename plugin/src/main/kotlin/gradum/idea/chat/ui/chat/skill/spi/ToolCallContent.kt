/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallContent.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.spi

/**
 * Generic, renderer-agnostic view-model for a single tool-call bubble.
 *
 * The previous design pinned this shape to a `sealed class` with one
 * subclass per server-side skill alias, which made it impossible to add
 * a new tool call UI without modifying the Gradum plugin source. This
 * open data class is the corresponding shape that any third-party
 * `ToolCallRenderer` (registered as an IntelliJ extension on
 * `gradum.skill.toolCallRenderer`) can produce freely.
 *
 * A renderer is free to:
 * - Put any fields it understands into [fields] (e.g. `path: String`,
 *   `linesAdded: Int`, `command: String`, `url: String`). The Gradum
 *   chat UI never inspects [fields] — only the originating renderer
 *   does, when its `render` composable is invoked.
 * - Expose any user actions (open in editor, view diff, open in
 *   browser, copy command, custom) via [actions]. The default
 *   `DefaultRenderer` renders the standard set; third-party renderers
 *   are free to ship their own action bar.
 *
 * The base class is intentionally minimal — the bulk of the UI logic
 * lives in each renderer's `render` composable.
 */
data class ToolCallContent(
  /** Server-side skill alias this content was parsed from, e.g. "Ran" / "Written" / "Read". */
  val aliasName: String,
  /** Free-form payload that the originating [ToolCallRenderer] alone interprets. */
  val fieldMap: Map<String, Any?> = emptyMap(),
  /** User-facing actions (open in editor, view diff, copy, etc.) attached to this call. */
  val actionList: List<ToolCallAction> = emptyList()
)
