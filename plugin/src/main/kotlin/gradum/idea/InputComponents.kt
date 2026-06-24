/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InputComponents.kt  2026-06-24 23:55:23 Changed by gwy
 */

package gradum.idea

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.GradumBundle.message
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.Stroke
import org.jetbrains.jewel.foundation.modifier.border
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.focusOutline
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys


@OptIn(ExperimentalJewelApi::class)
@Composable
fun ChatInputPanel(
    isFocused: Boolean,
    roundedCornerShape: RoundedCornerShape,
    onFocusChange: (Boolean) -> Unit,
    textState: TextFieldState,
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    showAddMenu: Boolean,
    editorContext: EditorContext,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit
) {
    Box(
        modifier = Modifier
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .thenIf(!isFocused) {
                border(
                    alignment = Stroke.Alignment.Inside,
                    width = 1.dp,
                    color = JewelTheme.globalColors.borders.normal,
                    shape = roundedCornerShape
                )
            }
            .focusOutline(
                showOutline = isFocused,
                outlineShape = roundedCornerShape
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {

            TextArea(
                state = textState,
                placeholder = { Text(message("gradum.input.placeholder")) },
                undecorated = true,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 60.dp, max = 160.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ChatToolbar(
                selectedPermission = selectedPermission,
                isSending = isSending,
                isMenuVisible = isMenuVisible,
                isExpanded = isExpanded,
                showAddMenu = showAddMenu,
                isTextNotEmpty = textState.text.isNotEmpty(),
                editorContext = editorContext,
                onToggleMenu = onToggleMenu,
                onSelectPermission = onSelectPermission,
                onDismissMenu = onDismissMenu,
                onToggleExpanded = onToggleExpanded,
                onClearText = onClearText,
                onSend = onSend,
                onToggleAddMenu = onToggleAddMenu,
                onDismissAddMenu = onDismissAddMenu
            )
        }

    }
}

@Composable
fun ChatInputSection(
    isFocused: Boolean,
    roundedCornerShape: RoundedCornerShape,
    onFocusChange: (Boolean) -> Unit,
    textState: TextFieldState,
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    showAddMenu: Boolean,
    editorContext: EditorContext,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit
) {
    Column {
        ChatInputPanel(
            isFocused = isFocused,
            roundedCornerShape = roundedCornerShape,
            onFocusChange = onFocusChange,
            textState = textState,
            selectedPermission = selectedPermission,
            isSending = isSending,
            isMenuVisible = isMenuVisible,
            isExpanded = isExpanded,
            showAddMenu = showAddMenu,
            editorContext = editorContext,
            onToggleMenu = onToggleMenu,
            onSelectPermission = onSelectPermission,
            onDismissMenu = onDismissMenu,
            onToggleExpanded = onToggleExpanded,
            onClearText = onClearText,
            onSend = onSend,
            onToggleAddMenu = onToggleAddMenu,
            onDismissAddMenu = onDismissAddMenu
        )
        Spacer(modifier = Modifier.height(6.dp))
        ModelSelectorBar()
    }
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatToolbar(
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    showAddMenu: Boolean,
    isTextNotEmpty: Boolean,
    editorContext: EditorContext,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit
) {
    val state = remember { TextFieldState() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tooltip(tooltip = { Text(message("gradum.add.context")) }) {
            IconButton(onClick = onToggleAddMenu) {
                Icon(
                    key = AllIconsKeys.General.Add,
                    contentDescription = message("gradum.add")
                )
            }
        }
        if (showAddMenu) {
            PopupMenu(
                onDismissRequest = { onDismissAddMenu(); true },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                passiveItem {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            key = GradumIcons.Search,
                            contentDescription = message("gradum.add.popup.search"),
                            modifier = Modifier
                                .padding(end = 6.dp)
                        )
                        TextField(
                            state = state,
                            undecorated = true,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 160.dp)
                                .widthIn(max = 200.dp),
                            placeholder = { Text(message("gradum.add.popup.search.placeholder")) }
                        )
                    }
                }
                separator()

                addMenuItem(
                    iconKey = AllIconsKeys.Actions.ProjectDirectory,
                    text = message("gradum.add.popup.project.directory")
                )

                addMenuItem(
                    iconKey = GradumIcons.Image,
                    text = message("gradum.add.popup.upload.image")
                )

                separator()

                passiveItem {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            message("gradum.add.popup.workspace"),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (editorContext.allOpenFiles.isEmpty()) {
                    passiveItem {
                        Text(
                            text = message("gradum.add.popup.empty"),
                            color = JewelTheme.globalColors.text.info,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    editorContext.allOpenFiles.forEach { file ->
                        selectableItem(
                            selected = file == editorContext.currentFile,
                            onClick = { }
                        ) {
                            FileItem(
                                file = file,
                                isSelected = file == editorContext.currentFile
                            )
                        }
                    }
                }
            }
        }

        PermissionSelector(
            selectedPermission = selectedPermission,
            isMenuVisible = isMenuVisible,
            onToggle = onToggleMenu,
            onSelect = onSelectPermission,
            onDismiss = onDismissMenu
        )

        Spacer(modifier = Modifier.weight(1f))

        Tooltip(tooltip = {
            Text(if (isExpanded) message("gradum.hide.context") else message("gradum.show.context"))
        }) {
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    key = if (isExpanded) AllIconsKeys.Actions.Unshare else AllIconsKeys.Actions.Share,
                    contentDescription = if (isExpanded) message("gradum.hide.context") else message("gradum.show.context")
                )
            }
        }

        Tooltip(tooltip = { Text(message("gradum.clear")) }) {
            IconButton(
                onClick = onClearText,
                enabled = isTextNotEmpty
            ) {
                Icon(
                    key = AllIconsKeys.General.Delete,
                    contentDescription = message("gradum.delete")
                )
            }
        }

        Tooltip(tooltip = { Text(if (isSending) message("gradum.stop") else message("gradum.send")) }) {
            IconButton(onClick = onSend, enabled = isTextNotEmpty || isSending) {
                Icon(
                    key = if (isSending) AllIconsKeys.Run.Stop else GradumIcons.Send,
                    contentDescription = message("gradum.stop.response")
                )
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun PermissionSelector(
    selectedPermission: String,
    isMenuVisible: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SelectorButton(
        text = selectedPermission,
        contentDescription = message("gradum.select.permissions"),
        onClick = onToggle,
        color = JewelTheme.globalColors.text.normal
    )

    if (isMenuVisible) {
        PopupMenu(
            onDismissRequest = { onDismiss(); true },
            horizontalAlignment = Alignment.Start
        ) {
            selectableItem(selected = false, onClick = { onSelect(message("gradum.readonly")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        key = AllIconsKeys.General.ReaderMode,
                        contentDescription = message("gradum.read.mode")
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(message("gradum.readonly"))
                        Text(
                            message("gradum.readonly.info"),
                            color = JewelTheme.globalColors.text.info
                        )
                    }
                }
            }
            selectableItem(
                selected = selectedPermission == message("gradum.full"),
                onClick = { onSelect(message("gradum.full")) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        key = GradumIcons.Edit,
                        contentDescription = message("gradum.full.mode")
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(message("gradum.full"))
                        Text(
                            message("gradum.full.info"),
                            color = JewelTheme.globalColors.text.info
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelSelectorBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectorButton(
            text = "Minimax-m2.5:cloud",
            contentDescription = message("gradum.model"),
            onClick = { }
        )
        Spacer(modifier = Modifier.weight(1f))
        ExternalLink(
            text = message("gradum.feedback"),
            onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
        )
    }
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun SelectorButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    color: Color = JewelTheme.globalColors.text.info,
    tooltip: @Composable () -> Unit = { Text(contentDescription) }
) {
    Tooltip(tooltip = tooltip) {
        IconButton(onClick = onClick) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    text = text,
                    color = color
                )
                Icon(
                    key = AllIconsKeys.General.ChevronDown,
                    contentDescription = contentDescription
                )
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
private fun MenuScope.addMenuItem(iconKey: IconKey, text: String) {
    selectableItem(
        onClick = {},
        selected = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                key = iconKey,
                contentDescription = text,
                modifier = Modifier.padding(end = 6.dp)
            )
            Column {
                Text(text)
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: VirtualFile,
    isSelected: Boolean
) {
    val iconKey = getLanguageIconKey(file.extension)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            key = iconKey ?: AllIconsKeys.FileTypes.Unknown,
            contentDescription = file.fileType.name,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(14.dp)
        )
        Column {
            Text(
                text = file.name,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
