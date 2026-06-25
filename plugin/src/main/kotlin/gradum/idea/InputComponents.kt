/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InputComponents.kt  2026-06-25 21:09:31 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.GradumBundle.message
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.Stroke
import org.jetbrains.jewel.foundation.modifier.border
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.focusOutline
import org.jetbrains.jewel.ui.icons.AllIconsKeys


/**
 * The main chat input panel containing a text area and a toolbar.
 */
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
    isAttachmentLimitReached: Boolean,
    editorContext: EditorContext,
    attachedFiles: List<AttachedContext>,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit,
    onSelectFile: (VirtualFile) -> Unit,
    onRemoveFile: (AttachedContext) -> Unit,
    onUploadImage: () -> Unit,
    onPasteAsContext: (String) -> Unit = {}
) {
    // Long-paste-to-context: when the text state grows by more than 200 chars
    // in a single settled snapshot, treat the appended run as a context
    // attachment and strip it back out of the input.
    //
    // We do not try to intercept Cmd+V / Ctrl+V at the key level here:
    // Compose Desktop's BasicTextField can consume the paste via its
    // internal TextInputService before the Modifier chain sees the keystroke,
    // and the same heuristic also catches right-click -> Paste and
    // drag-dropped text, which Cmd+V interception would miss.
    //
    // The 200-char threshold mirrors `message.content.length > 200` on the
    // assistant bubble's "copy as context" button, so the two paths
    // behave the same. A 50ms settle window lets rapid bursts (paste +
    // immediate typing) collapse into a single snapshot so we don't trip
    // the heuristic on a handful of normal keystrokes. The baseline is
    // seeded with the current text length so that a pre-existing draft
    // (e.g. a draft restored from the project service) is not mistaken
    // for a paste on first composition.
    val initialTextLength = remember { textState.text.length }
    var previousTextLength by remember { mutableStateOf(initialTextLength) }
    LaunchedEffect(textState.text) {
        delay(50)
        val currentInputText = textState.text.toString()
        val baselineTextLength = previousTextLength
        val appendedTextLength = currentInputText.length - baselineTextLength
        if (appendedTextLength > 200) {
            val appendedText = currentInputText.substring(baselineTextLength)
            if (appendedText.isNotBlank()) {
                onPasteAsContext(appendedText)
                textState.edit { delete(baselineTextLength, currentInputText.length) }
            }
        }
        previousTextLength = textState.text.length
    }

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
                    // Cmd+Enter / Ctrl+Enter sends the message; plain Enter stays as a newline.
                    // onPreviewKeyEvent fires before the TextArea consumes the keystroke, and
                    // returning `true` swallows the event so no newline is inserted on send.
                    // Only KeyDown is handled so KeyUp cannot re-trigger send.
                    // (Long-paste conversion to a context attachment is handled out-of-band
                    // by a state-diff LaunchedEffect further down — we do not try to
                    // intercept Cmd+V here because Compose Desktop's BasicTextField can
                    // consume the paste before the Modifier chain sees it.)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            keyEvent.key == Key.Enter && (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) -> {
                                onSend()
                                true
                            }
                            else -> false
                        }
                    }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ChatToolbar(
                selectedPermission = selectedPermission,
                isSending = isSending,
                isMenuVisible = isMenuVisible,
                isExpanded = isExpanded,
                showAddMenu = showAddMenu,
                isAttachmentLimitReached = isAttachmentLimitReached,
                isTextNotEmpty = textState.text.isNotEmpty(),
                editorContext = editorContext,
                onToggleMenu = onToggleMenu,
                onSelectPermission = onSelectPermission,
                onDismissMenu = onDismissMenu,
                onToggleExpanded = onToggleExpanded,
                onClearText = onClearText,
                onSend = onSend,
                onToggleAddMenu = onToggleAddMenu,
                onDismissAddMenu = onDismissAddMenu,
                onSelectFile = onSelectFile,
                onUploadImage = onUploadImage
            )

            AttachmentBar(
                attachedFiles = attachedFiles,
                onRemoveFile = onRemoveFile
            )
        }
    }
}

/**
 * Wraps [ChatInputPanel] with a model selector bar below it.
 */
