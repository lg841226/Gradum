/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AddContextPopup.kt  2026-06-26 23:55:00 Changed by gwy
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
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Popup menu for adding context files.
 */
@Composable
fun AddContextPopup(
    searchState: TextFieldState,
    filteredFiles: List<VirtualFile>,
    state: ChatInputState,
    actions: ChatInputActions
) {
    PopupMenu(
        onDismissRequest = { actions.onDismissAddMenu(); true },
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.heightIn(max = 300.dp)
    ) {
        passiveItem {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(key = GradumIcons.Search, contentDescription = message("gradum.add.popup.search"))
                Spacer(Modifier.width(6.dp))
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
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(key = AllIconsKeys.Actions.ProjectDirectory, contentDescription = message("gradum.add.popup.project.directory"))
                Spacer(Modifier.width(6.dp))
                Text(text = message("gradum.add.popup.project.directory"))
            }
        }

        selectableItem(
            selected = false,
            onClick = { if (!state.isAttachmentLimitReached) actions.onUploadImage() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(key = GradumIcons.Image, contentDescription = message("gradum.add.popup.upload.image"))
                Spacer(Modifier.width(6.dp))
                Text(text = message("gradum.add.popup.upload.image"))
            }
        }

        separator()

        passiveItem {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(text = message("gradum.add.popup.workspace"), fontWeight = FontWeight.Medium)
            }
        }

        if (state.editorContext.allOpenFiles.isEmpty()) {
            passiveItem {
                Text(
                    text = message("gradum.add.popup.empty"),
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        } else if (filteredFiles.isEmpty()) {
            passiveItem {
                Text(
                    text = message("gradum.add.popup.no.results"),
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
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
