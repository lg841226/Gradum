/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-06-24 22:36:50 Changed by gwy
 */

package gradum.idea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import gradum.idea.GradumBundle.message
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography


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

@OptIn(ExperimentalJewelApi::class)
@Composable
fun GradumUI(toolWindow: ToolWindow? = null) {
    var isFocused by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isMenuVisible by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var selectedPermission by remember { mutableStateOf(message("gradum.readonly")) }
    var hasSentMessage by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val textState = rememberTextFieldState("")
    val roundedCornerShape = RoundedCornerShape(6.dp)
    val editorContext = toolWindow?.project?.let { EditorUtils.getEditorContext(it) }
        ?: EditorContext(
            currentFile = null,
            allOpenFiles = emptyList(),
            currentLanguage = null
        )

    LaunchedEffect(hasSentMessage) {
        val content = toolWindow?.contentManager?.contents?.firstOrNull()
        if (content != null) {
            content.displayName =
                if (hasSentMessage) message("gradum.toolwindow.newchat") else message("gradum.toolwindow.welcome")
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
                    showAddMenu = showAddMenu,
                    editorContext = editorContext,
                    onToggleMenu = { isMenuVisible = !isMenuVisible },
                    onSelectPermission = { selectedPermission = it; isMenuVisible = false },
                    onDismissMenu = { isMenuVisible = false },
                    onToggleExpanded = { isExpanded = !isExpanded },
                    onClearText = { textState.edit { delete(0, length) } },
                    onSend = onSend,
                    onToggleAddMenu = { showAddMenu = !showAddMenu },
                    onDismissAddMenu = { showAddMenu = false }
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
                    showAddMenu = showAddMenu,
                    editorContext = editorContext,
                    onToggleMenu = { isMenuVisible = !isMenuVisible },
                    onSelectPermission = { selectedPermission = it; isMenuVisible = false },
                    onDismissMenu = { isMenuVisible = false },
                    onToggleExpanded = { isExpanded = !isExpanded },
                    onClearText = { textState.edit { delete(0, length) } },
                    onSend = onSend,
                    onToggleAddMenu = { showAddMenu = !showAddMenu },
                    onDismissAddMenu = { showAddMenu = false }
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
