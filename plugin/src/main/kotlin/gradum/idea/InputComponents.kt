/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InputComponents.kt  2026-06-26 15:59:00 Changed by gwy
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Duration.Companion.milliseconds


/**
 * The main chat input panel containing a text area and a toolbar.
 * Long-paste-to-context: when the text state grows by more than 200 chars
 * in a single settled snapshot, treat the appended run as a context
 * attachment and strip it back out of the input.
 */
@Composable
fun ChatInputPanel(
    state: ChatInputState,
    actions: ChatInputActions,
    roundedCornerShape: RoundedCornerShape,
    textState: TextFieldState
) {

    val initialTextLength = remember { textState.text.length }
    var previousTextLength by remember { mutableStateOf(initialTextLength) }
    LaunchedEffect(textState.text) {
        delay(50.milliseconds)
        val currentInputText = textState.text.toString()
        val baselineTextLength = previousTextLength
        val appendedTextLength = currentInputText.length - baselineTextLength

        if (appendedTextLength > 200) {
            val appendedText = currentInputText.substring(baselineTextLength)
            if (appendedText.isNotBlank()) {
                actions.onPasteAsContext(appendedText)
                textState.edit { delete(baselineTextLength, currentInputText.length) }
            }
        }
        previousTextLength = textState.text.length
    }

    Box(
        modifier = Modifier
            .onFocusChanged { actions.onFocusChange(it.hasFocus) }
            .thenIf(!state.isFocused) {
                border(
                    alignment = Stroke.Alignment.Inside,
                    width = 1.dp,
                    color = JewelTheme.globalColors.borders.normal,
                    shape = roundedCornerShape
                )
            }
            .focusOutline(
                showOutline = state.isFocused,
                outlineShape = roundedCornerShape
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            if (state.pendingMessages.isNotEmpty()) {
                state.pendingMessages.forEach { pending ->
                    val singleLineContent = pending.content.replace("\n", " ")
                    val preview = truncateToCodePoints(singleLineContent, MAX_PREVIEW_CODE_POINTS)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp)
                        )
                        SweepLightText(
                            text = preview,
                            modifier = Modifier.weight(1f)
                        )
                        IconTooltipButton(
                            tooltip = message("gradum.remove"),
                            iconKey = AllIconsKeys.Actions.Close,
                            contentDescription = message("gradum.remove"),
                            onClick = { actions.onRemovePending(pending) },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            TextArea(
                state = textState,
                placeholder = { Text(message("gradum.input.placeholder")) },
                undecorated = true,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 60.dp, max = 160.dp)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            keyEvent.key == Key.Enter && (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) -> {
                                if (!state.isSending || !state.isPendingQueueFull) actions.onSend()
                                true
                            }

                            else -> false
                        }
                    }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ChatToolbar(
                state = state,
                actions = actions,
                isTextNotEmpty = textState.text.isNotEmpty()
            )

            AttachmentBar(
                attachedFiles = state.attachedFiles,
                onRemoveFile = actions.onRemoveFile
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
    state: ChatInputState,
    actions: ChatInputActions,
    textState: TextFieldState,
    onRefreshModels: () -> Unit = {}
) {
    Column(modifier = modifier) {
        ChatInputPanel(
            state = state,
            actions = actions,
            roundedCornerShape = RoundedCornerShape(6.dp),
            textState = textState
        )
        Spacer(modifier = Modifier.height(6.dp))
        ModelSelectorBar(
            models = state.models,
            selectedModel = state.selectedModel,
            onRefresh = onRefreshModels,
            onSelectModel = actions.onSelectModel
        )
    }
}

/**
 * Toolbar row inside [ChatInputPanel] with add-menu, permission selector, and action buttons.
 */
@Composable
fun ChatToolbar(
    state: ChatInputState,
    actions: ChatInputActions,
    isTextNotEmpty: Boolean
) {
    val searchState = remember { TextFieldState() }

    val searchQuery = searchState.text.toString()
    val filteredFiles = if (searchQuery.isBlank()) {
        state.editorContext.allOpenFiles
    } else {
        state.editorContext.allOpenFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTooltipButton(
            tooltip = if (state.isAttachmentLimitReached) message("gradum.add.context.disabled") else message("gradum.add.context"),
            iconKey = AllIconsKeys.General.Add,
            contentDescription = message("gradum.add"),
            onClick = actions.onToggleAddMenu,
            enabled = !state.isAttachmentLimitReached
        )
        if (state.showAddMenu) {
            AddContextPopup(
                searchState = searchState,
                filteredFiles = filteredFiles,
                state = state,
                actions = actions
            )
        }

        PermissionSelector(
            selectedPermission = state.selectedPermission,
            isMenuVisible = state.isMenuVisible,
            onToggle = actions.onToggleMenu,
            onSelect = actions.onSelectPermission,
            onDismiss = actions.onDismissMenu
        )

        Spacer(modifier = Modifier.weight(1f))

        IconTooltipButton(
            tooltip = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context"),
            iconKey = if (state.isExpanded) AllIconsKeys.Actions.Unshare else AllIconsKeys.Actions.Share,
            contentDescription = if (state.isExpanded) message("gradum.hide.context") else message("gradum.show.context"),
            onClick = actions.onToggleExpanded
        )

        IconTooltipButton(
            tooltip = message("gradum.clear"),
            iconKey = AllIconsKeys.General.Delete,
            contentDescription = message("gradum.delete"),
            onClick = actions.onClearText,
            enabled = isTextNotEmpty
        )

        Row {
            if (state.isSending || state.pendingMessages.isNotEmpty()) {
                IconTooltipButton(
                    tooltip = message("gradum.stop"),
                    iconKey = AllIconsKeys.Run.Stop,
                    contentDescription = message("gradum.stop.response"),
                    onClick = actions.onStop
                )
            }
            IconTooltipButton(
                tooltip = if (state.isSending && state.isPendingQueueFull) message("gradum.send.queue.full") else message("gradum.send"),
                iconKey = GradumIcons.Send,
                contentDescription = message("gradum.send"),
                onClick = actions.onSend,
                enabled = isTextNotEmpty && !state.isPendingQueueFull
            )
        }
    }
}

/**
 * Popup menu for adding context files.
 */
@Composable
private fun AddContextPopup(
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
                Icon(
                    key = GradumIcons.Search,
                    contentDescription = message("gradum.add.popup.search"),
                    modifier = Modifier.padding(end = 6.dp)
                )
                TextField(
                    state = searchState,
                    undecorated = true,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 160.dp)
                        .widthIn(max = 200.dp),
                    placeholder = { Text(message("gradum.add.popup.search.placeholder")) }
                )
            }
        }
        separator()

        selectableItem(
            selected = false,
            onClick = {
                state.editorContext.projectDir?.let(actions.onSelectFile)
            }) {
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
            onClick = { if (!state.isAttachmentLimitReached) actions.onUploadImage() }
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
                    onClick = { actions.onSelectFile(file) },
                ) {
                    FileItem(
                        file = file,
                        isSelected = file == state.editorContext.currentFile
                    )
                }
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
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
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
                IconTooltipButton(
                    tooltip = message("gradum.remove"),
                    iconKey = AllIconsKeys.Actions.Close,
                    contentDescription = message("gradum.remove"),
                    onClick = { onRemoveFile(attachedContext) },
                    modifier = Modifier.size(18.dp)
                )
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
                            text = message("gradum.readonly.info"),
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


