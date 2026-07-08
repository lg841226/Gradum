/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AddContextPopup.kt  2026-07-02 16:15:04 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.state.GradumChatSession.Companion.MAX_ATTACHMENTS
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Popup menu for adding context files.
 */
@Composable
fun AddContextPopup(
    searchState: TextFieldState,
    filteredFiles: List<VirtualFile>,
    state: ChatInputState,
    actions: ChatInputActions,
    modifier: Modifier = Modifier
) {
    PopupMenu(
        onDismissRequest = { actions.onDismissAddMenu(); true },
        horizontalAlignment = Alignment.Start,
        modifier = modifier.heightIn(max = 300.dp)
    ) {
        passiveItem {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
            ) {
                Icon(key = GradumIcons.Search, contentDescription = message("gradum.add.popup.search"))
                Spacer(modifier = Modifier.width(GradumSpacing.md))
                TextField(
                    state = searchState,
                    undecorated = true,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 160.dp)
                        .widthIn(max = 200.dp),
                    placeholder = { Text(text = message("gradum.add.popup.search.placeholder")) }
                )
            }
        }
        separator()

        selectableItem(
            selected = false,
            onClick = { state.editorContext.projectDir?.let(actions.onSelectFile) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    key = AllIconsKeys.Actions.ProjectDirectory,
                    contentDescription = message("gradum.add.popup.project.directory")
                )
                Spacer(modifier = Modifier.width(GradumSpacing.md))
                Text(text = message("gradum.add.popup.project.directory"))
            }
        }

        // Hidden when the active model is text-only (no vision =
        // irrelevant option) and disabled when the per-message
        // image cap is hit. The disabled dark background on
        // `selectableItem(enabled = false)` reads as a visual
        // glitch rather than a "you cannot do this" hint, which
        // is why vision-unsupported is a visibility gate.
        val isVisionSupported: Boolean = state.isCurrentModelSupportsVision
        if (isVisionSupported) {
            val isLimitReached: Boolean = state.isAttachmentLimitReached
            val isUploadEnabled: Boolean = !isLimitReached
            val tooltipText: String? = if (isLimitReached) {
                message("gradum.image.limit.reached", MAX_ATTACHMENTS)
            } else null

            // `JewelTheme.globalColors` is `@Composable`, so color
            // resolution must live inside the `selectableItem`
            // lambda rather than the (non-composable) popup scope.
            selectableItem(
                selected = false,
                enabled = isUploadEnabled,
                onClick = { if (isUploadEnabled) actions.onUploadImage() }
            ) {
                val uploadTint: androidx.compose.ui.graphics.Color =
                    if (isUploadEnabled) JewelTheme.globalColors.text.normal
                    else JewelTheme.globalColors.text.normal.copy(alpha = 0.5f)
                val row: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = GradumSpacing.md,
                                vertical = GradumSpacing.xs
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                key = GradumIcons.Image,
                                contentDescription = message("gradum.add.popup.upload.image"),
                                modifier = Modifier.size(GradumSpacing.lrl)
                            )
                            Spacer(modifier = Modifier.width(GradumSpacing.md))
                            Text(text = message("gradum.add.popup.upload.image"), color = uploadTint)
                        }
                        Text(
                            color = JewelTheme.globalColors.text.info,
                            text = message("gradum.add.popup.upload.image.hint", MAX_ATTACHMENTS)
                        )
                    }
                }
                if (tooltipText != null) Tooltip(tooltip = { Text(tooltipText) }) { row() } else row()
            }
        }

        separator()

        passiveItem {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
            ) {
                Text(
                    text = message("gradum.add.popup.workspace"),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (state.editorContext.allOpenFiles.isEmpty()) {
            passiveItem {
                Text(
                    text = message("gradum.add.popup.empty"),
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.md)
                )
            }
        } else if (filteredFiles.isEmpty()) {
            passiveItem {
                Text(
                    text = message("gradum.add.popup.no.results"),
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.md)
                )
            }
        } else {
            filteredFiles.forEach { file ->
                selectableItem(
                    selected = file == state.editorContext.currentFile,
                    onClick = { actions.onSelectFile(file) }
                ) {
                    FileItem(file = file, isSelected = file == state.editorContext.currentFile)
                }
            }
        }
    }
}
