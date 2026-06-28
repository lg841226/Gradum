/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-06-28 17:25:03 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.state.GradumChatSession.Companion.MAX_ATTACHMENTS
import gradum.idea.chat.ui.ChatScreen
import gradum.idea.chat.ui.GradumCodeBlockRenderer
import gradum.idea.chat.ui.home.WelcomeScreen
import gradum.idea.chat.ui.rememberGradumMarkdownStyling
import gradum.idea.editor.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File
import kotlin.random.Random

class GradumToolWindowFactory : ToolWindowFactory {

    @OptIn(ExperimentalJewelApi::class)
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val session: GradumChatSession = project.getService(GradumChatSession::class.java)
            ?: error("GradumChatSession is not registered in plugin.xml")

        toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
            SwingBridgeTheme {
                val scope: CoroutineScope = rememberCoroutineScope()
                val codeHighlighter = remember(project, scope) {
                    CodeHighlighterFactory(project, scope).createHighlighter()
                }
                val markdownStyling = rememberGradumMarkdownStyling()
                val blockRenderer = remember(markdownStyling) {
                    GradumCodeBlockRenderer(markdownStyling)
                }
                ProvideMarkdownStyling(
                    markdownStyling = markdownStyling,
                    markdownBlockRenderer = blockRenderer,
                    codeHighlighter = codeHighlighter
                ) {
                    CompositionLocalProvider(LocalCodeHighlighter provides codeHighlighter) {
                        GradumUI(toolWindow = toolWindow, session = session)
                    }
                }
            }
        }

        val newChatAction = object : AnAction(
            "New Chat",
            "Start a new chat session",
            AllIcons.General.Add
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                val project: Project = event.project ?: return
                val toolWindow: ToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Gradum") ?: return
                val session: GradumChatSession = project.getService(GradumChatSession::class.java) ?: return
                session.reset()
                val content = toolWindow.contentManager.contents.firstOrNull() ?: return
                content.displayName = message("gradum.toolwindow.welcome")
            }
        }
        toolWindow.setTitleActions(listOf(newChatAction))
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun GradumUI(toolWindow: ToolWindow? = null, session: GradumChatSession) {
    val editorContext: EditorContext = toolWindow?.project?.let { EditorUtils.getEditorContext(it) }
        ?: EditorContext.EMPTY

    LaunchedEffect(session.hasSentMessage) {
        val content = toolWindow?.contentManager?.contents?.firstOrNull()
        if (content != null) {
            content.displayName =
                if (session.hasSentMessage) message("gradum.toolwindow.newchat")
                else message("gradum.toolwindow.welcome")
        }
    }

    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!session.modelsLoaded) {
            scope.launch { session.loadModels() }
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
                    val project: Project? = toolWindow?.project
                    if (project != null) {
                        val remaining: Int = MAX_ATTACHMENTS - session.attachedFiles.size
                        val imageExtensions: Set<String> = setOf(
                            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff"
                        )
                        val descriptor = FileChooserDescriptorFactory.multiFiles().apply {
                            title = "Select Images"
                            withFileFilter { file -> file.extension?.lowercase() in imageExtensions }
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
                                            iconKey = getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown
                                        )
                                    )
                                }
                        }
                    }
                }
            }
            val onCopyAsContext: (String) -> Unit = { text ->
                if (session.attachedFiles.size < MAX_ATTACHMENTS) {
                    val preview: String = if (text.length > 30) text.take(30) + "..." else text
                    session.attachedFiles.add(AttachedText(content = text, preview = preview))
                }
            }
            val onPasteAsContext: (String) -> Unit = onCopyAsContext
            val onRemovePending: (PendingMessage) -> Unit = { pending -> session.pendingMessages.remove(pending) }
        }
    }

    val onDeleteMessage: (Int) -> Unit = { userMessageIndex ->
        val assistantResponseIndex: Int = userMessageIndex + 1
        if (assistantResponseIndex < session.messages.size &&
            !session.messages[assistantResponseIndex].isUserMessage
        ) {
            session.messages.removeAt(assistantResponseIndex)
        }
        session.messages.removeAt(userMessageIndex)
        if (session.messages.isEmpty()) {
            session.hasSentMessage = false
            session.isSending = false
            session.pendingMessages.clear()
            session.attachedFiles.clear()
            session.textState.edit { delete(0, length) }
        }
    }

    val onRetryMessage: (Int) -> Unit = { assistantMessageIndex: Int ->
        val userMessageIndex: Int = assistantMessageIndex - 1
        if (userMessageIndex >= 0 && userMessageIndex < session.messages.size &&
            session.messages[userMessageIndex].isUserMessage
        ) {
            val userMessage: ChatMessage = session.messages[userMessageIndex]
            session.messages.removeAt(assistantMessageIndex)
            session.messages.removeAt(userMessageIndex)
            session.messages.add(ChatMessage(role = "user", content = userMessage.content, attachments = userMessage.attachments))
            session.messages.add(ChatMessage(role = "assistant", content = ""))
            session.isSending = true
            session.isWaitingForResponse = true
            scope.launch { session.sendMessage(userMessage.content) }
        }
    }

    val onSend: () -> Unit = {
        val text: String = session.textState.text.toString()
        val hasModel = session.selectedModel != null || session.isAutoSelected
        if (text.isNotBlank() && hasModel) {
            if (session.isSending) {
                if (!session.isPendingQueueFull) {
                    session.pendingMessages.add(PendingMessage(content = text, attachments = session.attachedFiles.toList()))
                }
            } else {
                val attachments = session.attachedFiles.toList()
                session.messages.add(ChatMessage(role = "user", content = text, attachments = attachments))
                session.messages.add(ChatMessage(role = "assistant", content = ""))
                session.hasSentMessage = true
                session.isSending = true
                session.isWaitingForResponse = true
                session.currentJob = scope.launch { session.sendMessage(text) }
            }
            session.textState.edit { delete(0, length) }
            session.attachedFiles.clear()
        }
    }

    val onStop: () -> Unit = {
        scope.launch { session.stopSession() }
    }

    val onRefreshModels: () -> Unit = { scope.launch { session.loadModels() } }

    val onOpenInEditor: (String) -> Unit = { target ->
        val project: Project? = toolWindow?.project
        if (project != null && target.isNotBlank()) {
            try {
                val ioFile = File(target)
                if (ioFile.isFile) {
                    val virtualFile: VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                    if (virtualFile != null) {
                        FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    }
                } else {
                    val tempFile = File.createTempFile("gradum_cmd_", ".sh")
                    tempFile.writeText(target)
                    tempFile.deleteOnExit()
                    val virtualFile: VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
                    if (virtualFile != null) {
                        FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    }
                }
            } catch (exception: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(GradumToolWindowFactory::class.java)
                    .warn("Failed to open target in editor: $target", exception)
            }
        }
    }

    val inputState = ChatInputState(
        isFocused = session.isFocused,
        isSending = session.isSending,
        isPendingQueueFull = session.isPendingQueueFull,
        isMenuVisible = session.isMenuVisible,
        isExpanded = session.isExpanded,
        showAddMenu = session.showAddMenu,
        isAttachmentLimitReached = session.isAttachmentLimitReached,
        selectedPermission = session.selectedPermission,
        editorContext = editorContext,
        attachedFiles = session.attachedFiles,
        pendingMessages = session.pendingMessages,
        models = session.models.toList(),
        selectedModel = session.selectedModel,
        pinnedModels = session.pinnedModels.toList(),
        isAutoSelected = session.isAutoSelected,
        modelsLoaded = session.modelsLoaded
    )

    val inputActions = ChatInputActions(
        onFocusChange = callbacks.onFocusChange,
        onToggleMenu = callbacks.onToggleMenu,
        onSelectPermission = callbacks.onSelectPermission,
        onDismissMenu = callbacks.onDismissMenu,
        onToggleExpanded = callbacks.onToggleExpanded,
        onClearText = callbacks.onClearText,
        onSend = onSend,
        onStop = onStop,
        onToggleAddMenu = callbacks.onToggleAddMenu,
        onDismissAddMenu = callbacks.onDismissAddMenu,
        onSelectFile = callbacks.onSelectFile,
        onRemoveFile = callbacks.onRemoveFile,
        onUploadImage = callbacks.onUploadImage,
        onRemovePending = callbacks.onRemovePending,
        onPasteAsContext = callbacks.onPasteAsContext,
        onSelectModel = { model ->
            session.selectedModel = model
            session.isAutoSelected = false
        },
        onTogglePin = { model ->
            if (session.pinnedModels.any { it.name == model.name && it.serverName == model.serverName }) {
                session.pinnedModels.removeAll { it.name == model.name && it.serverName == model.serverName }
            } else {
                session.pinnedModels.add(model)
            }
        },
        onSelectAuto = {
            session.selectedModel = null
            session.isAutoSelected = true
        }
    )

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (session.hasSentMessage) {
            ChatScreen(
                messages = session.messages,
                isLoading = session.isSending,
                isWaitingForResponse = session.isWaitingForResponse,
                textState = session.textState,
                inputState = inputState,
                inputActions = inputActions,
                onDeleteMessage = onDeleteMessage,
                onRetryMessage = onRetryMessage,
                onCopyAsContext = callbacks.onCopyAsContext,
                onRefreshModels = onRefreshModels,
                onOpenInEditor = onOpenInEditor,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            WelcomeScreen(
                inputState = inputState,
                inputActions = inputActions,
                textState = session.textState,
                suggestionVariants = session.suggestionVariants,
                onRefreshSuggestions = { session.suggestionVariants = List(4) { Random.nextInt(5) } },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
