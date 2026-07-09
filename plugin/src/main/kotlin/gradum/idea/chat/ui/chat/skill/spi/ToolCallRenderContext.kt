/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallRenderContext.kt  2026-07-09 17:55:26 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.spi

import com.intellij.openapi.project.Project

/**
 * Per-render callback context. A [ToolCallRenderer] reads the IDE
 * project from here and the chat-level "open in editor" / "view diff"
 * callbacks so it doesn't need to know the full chat plumbing.
 *
 * `onOpenInEditor` and `onViewDiff` are null when the chat panel is
 * detached from a project (e.g. rendering in a preview), and
 * renderers must handle that gracefully. `startLine` / `endLine`
 * passed to `onOpenInEditor` are 1-based; `0` means "don't jump to a
 * specific line".
 *
 * `originalContent` / `modifiedContent` passed to `onViewDiff` are
 * null when the underlying tool result did not embed a diff payload
 * (e.g. the tool only reported a path). The caller (chat panel)
 * decides how to render the diff and may use a system-wide
 * `DiffViewer` instead.
 */
data class ToolCallRenderContext(
  val project: Project?,
  val isError: Boolean,
  val errorDetail: String?,
  val onCopy: ((payload: String) -> Unit)?,
  val onOpenInEditor: ((filePath: String, startLine: Int, endLine: Int) -> Unit)?,
  val onViewDiff: ((filePath: String, originalContent: String?, modifiedContent: String?) -> Unit)?
)
