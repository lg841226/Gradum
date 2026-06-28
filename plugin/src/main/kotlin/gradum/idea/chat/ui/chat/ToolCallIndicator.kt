/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallIndicator.kt  2026-06-28 12:20:54 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val capsuleShape = RoundedCornerShape(percent = 50)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallCapsule(
    iconKey: IconKey,
    alias: String,
    success: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {
        Spacer(modifier = Modifier.weight(1f))
    }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(capsuleShape)
            .background(JewelTheme.globalColors.borders.normal)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            key = iconKey,
            contentDescription = null,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = alias,
            color = JewelTheme.globalColors.text.normal,
            fontWeight = FontWeight.Medium
        )
        trailing()
        Icon(
            key = if (success) AllIconsKeys.General.GreenCheckmark else AllIconsKeys.General.Close,
            contentDescription = if (success) message("gradum.tool.success") else message("gradum.tool.failed"),
            modifier = Modifier.size(14.dp)
        )
    }
}

private fun aliasIconKey(alias: String): IconKey = when (alias) {
    "Ran" -> GradumIcons.Ran
    "Edited" -> GradumIcons.Edit
    "Read" -> AllIconsKeys.General.Show
    "Explored" -> GradumIcons.Search
    "Planned" -> AllIconsKeys.Nodes.Folder
    "Completed" -> AllIconsKeys.Actions.Checked
    else -> AllIconsKeys.Nodes.Plugin
}

/**
 * Generic capsule-shaped indicator for tool calls (non-Ran).
 */
@Composable
fun ToolCallIndicator(
    alias: String,
    success: Boolean = true,
    modifier: Modifier = Modifier
) {
    ToolCallCapsule(
        iconKey = aliasIconKey(alias),
        alias = alias,
        success = success,
        modifier = modifier
    )
}

/**
 * Capsule-shaped indicator for the "Ran" (run_cmd) tool.
 * Shows the command reason and an export button with tooltip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RanToolCallIndicator(
    reason: String,
    command: String,
    success: Boolean = true,
    modifier: Modifier = Modifier,
    onOpenInEditor: (String) -> Unit = {}
) {
    ToolCallCapsule(
        iconKey = GradumIcons.Ran,
        alias = "Ran",
        success = success,
        modifier = modifier,
        trailing = {
            if (reason.isNotBlank()) {
                Text(
                    text = reason,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (command.isNotBlank()) {
                Tooltip(tooltip = { Text(text = command) }) {
                    Icon(
                        key = AllIconsKeys.General.Export,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onOpenInEditor(command) }
                    )
                }
            }
        }
    )
}
