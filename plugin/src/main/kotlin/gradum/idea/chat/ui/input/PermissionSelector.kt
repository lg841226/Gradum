/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PermissionSelector.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * Dropdown that switches between readonly and full-control permissions.
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
        text = selectedPermission,
        contentDescription = message("gradum.select.permissions"),
        onClick = onToggle,
        color = JewelTheme.globalColors.text.normal,
        modifier = modifier
    )

    if (isMenuVisible) {
        PopupMenu(onDismissRequest = { onDismiss(); true }, horizontalAlignment = Alignment.Start) {
            selectableItem(
                selected = selectedPermission == message("gradum.readonly"),
                onClick = { onSelect(message("gradum.readonly")) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(key = AllIconsKeys.General.ReaderMode, contentDescription = message("gradum.read.mode"))
                    Spacer(modifier = Modifier.width(GradumSpacing.md))
                    Column {
                        Text(text = message("gradum.readonly"))
                        Text(text = message("gradum.readonly.info"), color = JewelTheme.globalColors.text.info)
                    }
                }
            }
            selectableItem(
                selected = selectedPermission == message("gradum.single_step"),
                onClick = { onSelect(message("gradum.single_step")) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(key = GradumIcons.Edit, contentDescription = message("gradum.read.mode"))
                    Spacer(modifier = Modifier.width(GradumSpacing.md))
                    Column {
                        Text(text = message("gradum.single_step"))
                        Text(text = message("gradum.single_step.info"), color = JewelTheme.globalColors.text.info)
                    }
                }
            }
            selectableItem(
                selected = selectedPermission == message("gradum.full"),
                onClick = { onSelect(message("gradum.full")) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(key = GradumIcons.Edit, contentDescription = message("gradum.full.mode"))
                    Spacer(modifier = Modifier.width(GradumSpacing.md))
                    Column {
                        Text(text = message("gradum.full"))
                        Text(text = message("gradum.full.info"), color = JewelTheme.globalColors.text.info)
                    }
                }
            }
        }
    }
}