private val modelDisplayNames = mapOf(
    "qwen2.5" to "Qwen 2.5",
    "qwen2.5-coder" to "Qwen 2.5 Coder",
    "qwen2.5vl" to "Qwen 2.5 VL",
    "qwen3" to "Qwen 3",
    "qwen3.5" to "Qwen 3.5",
    "qwen3.6" to "Qwen 3.6",
    "qwen3-coder" to "Qwen 3 Coder",
    "qwen3-coder-next" to "Qwen 3 Coder Next",
    "qwen3-next" to "Qwen 3 Next",
    "qwen3-vl" to "Qwen 3 VL",
    "llama3" to "Llama 3",
    "llama3.1" to "Llama 3.1",
    "llama3.2" to "Llama 3.2",
    "llama3.3" to "Llama 3.3",
    "llama4" to "Llama 4",
    "deepseek-r1" to "DeepSeek R1",
    "deepseek-v2" to "DeepSeek V2",
    "deepseek-v2.5" to "DeepSeek V2.5",
    "deepseek-v3" to "DeepSeek V3",
    "deepseek-v3.1" to "DeepSeek V3.1",
    "deepseek-v3.2" to "DeepSeek V3.2",
    "deepseek-coder" to "DeepSeek Coder",
    "deepseek-coder-v2" to "DeepSeek Coder V2",
    "minimax-m2" to "MiniMax M2",
    "minimax-m2.1" to "MiniMax M2.1",
    "minimax-m2.5" to "MiniMax M2.5",
    "minimax-m2.7" to "MiniMax M2.7",
    "minimax-m3" to "MiniMax M3",
    "gemma3" to "Gemma 3",
    "gemma4" to "Gemma 4",
    "glm-5" to "GLM 5",
    "glm-5.1" to "GLM 5.1",
    "glm-5.2" to "GLM 5.2",
    "kimi-k2" to "Kimi K2",
    "kimi-k2.5" to "Kimi K2.5",
    "kimi-k2.6" to "Kimi K2.6",
    "gpt-oss" to "ChatGPT 4 Nano",
)

