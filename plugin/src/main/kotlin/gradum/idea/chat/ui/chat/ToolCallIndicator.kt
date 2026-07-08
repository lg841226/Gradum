/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallIndicator.kt  2026-07-07 22:11:36 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalColorPalette
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys


/**
 * Max width the `reason` text takes inside [RanToolCallIndicator] /
 * the trailing text slot. Without this, a long LLM-generated reason
 * string would claim the whole row width and crowd out the
 * "open in editor" affordance. Anything wider than this is
 * truncated with ellipsis and horizontally scrollable on hover /
 * focus.
 */
private val REASON_MAX_WIDTH_DP: Dp = 200.dp

@Composable
private fun ToolCallCapsule(
    iconKey: IconKey,
    label: String,
    success: Boolean,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    errorDetail: String = "",
    trailingText: String = "",
    trailingIcon: @Composable RowScope.() -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var showErrorPopup by remember { mutableStateOf(false) }
    var isCopied by remember { mutableStateOf(false) }
    val hasError = !success && errorMessage.isNotBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hasError) Modifier
                    .clickable { showErrorPopup = true } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
        Icon(iconKey, contentDescription = null)
        Text(
            text = label,
            fontWeight = FontWeight.Medium,
            color = JewelTheme.globalColors.text.normal
        )

        if (trailingText.isNotBlank()) {
            Text(
                maxLines = 1,
                text = trailingText,
                overflow = TextOverflow.Ellipsis,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .widthIn(max = REASON_MAX_WIDTH_DP)
            )
        }

        trailingIcon()

        if (!success) {
            Icon(
                key = AllIconsKeys.Status.FailedInProgress,
                contentDescription = message("gradum.tool.failed")
            )
        }
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
                            text = if (isCopied) message("gradum.error.copied")
                            else message("gradum.error.copy.hint"),
                            onClick = {
                                if (!isCopied) {
                                    copyToClipboard(
                                        scope = scope,
                                        text = errorDetail,
                                        onCopied = { isCopied = true },
                                        onReset = { isCopied = false }
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

/**
 * Format a byte count into a human-readable string. Picks the
 * largest unit that still gives a value >= 1, with one decimal
 * for KB / MB / GB. Used by [SavedToolCallContent] to render
 * the saved file's size on the tool call capsule.
 *
 *     512          -> "512 B"
 *     1_500        -> "1.5 KB"
 *     2_000_000    -> "1.9 MB"
 *     4_500_000_000 -> "4.2 GB"
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kbValue: Double = bytes / 1024.0
    if (kbValue < 1024.0) return "%.1f KB".format(kbValue)
    val mbValue: Double = kbValue / 1024.0
    if (mbValue < 1024.0) return "%.1f MB".format(mbValue)
    val gbValue: Double = mbValue / 1024.0
    return "%.1f GB".format(gbValue)
}

/**
 * Default capsule renderer for tool calls without per-tool UI
 * (i.e. anything not handled by a specialised [RanToolCallIndicator],
 * [FileToolCallIndicator], or [SavedToolCallIndicator]). Shows just
 * the alias icon + label + optional failure indicator.
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
        label = alias,
        success = success,
        modifier = modifier,
        errorMessage = errorMessage,
        errorDetail = errorDetail
    )
}

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
        label = alias,
        success = success,
        modifier = modifier,
        errorMessage = errorMessage,
        errorDetail = errorDetail,
        trailingText = reason,
        trailingIcon = {
            OpenInEditorButton(target = command, onOpenInEditor = onOpenInEditor)
        }
    )
}

@Composable
fun FileToolCallIndicator(
    alias: String,
    filePath: String,
    sizeText: String? = null,
    linesAdded: Int = 0,
    linesRemoved: Int = 0,
    success: Boolean = true,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    errorDetail: String = "",
    onOpenInEditor: (String) -> Unit = {},
    onViewDiff: () -> Unit = {},
    hasDiffPayload: Boolean = false,
) {
    val iconKey = if (alias == "Edited") GradumIcons.Edit else AllIconsKeys.General.Show
    val fileName = filePath.substringAfterLast('/')
    val displayText = if (sizeText != null) "$fileName · $sizeText" else fileName

    ToolCallCapsule(
        iconKey = iconKey,
        label = alias,
        success = success,
        modifier = modifier,
        errorMessage = errorMessage,
        errorDetail = errorDetail,
        trailingText = displayText,
        trailingIcon = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (linesAdded > 0) {
                    // greenOrNull(5) returns the LaF's mid-saturation green,
                    // or null if the theme didn't ship a full palette. Falling
                    // back to text.info (a soft blue) keeps the row readable
                    // even on themes that don't define `green(5)` — the IDE
                    // ships a full palette so this branch is rare in practice.
                    val addedColor = LocalColorPalette.current.greenOrNull(5)
                        ?: JewelTheme.globalColors.text.info
                    Text(
                        text = "+$linesAdded",
                        color = addedColor
                    )
                }
                if (linesRemoved > 0) {
                    Text(
                        text = "-$linesRemoved",
                        color = JewelTheme.globalColors.text.error
                    )
                }
                if (alias == "Edited" && success && hasDiffPayload) {
                    ViewDiffButton(onViewDiff = onViewDiff)
                } else {
                    OpenInEditorButton(target = filePath, onOpenInEditor = onOpenInEditor)
                }
            }
        }
    )
}

private fun aliasIconKey(alias: String): IconKey = when (alias) {
    "Ran" -> GradumIcons.Ran
    "Edited" -> GradumIcons.Edit
    "Saved" -> GradumIcons.Save
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
                modifier = Modifier.clickable { onOpenInEditor(target) }
            )
        }
    }
}

@Composable
private fun ViewDiffButton(
    onViewDiff: () -> Unit
) {
    Tooltip(tooltip = { Text(text = message("gradum.tool.view.diff")) }) {
        Icon(
            key = AllIconsKeys.Actions.Diff,
            contentDescription = null,
            modifier = Modifier.clickable { onViewDiff() }
        )
    }
}
