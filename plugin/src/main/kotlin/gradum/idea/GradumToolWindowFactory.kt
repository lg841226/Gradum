/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-06-24 07:37:21 Changed by gwy
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
        toolWindow.addComposeTab("Gradum") {
            SwingBridgeTheme {
                GradumUI()
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
fun GradumUI() {
    var isFocused by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isMenuVisible by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var selectedPermission by remember { mutableStateOf("Read-only Permissions") }
    val textState = rememberTextFieldState("")
    val roundedCornerShape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column {
                Text(
                    "{ Ge Wangyang }",
                    color = JewelTheme.globalColors.outlines.focused,
                    style = JewelTheme.typography.h2TextStyle.copy(
                        fontFamily = JewelTheme.typography.editorTextStyle.fontFamily
                    ),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "What do you want to build today?",
                    style = JewelTheme.typography.h2TextStyle
                )
            }
            Spacer(Modifier.height(20.dp))

            Column {
                ChatInputPanel(
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
                    onSend = {
                        isSending = !isSending
                        textState.edit { delete(0, length) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                ModelSelectorBar()

            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically

        ) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "However, anything isn't perfect, check important info",
                style = JewelTheme.typography.small,
                fontFamily = JewelTheme.typography.editorTextStyle.fontFamily,
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}

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
            .focusOutline(showOutline = isFocused, outlineShape = roundedCornerShape)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
        ) {

            TextArea(
                state = textState,
                placeholder = { Text("Ask Gradum anything") },
                undecorated = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 160.dp)
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
        Tooltip(tooltip = { Text("Add files or context") }) {
            IconButton(onClick = { }) {
                Icon(key = AllIconsKeys.General.Add, contentDescription = "Add")
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
            Text(
                if (isExpanded) "Hide current context" else "Show current context"
            )
        }) {
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    key = if (isExpanded) AllIconsKeys.Run.ShowIgnored else AllIconsKeys.General.Show,
                    contentDescription = if (isExpanded) "Hide Current Context" else "Show Current Context"
                )
            }
        }

        Tooltip(tooltip = { Text("Clear message") }) {
            IconButton(onClick = onClearText, enabled = isTextNotEmpty) {
                Icon(key = AllIconsKeys.General.Delete, contentDescription = "Delete message")
            }
        }

        Tooltip(tooltip = { Text(if (isSending) "Stop" else "Send") }) {
            IconButton(onClick = onSend, enabled = isTextNotEmpty || isSending) {
                Icon(
                    key = if (isSending) AllIconsKeys.Run.Stop else GradumIcons.Send,
                    contentDescription = "Stop Response"
                )
            }
        }
    }
}

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
        contentDescription = "Select permissions",
        onClick = onToggle,
        color = JewelTheme.globalColors.text.normal
    )

    if (isMenuVisible) {
        PopupMenu(
            onDismissRequest = { onDismiss(); true },
            horizontalAlignment = Alignment.Start
        ) {
            selectableItem(selected = false, onClick = { onSelect("Read-only Permissions") }) {
                Text("Read-only Permissions")
            }
            selectableItem(selected = false, onClick = { onSelect("Full Permissions") }) {
                Text("Full Permissions")
            }
        }
    }
}

@Composable
private fun ModelSelectorBar() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SelectorButton(
            text = "Minimax-m2.5:cloud",
            contentDescription = "Select model",
            onClick = { }
        )

        Spacer(modifier = Modifier.weight(1f))

        ExternalLink(
            text = "Feedback",
            onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
        )
    }
}

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
                Icon(key = AllIconsKeys.General.ChevronDown, contentDescription = contentDescription)
            }
        }
    }
}

/**
 * Custom path-based icon keys used throughout the Gradum UI.
 *
 * Icons are loaded from the plugin resource tree via [PathIconKey] and
 * referenced in Compose [Icon] calls alongside JetBrains [AllIconsKeys].
 */
object GradumIcons {
    val Send = PathIconKey("/icons/send/send.svg", GradumIcons::class.java)
}