@Composable
fun ChatInputSection(
    modifier: Modifier = Modifier,
    isFocused: Boolean,
    roundedCornerShape: RoundedCornerShape,
    onFocusChange: (Boolean) -> Unit,
    textState: TextFieldState,
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    showAddMenu: Boolean,
    isAttachmentLimitReached: Boolean,
    editorContext: EditorContext,
    attachedFiles: List<AttachedContext>,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit,
    onSelectFile: (VirtualFile) -> Unit,
    onRemoveFile: (AttachedContext) -> Unit,
    onUploadImage: () -> Unit,
    onPasteAsContext: (String) -> Unit = {}
) {
    Column(modifier = modifier) {
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
            isAttachmentLimitReached = isAttachmentLimitReached,
            editorContext = editorContext,
            attachedFiles = attachedFiles,
            onToggleMenu = onToggleMenu,
            onSelectPermission = onSelectPermission,
            onDismissMenu = onDismissMenu,
            onToggleExpanded = onToggleExpanded,
            onClearText = onClearText,
            onSend = onSend,
            onToggleAddMenu = onToggleAddMenu,
            onDismissAddMenu = onDismissAddMenu,
            onSelectFile = onSelectFile,
            onRemoveFile = onRemoveFile,
            onUploadImage = onUploadImage,
            onPasteAsContext = onPasteAsContext
        )
        Spacer(modifier = Modifier.height(6.dp))
        ModelSelectorBar()
    }
}

/**
 * Toolbar row inside [ChatInputPanel] with add-menu, permission selector, and action buttons.
 */
@Composable
fun ChatToolbar(
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    showAddMenu: Boolean,
    isAttachmentLimitReached: Boolean,
    isTextNotEmpty: Boolean,
    editorContext: EditorContext,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit,
    onSelectFile: (VirtualFile) -> Unit,
    onUploadImage: () -> Unit
) {
    val state = remember { TextFieldState() }

    val searchQuery = state.text.toString()
    val filteredFiles = if (searchQuery.isBlank()) {
        editorContext.allOpenFiles
    } else {
        editorContext.allOpenFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tooltip(tooltip = {
            Text(
                if (isAttachmentLimitReached) message("gradum.add.context.disabled")
                else message("gradum.add.context")
            )
        }) {
            IconButton(onClick = onToggleAddMenu, enabled = !isAttachmentLimitReached) {
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

                selectableItem(selected = false, onClick = { editorContext.projectDir?.let(onSelectFile) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.ProjectDirectory,
                            contentDescription = message("gradum.add.popup.project.directory"),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(message("gradum.add.popup.project.directory"))
                    }
                }

                selectableItem(
                    selected = false,
                    onClick = {
                        if (!isAttachmentLimitReached) onUploadImage()
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            key = GradumIcons.Image,
                            contentDescription = message("gradum.add.popup.upload.image"),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(message("gradum.add.popup.upload.image"))
                    }
                }

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
                            selected = file == editorContext.currentFile,
                            onClick = { onSelectFile(file) },
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

/**
 * Attachment bar below the toolbar, showing selected attachments.
 */
@Composable
fun AttachmentBar(
    attachedFiles: List<AttachedContext>,
    onRemoveFile: (AttachedContext) -> Unit
) {
    if (attachedFiles.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachedFiles.forEach { attachedContext ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    key = attachedContext.iconKey,
                    contentDescription = attachedContext.displayName,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = attachedContext.displayName,
                    color = JewelTheme.globalColors.text.normal
                )
                Tooltip(tooltip = { Text(message("gradum.remove")) }) {
                    IconButton(
                        onClick = { onRemoveFile(attachedContext) },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Close,
                            contentDescription = message("gradum.remove")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dropdown that switches between readonly and full-control permissions.
 */
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
            selectableItem(
                selected = false,
                onClick = { onSelect(message("gradum.readonly")) }
            ) {
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

/**
 * Bottom bar showing the current model name and a feedback link.
 */
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

/**
 * A button styled as a selector with a chevron icon and optional tooltip.
 */
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

/**
 * A single file entry shown in the add-menu file list, with a language icon and bold name when selected.
 */
@Composable
fun FileItem(
    file: VirtualFile,
    isSelected: Boolean
) {
    val iconKey = getLanguageIconKey(file.extension)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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