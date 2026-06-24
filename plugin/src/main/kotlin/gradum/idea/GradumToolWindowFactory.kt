/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-06-24 19:23:32 Changed by gwy
 */

package gradum.idea

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import gradum.idea.GradumBundle.message
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.Stroke
import org.jetbrains.jewel.foundation.modifier.border
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.focusOutline
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography


/**
 * Registers the Gradum chat panel as an IntelliJ tool window tab.
 *
 * Creates a Compose-backed tab under the tool window so the Gradum UI
 * renders natively inside the IDE rather than as a webview or dialog.
 */
class GradumToolWindowFactory : ToolWindowFactory {

    @OptIn(ExperimentalJewelApi::class)
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
            SwingBridgeTheme {
                GradumUI(toolWindow = toolWindow)
            }
        }
    }
}

/**
 * Root composable for the Gradum chat interface inside the IDE tool window.
 *
 * Owns all interaction state (input text, sending state, permission choice,
 * context expansion, focus) and arranges the input panel above the model
 * selector bar. State flows down; events flow up via lambda callbacks.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun GradumUI(toolWindow: ToolWindow? = null) {
    var isFocused by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isMenuVisible by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var selectedPermission by remember { mutableStateOf(message("gradum.readonly")) }
    var hasSentMessage by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val textState = rememberTextFieldState("")
    val roundedCornerShape = RoundedCornerShape(6.dp)

    LaunchedEffect(hasSentMessage) {
        val content = toolWindow?.contentManager?.contents?.firstOrNull()
        if (content != null) {
            content.displayName = if (hasSentMessage) message("gradum.toolwindow.newchat") else message("gradum.toolwindow.welcome")
        }
    }
    val onSend: () -> Unit = {
        val text = textState.text.toString()
        if (text.isNotBlank()) {
            messages.add(ChatMessage(role = "user", content = text))
            messages.add(ChatMessage(role = "assistant", content = ""))
            hasSentMessage = true
            isSending = true
            textState.edit { delete(0, length) }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        if (hasSentMessage) {
            Column(modifier = Modifier.fillMaxSize()) {
                ChatMessageList(
                    messages = messages,
                    isLoading = isSending,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                ChatInputSection(
                    isFocused = isFocused,
                    roundedCornerShape = roundedCornerShape,
                    onFocusChange = { isFocused = it },
                    textState = textState,
                    selectedPermission = selectedPermission,
                    isSending = isSending,
                    isMenuVisible = isMenuVisible,
                    isExpanded = isExpanded,
                    onToggleMenu = { isMenuVisible = !isMenuVisible },
                    onSelectPermission = { selectedPermission = it; isMenuVisible = false },
                    onDismissMenu = { isMenuVisible = false },
                    onToggleExpanded = { isExpanded = !isExpanded },
                    onClearText = { textState.edit { delete(0, length) } },
                    onSend = onSend
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column {
                    Text(
                        message("gradum.brand.name"),
                        color = JewelTheme.globalColors.outlines.focused,
                        style = JewelTheme.typography.h2TextStyle.copy(
                            fontFamily = JewelTheme.typography.editorTextStyle.fontFamily
                        ),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message("gradum.welcome.title"),
                        style = JewelTheme.typography.h2TextStyle
                    )
                }
                Spacer(Modifier.height(20.dp))
                ChatInputSection(
                    isFocused = isFocused,
                    roundedCornerShape = roundedCornerShape,
                    onFocusChange = { isFocused = it },
                    textState = textState,
                    selectedPermission = selectedPermission,
                    isSending = isSending,
                    isMenuVisible = isMenuVisible,
                    isExpanded = isExpanded,
                    onToggleMenu = { isMenuVisible = !isMenuVisible },
                    onSelectPermission = { selectedPermission = it; isMenuVisible = false },
                    onDismissMenu = { isMenuVisible = false },
                    onToggleExpanded = { isExpanded = !isExpanded },
                    onClearText = { textState.edit { delete(0, length) } },
                    onSend = onSend
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message("gradum.disclaimer"),
                    style = JewelTheme.typography.small,
                    fontFamily = JewelTheme.typography.editorTextStyle.fontFamily,
                    color = JewelTheme.globalColors.text.info
                )
            }
        }
    }
}

/**
 * Bordered text input area with an action toolbar underneath.
 *
 * Owns focus tracking and delegates toolbar events upward via lambdas.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ChatInputPanel(
    isFocused: Boolean,
    roundedCornerShape: RoundedCornerShape,
    onFocusChange: (Boolean) -> Unit,
    textState: TextFieldState,
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit
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
                isTextNotEmpty = textState.text.isNotEmpty(),
                onToggleMenu = onToggleMenu,
                onSelectPermission = onSelectPermission,
                onDismissMenu = onDismissMenu,
                onToggleExpanded = onToggleExpanded,
                onClearText = onClearText,
                onSend = onSend
            )
        }

    }
}

/**
 * Groups [ChatInputPanel] and [ModelSelectorBar] vertically.
 *
 * Convenience wrapper used in both the welcome and chat‑active layouts.
 */
@Composable
private fun ChatInputSection(
    isFocused: Boolean,
    roundedCornerShape: RoundedCornerShape,
    onFocusChange: (Boolean) -> Unit,
    textState: TextFieldState,
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit
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
            onToggleMenu = onToggleMenu,
            onSelectPermission = onSelectPermission,
            onDismissMenu = onDismissMenu,
            onToggleExpanded = onToggleExpanded,
            onClearText = onClearText,
            onSend = onSend
        )
        Spacer(modifier = Modifier.height(6.dp))
        ModelSelectorBar()
    }
}

/**
 * Action bar inside the chat input: add context, permission, expand, clear, send.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatToolbar(
    selectedPermission: String,
    isSending: Boolean,
    isMenuVisible: Boolean,
    isExpanded: Boolean,
    isTextNotEmpty: Boolean,
    onToggleMenu: () -> Unit,
    onSelectPermission: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClearText: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tooltip(tooltip = { Text(message("gradum.add.context")) }) {
            IconButton(onClick = { }) {
                Icon(
                    key = AllIconsKeys.General.Add,
                    contentDescription = message("gradum.add")
                )
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
 * Dropdown button that opens a [PopupMenu] to switch between read‑only and full permissions.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun PermissionSelector(
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

/**
 * Bottom bar showing the model selector and a feedback link.
 */
@Composable
private fun ModelSelectorBar() {
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
 * Reusable labeled dropdown activator used by both the model and permission selectors.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SelectorButton(
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
 * Custom path-based icon keys used throughout the Gradum UI.
 *
 * Icons are loaded from the plugin resource tree via [PathIconKey] and
 * referenced in Compose [Icon] calls alongside JetBrains [AllIconsKeys].
 *
 * Dark variant SVGs (e.g. send_dark.svg, edit_dark.svg) already exist
 * under .../resources/icons and are resolved automatically by the
 * internal icon system.
 */
object GradumIcons {
    val Send = PathIconKey("/icons/send/send.svg", GradumIcons::class.java)
    val Edit = PathIconKey("/icons/edit/edit.svg", GradumIcons::class.java)
    val Like = PathIconKey("/icons/like/like.svg", GradumIcons::class.java)
    val LikeSelected = PathIconKey("icons/like-selected/like-selected.svg", GradumIcons::class.java)
}
