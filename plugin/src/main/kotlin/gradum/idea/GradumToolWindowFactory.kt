/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-06-25 13:06:00 Changed by gwy
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
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import gradum.idea.GradumBundle.message
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private val roundedCornerShape = RoundedCornerShape(6.dp)

class GradumToolWindowFactory : ToolWindowFactory {

    @OptIn(ExperimentalJewelApi::class)
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
            SwingBridgeTheme {
                GradumUI(toolWindow = toolWindow)
            }
        }

        val newChatAction = object : AnAction(
            "New Chat",
            "Start a new chat session",
            AllIcons.General.Add
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: return
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Gradum") ?: return
                val contentManager = tw.contentManager
                val content = contentManager.getContent(0) ?: return
                contentManager.removeContent(content, true)
                tw.addComposeTab(GradumBundle.message("gradum.toolwindow.welcome")) {
                    SwingBridgeTheme {
                        GradumUI(toolWindow = tw)
                    }
                }
            }
        }
        toolWindow.setTitleActions(listOf(newChatAction))
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
    val attachedFiles = remember { mutableStateListOf<AttachedFile>() }
    val textState = rememberTextFieldState("")
    val editorContext = toolWindow?.project?.let { EditorUtils.getEditorContext(it) }
        ?: EditorContext.EMPTY

    LaunchedEffect(hasSentMessage) {
        val content = toolWindow?.contentManager?.contents?.firstOrNull()
        if (content != null) {
            content.displayName =
                if (hasSentMessage) message("gradum.toolwindow.newchat") else message("gradum.toolwindow.welcome")
        }
    }

    val callbacks = remember {
        object {
            val onFocusChange: (Boolean) -> Unit = { isFocused = it }
            val onToggleMenu: () -> Unit = { isMenuVisible = !isMenuVisible }
            val onSelectPermission: (String) -> Unit = { selectedPermission = it; isMenuVisible = false }
            val onDismissMenu: () -> Unit = { isMenuVisible = false }
            val onToggleExpanded: () -> Unit = { isExpanded = !isExpanded }
            val onClearText: () -> Unit = { textState.edit { delete(0, length) } }
            val onToggleAddMenu: () -> Unit = { showAddMenu = !showAddMenu }
            val onDismissAddMenu: () -> Unit = { showAddMenu = false }
            val onSelectFile: (VirtualFile) -> Unit = { file ->
                if (attachedFiles.none { it.file.path == file.path }) {
                    val iconKey = if (file.isDirectory) {
                        AllIconsKeys.Actions.ProjectDirectory
                    } else {
                        getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown
                    }
                    attachedFiles.add(AttachedFile(file = file, iconKey = iconKey))
                }
            }
            val onRemoveFile: (AttachedFile) -> Unit = { attachedFile ->
                attachedFiles.removeAll { it.file.path == attachedFile.file.path }
            }
            val onUploadImage: () -> Unit = {
                val project = toolWindow?.project
                if (project != null) {
                    val imageExtensions = setOf(
                        "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff"
                    )
                    val descriptor = FileChooserDescriptorFactory.multiFiles().apply {
                        title = "Select Images"
                        withFileFilter { file ->
                            file.extension?.lowercase() in imageExtensions
                        }
                    }
                    FileChooser.chooseFiles(descriptor, project, project.baseDir) { files ->
                        files.filter { it.extension?.lowercase() in imageExtensions }.forEach { file ->
                            if (attachedFiles.none { it.file.path == file.path }) {
                                attachedFiles.add(
                                    AttachedFile(
                                        file = file,
                                        iconKey = getLanguageIconKey(file.extension)
                                            ?: AllIconsKeys.FileTypes.Unknown
                                    )
                                )
                            }
                        }
                    }
                }
            }
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
                    onFocusChange = callbacks.onFocusChange,
                    textState = textState,
                    selectedPermission = selectedPermission,
                    isSending = isSending,
                    isMenuVisible = isMenuVisible,
                    isExpanded = isExpanded,
                    showAddMenu = showAddMenu,
                    editorContext = editorContext,
                    attachedFiles = attachedFiles,
                    onToggleMenu = callbacks.onToggleMenu,
                    onSelectPermission = callbacks.onSelectPermission,
                    onDismissMenu = callbacks.onDismissMenu,
                    onToggleExpanded = callbacks.onToggleExpanded,
                    onClearText = callbacks.onClearText,
                    onSend = onSend,
                    onToggleAddMenu = callbacks.onToggleAddMenu,
                    onDismissAddMenu = callbacks.onDismissAddMenu,
                    onSelectFile = callbacks.onSelectFile,
                    onRemoveFile = callbacks.onRemoveFile,
                    onUploadImage = callbacks.onUploadImage
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
                    onFocusChange = callbacks.onFocusChange,
                    textState = textState,
                    selectedPermission = selectedPermission,
                    isSending = isSending,
                    isMenuVisible = isMenuVisible,
                    isExpanded = isExpanded,
                    showAddMenu = showAddMenu,
                    editorContext = editorContext,
                    attachedFiles = attachedFiles,
                    onToggleMenu = callbacks.onToggleMenu,
                    onSelectPermission = callbacks.onSelectPermission,
                    onDismissMenu = callbacks.onDismissMenu,
                    onToggleExpanded = callbacks.onToggleExpanded,
                    onClearText = callbacks.onClearText,
                    onSend = onSend,
                    onToggleAddMenu = callbacks.onToggleAddMenu,
                    onDismissAddMenu = callbacks.onDismissAddMenu,
                    onSelectFile = callbacks.onSelectFile,
                    onRemoveFile = callbacks.onRemoveFile,
                    onUploadImage = callbacks.onUploadImage
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
