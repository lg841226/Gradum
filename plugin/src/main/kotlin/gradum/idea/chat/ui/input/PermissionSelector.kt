/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PermissionSelector.kt  2026-07-05 18:03:06 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Wire-format identifiers sent to the server. Kept as constants so
 * [gradum.idea.chat.state.GradumChatSession.selectedPermission] and
 * [PermissionSelector] can never disagree on spelling.
 */
object PermissionMode {
  const val READONLY = "read_only"
  const val EDIT = "edit"
  const val AGENT = "agent"
}

/**
 * UI label for a [PermissionMode] wire value. The selector stores the
 * wire value but shows the user-facing label; this is the one place
 * that maps between the two. The previous implementation stored the
 * label in [gradum.idea.chat.state.GradumChatSession.selectedPermission]
 * and translated to wire inside the tool window factory, which meant
 * the initial state was always the label and the translation was
 * never run — a silent default back to "write" on every fresh session.
 */
fun permissionLabel(wire: String): String = when (wire) {
  PermissionMode.READONLY -> message("gradum.read")
  PermissionMode.EDIT -> message("gradum.edit")
  PermissionMode.AGENT -> message("gradum.agent")
  else -> wire
}

/**
 * Dropdown that switches between read-only, edit, and full agent
 * permissions. Stores the wire-format value (e.g. "read_only") in
 * [gradum.idea.chat.state.GradumChatSession.selectedPermission] so the
 * server receives a parseable enum name; the user-facing label is
 * looked up via [permissionLabel].
 */
@Composable
fun PermissionSelector(
  selectedPermission: String,
  isMenuVisible: Boolean,
  onToggle: () -> Unit,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  SelectorButton(
    text = permissionLabel(selectedPermission),
    onClick = onToggle,
    contentDescription = message("gradum.select.permissions"),
    color = JewelTheme.globalColors.text.normal
  )

  if (isMenuVisible) {
    PopupMenu(
      onDismissRequest = { onDismiss(); true },
      horizontalAlignment = Alignment.Start
    ) {
      selectableItem(
        selected = selectedPermission == PermissionMode.READONLY,
        onClick = { onSelect(PermissionMode.READONLY) }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = GradumSpacing.md,
              vertical = GradumSpacing.xs
            ),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(key = AllIconsKeys.General.ReaderMode, contentDescription = message("gradum.read.mode"))
          Spacer(modifier = Modifier.width(GradumSpacing.md))
          Column {
            Text(text = message("gradum.read"))
            Text(
              text = message("gradum.read.info"),
              color = JewelTheme.globalColors.text.info
            )
          }
        }
      }
      selectableItem(
        selected = selectedPermission == PermissionMode.EDIT,
        onClick = { onSelect(PermissionMode.EDIT) }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = GradumSpacing.md,
              vertical = GradumSpacing.xs
            ),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(key = GradumIcons.Edit, contentDescription = message("gradum.edit.mode"))
          Spacer(modifier = Modifier.width(GradumSpacing.md))
          Column {
            Text(text = message("gradum.edit"))
            Text(
              text = message("gradum.edit.info"),
              color = JewelTheme.globalColors.text.info
            )
          }
        }
      }
      selectableItem(
        selected = selectedPermission == PermissionMode.AGENT,
        onClick = { onSelect(PermissionMode.AGENT) }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = GradumSpacing.md,
              vertical = GradumSpacing.xs
            ),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(key = GradumIcons.Build, contentDescription = message("gradum.agent.mode"))
          Spacer(modifier = Modifier.width(GradumSpacing.md))
          Column {
            Text(text = message("gradum.agent"))
            Text(
              text = message("gradum.agent.info"),
              color = JewelTheme.globalColors.text.info
            )
          }
        }
      }
    }
  }
}