/**
 * Converts a raw model name (e.g. "qwen2.5-coder:cloud") to a display-friendly name.
 * Uses [modelDisplayNames] lookup first, then falls back to title-cased hyphen replacement.
 */
fun formatModelName(raw: String): String {
    val modelBase = raw.substringBefore(":")
    modelDisplayNames[modelBase]?.let { return it }
    return modelBase.replace("-", " ").replaceFirstChar { it.uppercase() }
}

/**
 * Bottom bar showing the current model name and a feedback link.
 */
@Composable
fun ModelSelectorBar(
    models: List<ModelInfo> = emptyList(),
    selectedModel: ModelInfo? = null,
    onRefresh: () -> Unit = {},
    onSelectModel: (ModelInfo) -> Unit = {}
) {
    var showModelMenu by remember { mutableStateOf(false) }

    val displayModels = remember(models, selectedModel) {
        if (selectedModel != null) {
            listOf(selectedModel) + models.filter {
                it.name != selectedModel.name || it.serverName != selectedModel.serverName
            }
        } else
            models
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            SelectorButton(
                text = selectedModel?.let { formatModelName(it.name) } ?: message("gradum.model.none"),
                contentDescription = message("gradum.model.select"),
                onClick = { showModelMenu = true }
            )
            if (showModelMenu) {
                PopupMenu(
                    onDismissRequest = { showModelMenu = false; true },
                    horizontalAlignment = Alignment.Start
                ) {
                    passiveItem {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                message("gradum.model"),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (models.isEmpty()) {
                        passiveItem { ModelAutoItemContent(enabled = false) }
                        separator()
                        passiveItem { RefreshButtonItem(onRefresh) }
                    } else {
                        selectableItem(
                            selected = selectedModel == null,
                            onClick = { showModelMenu = false }
                        ) { ModelAutoItemContent(enabled = true) }
                        separator()
                        displayModels.forEach { model ->
                            val isSelected = selectedModel?.let {
                                it.name == model.name && it.serverName == model.serverName
                            } == true
                            selectableItem(
                                selected = isSelected,
                                onClick = {
                                    onSelectModel(model)
                                    showModelMenu = false
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(formatModelName(model.name))
                                    if (model.serverName.isNotBlank()) {
                                        Text(
                                            text = model.serverName,
                                            color = JewelTheme.globalColors.text.info,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                    IconTooltipButton(
                                        tooltip = message("gradum.model.pin"),
                                        iconKey = if (isSelected) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
                                        contentDescription = message("gradum.model.pin"),
                                        onClick = {
                                            onSelectModel(model)
                                            showModelMenu = false
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        separator()
                        passiveItem { RefreshButtonItem(onRefresh) }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        ExternalLink(
            text = message("gradum.feedback"),
            onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
        )
    }
}

@Composable
private fun RefreshButtonItem(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            message("gradum.model.refresh"),
            color = JewelTheme.globalColors.text.info
        )
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onRefresh) {
            Icon(
                key = AllIconsKeys.General.Refresh,
                contentDescription = message("gradum.refresh")
            )
        }
    }
}

@Composable
private fun ModelAutoItemContent(enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 6.dp)
    ) {
        Icon(
            key = GradumIcons.Auto,
            contentDescription = message("gradum.auto.model"),
            modifier = if (enabled) Modifier else Modifier.alpha(0.4f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            message("gradum.model.auto"),
            color = if (enabled) JewelTheme.globalColors.text.normal
            else JewelTheme.globalColors.text.disabled
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
 * Reusable icon button with tooltip, reducing repeated Tooltip+IconButton+Icon patterns.
 */
@Composable
fun IconTooltipButton(
    tooltip: String,
    iconKey: IconKey,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Tooltip(tooltip = { Text(tooltip) }) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(key = iconKey, contentDescription = contentDescription)
        }
    }
}

private const val MAX_PREVIEW_CODE_POINTS = 30

private fun truncateToCodePoints(text: String, maxCodePoints: Int): String {
    if (text.length <= maxCodePoints) return text
    val buffer = StringBuilder()
    for ((index, codePoint) in text.codePoints().toArray().withIndex()) {
        if (index >= maxCodePoints) break
        buffer.appendCodePoint(codePoint)
    }
    return buffer.append("...").toString()
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