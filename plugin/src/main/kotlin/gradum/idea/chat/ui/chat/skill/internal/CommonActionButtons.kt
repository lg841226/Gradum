/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommonActionButtons.kt  2026-07-14 21:27:12 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.internal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gradum.idea.bundle.GradumBundle.message
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Open-in-editor action button. Reusable across Ran / Read / Saved
 * / Edited renderers. Lives next to [ToolCallCapsule] because
 * every default renderer that touches a file uses it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OpenInEditorButton(
  filePath: String,
  startLine: Int? = null,
  endLine: Int? = null,
  onClick: () -> Unit
) {
  if (filePath.isNotBlank()) {
    Tooltip(tooltip = { Text(text = message("gradum.tool.open.in.editor")) }) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.General.Export,
        modifier = Modifier.clickable { onClick() }
      )
    }
  }
}

@Composable
internal fun ViewDiffButton(onClick: () -> Unit) {
  Tooltip(tooltip = { Text(text = message("gradum.tool.view.diff")) }) {
    Icon(
      contentDescription = null,
      key = AllIconsKeys.Actions.Diff,
      modifier = Modifier.clickable { onClick() }
    )
  }
}
