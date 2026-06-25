/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-06-26 00:00:00 Changed by gwy
 */

package gradum.idea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.delete
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
import gradum.idea.GradumChatSession.Companion.MAX_ATTACHMENTS
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private val roundedCornerShape = RoundedCornerShape(6.dp)

/**
 * Hosts the Gradum chat tool window.
 *
 * All session state is held by the project-scoped [GradumChatSession] so
 * it survives the platform's "dispose on collapse" lifecycle. The tab is
 * reused rather than recreated: the welcome page is a UI branch inside
 * [GradumUI] gated on `session.hasSentMessage`, so the same Compose tree
 * flips between welcome and chat as messages are sent.
 */
class GradumToolWindowFactory : ToolWindowFactory {

    @OptIn(ExperimentalJewelApi::class)
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val session = project.getService(GradumChatSession::class.java)
            ?: error("GradumChatSession is not registered in plugin.xml")

        toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
            SwingBridgeTheme {
                GradumUI(toolWindow = toolWindow, session = session)
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
                val session = project.getService(GradumChatSession::class.java) ?: return
                session.reset()
                val content = tw.contentManager.contents.firstOrNull() ?: return
                content.displayName = message("gradum.toolwindow.welcome")
            }
        }
        toolWindow.setTitleActions(listOf(newChatAction))
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun GradumUI(
    toolWindow: ToolWindow? = null,
    session: GradumChatSession
) {
    val editorContext = toolWindow?.project?.let { EditorUtils.getEditorContext(it) }
        ?: EditorContext.EMPTY

    LaunchedEffect(session.hasSentMessage) {
        val content = toolWindow?.contentManager?.contents?.firstOrNull()
        if (content != null) {
            content.displayName =
                if (session.hasSentMessage) message("gradum.toolwindow.newchat")
                else message("gradum.toolwindow.welcome")
        }
    }

    val callbacks = remember {
        object {
            val onFocusChange: (Boolean) -> Unit = { session.isFocused = it }
            val onToggleMenu: () -> Unit = { session.isMenuVisible = !session.isMenuVisible }
            val onSelectPermission: (String) -> Unit = {
                session.selectedPermission = it
                session.isMenuVisible = false
            }
            val onDismissMenu: () -> Unit = { session.isMenuVisible = false }
            val onToggleExpanded: () -> Unit = { session.isExpanded = !session.isExpanded }
            val onClearText: () -> Unit = { session.textState.edit { delete(0, length) } }
            val onToggleAddMenu: () -> Unit = { session.showAddMenu = !session.showAddMenu }
            val onDismissAddMenu: () -> Unit = { session.showAddMenu = false }
            val onSelectFile: (VirtualFile) -> Unit = { file ->
                if (session.attachedFiles.size < MAX_ATTACHMENTS &&
                    session.attachedFiles.none { it is AttachedFile && it.file.path == file.path }
                ) {
                    val iconKey = if (file.isDirectory) {
                        AllIconsKeys.Actions.ProjectDirectory
                    } else {
                        getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown
                    }
                    session.attachedFiles.add(AttachedFile(file = file, iconKey = iconKey))
                }
            }
            val onRemoveFile: (AttachedContext) -> Unit = { attachedContext ->
                when (attachedContext) {
                    is AttachedFile -> session.attachedFiles.removeAll { it is AttachedFile && it.file.path == attachedContext.file.path }
                    is AttachedText -> session.attachedFiles.removeAll { it is AttachedText && it.content == attachedContext.content }
                }
            }
            val onUploadImage: () -> Unit = {
                if (session.attachedFiles.size < MAX_ATTACHMENTS) {
                    val project = toolWindow?.project
                    if (project != null) {
                        val remaining: Int = MAX_ATTACHMENTS - session.attachedFiles.size
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
                            files.filter { it.extension?.lowercase() in imageExtensions }
                                .filter { imageFile ->
                                    session.attachedFiles.none { existing -> existing is AttachedFile && existing.file.path == imageFile.path }
                                }
                                .take(remaining)
                                .forEach { file ->
                                    session.attachedFiles.add(
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
            val onCopyAsContext: (String) -> Unit = { text ->
                if (session.attachedFiles.size < MAX_ATTACHMENTS) {
                    val preview = if (text.length > 30) text.take(30) + "..." else text
                    session.attachedFiles.add(AttachedText(content = text, preview = preview))
                }
            }
            val onPasteAsContext: (String) -> Unit = onCopyAsContext
        }
    }

    val onDeleteMessage: (Int) -> Unit = { userMessageIndex ->
        if (session.isSending) {
            session.isSending = false
        }
        val assistantResponseIndex = userMessageIndex + 1
        if (assistantResponseIndex < session.messages.size &&
            session.messages[assistantResponseIndex].role == "assistant"
        ) {
            session.messages.removeAt(assistantResponseIndex)
        }
        session.messages.removeAt(userMessageIndex)
        if (session.messages.isEmpty()) {
            session.hasSentMessage = false
        }
    }

    val onSend: () -> Unit = {
        val text = session.textState.text.toString()
        if (text.isNotBlank()) {
            session.messages.add(
                ChatMessage(role = "user", content = text, attachments = session.attachedFiles.toList())
            )
            session.messages.add(ChatMessage(role = "assistant", content = ""))
            session.hasSentMessage = true
            session.isSending = true
            session.textState.edit { delete(0, length) }
            session.attachedFiles.clear()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        if (session.hasSentMessage) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ChatMessageList(
                    messages = session.messages,
                    isLoading = session.isSending,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onDeleteMessage = onDeleteMessage,
                    onCopyAsContext = callbacks.onCopyAsContext
                )
                ChatInputSection(
                    modifier = Modifier.widthIn(max = 600.dp),
                    isFocused = session.isFocused,
                    roundedCornerShape = roundedCornerShape,
                    onFocusChange = callbacks.onFocusChange,
                    textState = session.textState,
                    selectedPermission = session.selectedPermission,
                    isSending = session.isSending,
                    isMenuVisible = session.isMenuVisible,
                    isExpanded = session.isExpanded,
                    showAddMenu = session.showAddMenu,
                    isAttachmentLimitReached = session.isAttachmentLimitReached,
                    editorContext = editorContext,
                    attachedFiles = session.attachedFiles,
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
                    onUploadImage = callbacks.onUploadImage,
                    onPasteAsContext = callbacks.onPasteAsContext
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
                    modifier = Modifier.widthIn(max = 600.dp),
                    isFocused = session.isFocused,
                    roundedCornerShape = roundedCornerShape,
                    onFocusChange = callbacks.onFocusChange,
                    textState = session.textState,
                    selectedPermission = session.selectedPermission,
                    isSending = session.isSending,
                    isMenuVisible = session.isMenuVisible,
                    isExpanded = session.isExpanded,
                    showAddMenu = session.showAddMenu,
                    isAttachmentLimitReached = session.isAttachmentLimitReached,
                    editorContext = editorContext,
                    attachedFiles = session.attachedFiles,
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
                    onUploadImage = callbacks.onUploadImage,
                    onPasteAsContext = callbacks.onPasteAsContext
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
