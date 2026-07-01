/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallIndicator.kt  2026-06-30 23:35:47 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val capsuleShape = RoundedCornerShape(percent = 50)

@Composable
private fun ToolCallCapsule(
    iconKey: IconKey,
    alias: String,
    success: Boolean,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    errorDetail: String = "",
    trailing: @Composable RowScope.() -> Unit = {
        Spacer(modifier = Modifier.weight(1f))
    }
) {
    var showErrorPopup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }
    val hasError = !success && errorMessage.isNotBlank()

    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(capsuleShape)
                .background(JewelTheme.globalColors.borders.normal)
                .then(if (hasError) Modifier.clickable { showErrorPopup = true } else Modifier)
                .padding(horizontal = GradumSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
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
                key = if (success) AllIconsKeys.General.GreenCheckmark else AllIconsKeys.Status.FailedInProgress,
                contentDescription = if (success) message("gradum.tool.success") else message("gradum.tool.failed"),
                modifier = Modifier.size(14.dp)
            )
        }

        if (hasError && showErrorPopup) {
            PopupMenu(
                onDismissRequest = { showErrorPopup = false; true },
                horizontalAlignment = Alignment.Start
            ) {
                passiveItem {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
                    ) {
                        Icon(
                            key = AllIconsKeys.Status.FailedInProgress,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = errorMessage,
                                color = JewelTheme.globalColors.text.normal
                            )
                            Spacer(modifier = Modifier.height(GradumSpacing.xs))
                            Link(
                                text =
                                    if (isCopied) message("gradum.error.copied")
                                    else message("gradum.error.copy.hint"),
                                onClick = {
                                    if (!isCopied) {
                                        copyToClipboard(
                                            text = errorDetail,
                                            onCopied = { isCopied = true },
                                            onReset = { isCopied = false },
                                            scope = scope
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun aliasIconKey(alias: String): IconKey = when (alias) {
    "Ran" -> GradumIcons.Ran
    "Edited" -> GradumIcons.Edit
    "Read" -> AllIconsKeys.General.Show
    "Explored" -> GradumIcons.Explore
    "Planned" -> AllIconsKeys.Nodes.Folder
    "Completed" -> AllIconsKeys.Actions.Checked
    else -> AllIconsKeys.Nodes.Plugin
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OpenInEditorButton(
    target: String,
    onOpenInEditor: (String) -> Unit
) {
    if (target.isNotBlank()) {
        Tooltip(tooltip = { Text(text = message("gradum.tool.open.in.editor")) }) {
            Icon(
                key = AllIconsKeys.General.Export,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onOpenInEditor(target) }
            )
        }
    }
}

/**
 * Generic capsule-shaped indicator for tool calls (non-Ran).
 */
@Composable
fun ToolCallIndicator(
    alias: String,
    success: Boolean = true,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    errorDetail: String = ""
) {
    ToolCallCapsule(
        iconKey = aliasIconKey(alias),
        alias = alias,
        success = success,
        modifier = modifier,
        errorMessage = errorMessage,
        errorDetail = errorDetail
    )
}

/**
 * Capsule-shaped indicator for the "Ran" (run_cmd) tool.
 * Shows the command reason and an open-in-editor button with tooltip.
 */
@Composable
fun RanToolCallIndicator(
    alias: String,
    reason: String,
    command: String,
    success: Boolean = true,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    errorDetail: String = "",
    onOpenInEditor: (String) -> Unit = {}
) {
    ToolCallCapsule(
        iconKey = GradumIcons.Ran,
        alias = alias,
        success = success,
        modifier = modifier,
        errorMessage = errorMessage,
        errorDetail = errorDetail,
        trailing = {
            if (reason.isNotBlank()) {
                Box(modifier = Modifier.weight(1f).widthIn(max = 200.dp)) {
                    Text(
                        maxLines = 1,
                        text = reason,
                        overflow = TextOverflow.Ellipsis,
                        color = JewelTheme.globalColors.text.info,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
            }
            OpenInEditorButton(target = command, onOpenInEditor = onOpenInEditor)
        }
    )
}

/**
 * Capsule-shaped indicator for file-related tool calls (Read / Edited).
 * Shows the file name, optional diff summary (+X -Y), and an open-in-editor button.
 */
@Composable
fun FileToolCallIndicator(
    alias: String,
    path: String,
    linesAdded: Int = 0,
    linesRemoved: Int = 0,
    success: Boolean = true,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    errorDetail: String = "",
    onOpenInEditor: (String) -> Unit = {}
) {
    val iconKey = when (alias) {
        "Edited" -> GradumIcons.Edit; else -> AllIconsKeys.General.Show
    }

    val isEdited = alias == "Edited" && (linesAdded > 0 || linesRemoved > 0)

    ToolCallCapsule(
        iconKey = iconKey,
        alias = alias,
        success = success,
        modifier = modifier,
        errorMessage = errorMessage,
        errorDetail = errorDetail,
        trailing = {
            if (path.isNotBlank() || isEdited) {
                Box(modifier = Modifier.weight(1f).widthIn(max = 200.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (path.isNotBlank()) {
                            Text(
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = JewelTheme.globalColors.text.info,
                                text = path.substringAfterLast('/'),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (isEdited) {
                            val addedLinesColor = JewelTheme.globalColors.text.info
                            if (linesAdded > 0)
                                Text(text = "+$linesAdded", color = addedLinesColor)
                            if (linesRemoved > 0)
                                Text(text = "-$linesRemoved", color = JewelTheme.globalColors.text.error)
                        }
                    }
                }
            }
            OpenInEditorButton(target = path, onOpenInEditor = onOpenInEditor)
        }
    )
}
