/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumToolWindowFactory.kt  2026-07-07 16:07:35 Changed by gwy
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
import gradum.idea.chat.ui.GradumMarkdownProcessor
import gradum.idea.chat.ui.common.DiffViewer
import gradum.idea.chat.ui.home.WelcomeScreen
import gradum.idea.chat.ui.rememberGradumMarkdownStyling
import gradum.idea.editor.*
import kotlinx.coroutines.*
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalJewelApi::class)
class GradumToolWindowFactory : ToolWindowFactory {

    @Suppress("UnstableApiUsage")
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val session: GradumChatSession = project.getService(GradumChatSession::class.java)
            ?: error("GradumChatSession is not registered in plugin.xml")
        session.project = project

        toolWindow.addComposeTab(message("gradum.toolwindow.welcome")) {
            SwingBridgeTheme {
                val scope: CoroutineScope = rememberCoroutineScope()
                session.scope = scope
                val codeHighlighter = remember(project, scope) {
                    CodeHighlighterFactory(project, scope).createHighlighter()
                }
                val markdownStyling = rememberGradumMarkdownStyling()
                val blockRenderer = remember(markdownStyling) {
                    GradumCodeBlockRenderer(
                        styling = markdownStyling,
                        onInsertAsFile = { code, language ->
                            EditorUtils.openCodeAsNewFile(project, code, language)
                        },
                    )
                }
                ProvideMarkdownStyling(
                    markdownStyling = markdownStyling,
                    markdownProcessor = GradumMarkdownProcessor,
                    markdownBlockRenderer = blockRenderer,
                    codeHighlighter = codeHighlighter,
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
                val toolWindow: ToolWindow =
                    ToolWindowManager.getInstance(project).getToolWindow("Gradum") ?: return
                val session: GradumChatSession = project.getService(GradumChatSession::class.java) ?: return
                val tabContent = toolWindow.contentManager.contents.firstOrNull() ?: return

                session.reset()

                tabContent.displayName = message("gradum.toolwindow.welcome")
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

    LaunchedEffect(session.hasSentMessage, session.isSending) {
        val tabContent = toolWindow?.contentManager?.contents?.firstOrNull()
        if (tabContent != null) {
            val tabName: String = if (session.hasSentMessage) message("gradum.toolwindow.newchat")
            else message("gradum.toolwindow.welcome")
            tabContent.displayName = tabName
        }
    }

    LaunchedEffect(session.isSending) {
        val tabContent = toolWindow?.contentManager?.contents?.firstOrNull() ?: return@LaunchedEffect
        val tabName: String = if (session.hasSentMessage) {
            message("gradum.toolwindow.newchat")
        } else message("gradum.toolwindow.welcome")

        if (session.isSending) {
            val spinnerFrames: CharArray =
                charArrayOf('\u280B', '\u2819', '\u2839', '\u2838', '\u283C', '\u2834', '\u2826', '\u2827')
            var frameIndex = 0
            while (session.isSending) {
                tabContent.displayName = "$tabName  ${spinnerFrames[frameIndex]}"
                frameIndex = (frameIndex + 1) % spinnerFrames.size; delay(100.milliseconds)
            }
        }
        tabContent.displayName = tabName
    }

    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!session.modelsLoaded)
            coroutineScope.launch { session.loadModels() }
        session.startModelPolling(coroutineScope)
    }
    DisposableEffect(Unit) {
        onDispose { session.stopModelPolling() }
    }

    val eventCallbacks = remember {
        object {
            val onFocusChange: (Boolean) -> Unit = { session.isFocused = it }
            val onToggleExpanded: () -> Unit = { session.isExpanded = !session.isExpanded }

            val onToggleMenu: () -> Unit = { session.isMenuVisible = !session.isMenuVisible }
            val onDismissMenu: () -> Unit = { session.isMenuVisible = false }
            val onSelectPermission: (String) -> Unit = { permission ->
                /**
                 * `permission` is the server wire format (e.g. "read_only").
                 * Storing it directly keeps selectedPermission and toolMode
                 * in lockstep — see [GradumChatSession.toolMode].
                 */
                session.selectedPermission = permission
                session.isMenuVisible = false
            }
            val onToggleAddMenu: () -> Unit = { session.showAddMenu = !session.showAddMenu }
            val onDismissAddMenu: () -> Unit = { session.showAddMenu = false }

            val onSelectFile: (VirtualFile) -> Unit = { file ->
                if (session.attachedFiles.size < MAX_ATTACHMENTS &&
                    session.attachedFiles.none { it is AttachedFile && it.file.path == file.path }
                ) {
                    val iconKey = if (file.isDirectory)
                        AllIconsKeys.Actions.ProjectDirectory
                    else
                        getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown
                    session.attachedFiles.add(AttachedFile(file = file, iconKey = iconKey))
                }
            }

            val onRemoveFile: (AttachedContext) -> Unit = { attachedContext ->
                when (attachedContext) {
                    is AttachedFile -> session.attachedFiles.removeAll {
                        it is AttachedFile && it.file.path == attachedContext.file.path
                    }

                    is AttachedText -> session.attachedFiles.removeAll {
                        it is AttachedText && it.content == attachedContext.content
                    }

                    is AttachedImage -> session.attachedFiles.removeAll {
                        it is AttachedImage && it.id == attachedContext.id
                    }
                }
            }

            val onUploadImage: () -> Unit = Unit@{
                val remainingSlots: Int = MAX_ATTACHMENTS - session.attachedFiles.size
                if (remainingSlots <= 0) return@Unit
                val currentProject = toolWindow?.project ?: return@Unit

                val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff")

                val fileDescriptor = FileChooserDescriptorFactory.multiFiles().apply {
                    withFileFilter { file -> file.extension?.lowercase() in imageExtensions }
                    title = "Select Images"
                }
                val baseDir = currentProject.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                FileChooser.chooseFiles(fileDescriptor, currentProject, baseDir) { files ->
                    files.asSequence()
                        .filter { it.extension?.lowercase() in imageExtensions }
                        .filter { imageFile ->
                            session.attachedFiles.none { existing ->
                                when (existing) {
                                    is AttachedFile -> existing.file.path == imageFile.path
                                    is AttachedImage -> existing.originalName == imageFile.name &&
                                        imageFile.length == existing.originalSizeBytes

                                    is AttachedText -> false
                                }
                            }
                        }
                        .take(remainingSlots)
                        .forEach { file ->
                            if (file.length > MAX_IMAGE_BYTES) return@forEach
                            val attachment: AttachedImage = encodeImageToAttachment(file) ?: return@forEach
                            session.attachedFiles.add(attachment)
                        }
                }
            }

            val onCopyAsContext: (String) -> Unit = { text ->
                if (session.attachedFiles.size < MAX_ATTACHMENTS) {
                    val previewText = if (text.length > 30) text.take(30) + "..." else text
                    session.attachedFiles.add(AttachedText(content = text, preview = previewText))
                }
            }
            val onPasteAsContext: (String) -> Unit = onCopyAsContext

            val onClearText: () -> Unit = { session.textState.edit { delete(0, length) } }
            val onRemovePending: (PendingMessage) -> Unit = { pending -> session.pendingMessages.remove(pending) }
        }
    }

    val onDeleteMessage: (Int) -> Unit = { userMessageIndex ->
        val assistantResponseIndex: Int = userMessageIndex + 1
        if (assistantResponseIndex < session.messages.size &&
            !session.messages[assistantResponseIndex].isUserMessage
        ) session.messages.removeAt(assistantResponseIndex)

        session.messages.removeAt(userMessageIndex)
        if (session.messages.isEmpty()) {
            session.hasSentMessage = false
            session.isSending = false
            session.sendingPhase = ""
            session.pendingMessages.clear()
            session.attachedFiles.clear()
            session.textState.edit { delete(0, length) }
        }
    }

    val onRetryMessage: (Int) -> Unit = { assistantMessageIndex: Int ->
        val userMessageIndex = (assistantMessageIndex - 1 downTo 0)
            .firstOrNull { session.messages[it].isUserMessage }

        if (userMessageIndex != null) {
            val userMessage = session.messages[userMessageIndex]


            val activeProject = toolWindow?.project
            val editorContext = activeProject?.let { EditorUtils.getEditorContext(it) }
            val focusedPath = editorContext?.currentFile?.path ?: ""
            val openFiles = editorContext?.allOpenFiles ?: emptyList()
            val (resolvedText, anyReplaced) = GradumChatSession.resolveInlineTags(
                userMessage.content,
                focusedPath, openFiles
            )

            val displayName = session.selectedModel?.name ?: "Auto"
            val providerName = session.selectedModel?.provider ?: ""
            val serverLabel = session.selectedModel?.serverName ?: ""

            val messagesToRemove = assistantMessageIndex - userMessageIndex + 1
            repeat(messagesToRemove) {
                session.messages.removeAt(userMessageIndex)
            }

            session.messages.add(
                userMessageIndex,
                ChatMessage(
                    role = "user",
                    content = userMessage.content,
                    attachments = userMessage.attachments
                )
            )
            session.messages.add(
                userMessageIndex + 1,
                ChatMessage(
                    role = "assistant",
                    content = "",
                    modelName = displayName,
                    provider = providerName,
                    serverName = serverLabel
                )
            )

            session.isSending = true
            session.isWaitingForResponse = true
            coroutineScope.launch {
                val contextPath = if (session.isExpanded && !anyReplaced) {
                    focusedPath
                } else ""
                session.sendMessage(resolvedText, userMessage.attachments, contextPath)
            }
        }
    }

    val onSend: () -> Unit = {
        val rawText: String = session.textState.text.toString()
        val hasModel = session.selectedModel != null || session.isAutoSelected
        if (rawText.isNotBlank() && hasModel) {
            val toolProject = toolWindow?.project
            val editorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
            val focusedPath = editorContext?.currentFile?.path ?: ""
            val openFiles = editorContext?.allOpenFiles ?: emptyList()
            val (resolvedText, anyReplaced) = GradumChatSession.resolveInlineTags(rawText, focusedPath, openFiles)
            if (session.isSending) {
                if (!session.isPendingQueueFull) {
                    session.pendingMessages.add(
                        PendingMessage(
                            content = rawText,
                            attachments = session.attachedFiles.toList()
                        )
                    )
                }
            } else {
                val attachedList = session.attachedFiles.toList()
                val displayName = session.selectedModel?.name ?: "Auto"
                val providerName = session.selectedModel?.provider ?: ""
                val serverLabel = session.selectedModel?.serverName ?: ""
                session.messages.add(ChatMessage(role = "user", content = rawText, attachments = attachedList))
                session.messages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "",
                        modelName = displayName,
                        provider = providerName,
                        serverName = serverLabel
                    )
                )
                session.hasSentMessage = true
                session.isSending = true
                session.isWaitingForResponse = true
                session.currentJob = coroutineScope.launch {
                    val contextPath = if (session.isExpanded && !anyReplaced) {
                        focusedPath
                    } else ""
                    session.sendMessage(resolvedText, attachedList, contextPath)
                }
            }
            session.textState.edit { delete(0, length) }
            session.attachedFiles.clear()
        }
    }

    val onStop: () -> Unit = { coroutineScope.launch { session.stopSession() } }

    val onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { filePath, startLine, _ ->
        val project: Project? = toolWindow?.project
        if (project != null && filePath.isNotBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val absolutePath = if (File(filePath).isAbsolute) {
                        filePath
                    } else project.basePath?.let { "$it/$filePath" } ?: filePath

                    val virtualFile: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                    if (virtualFile != null && virtualFile.exists()) {
                        withContext(Dispatchers.Main) {
                            val fileEditor = FileEditorManager.getInstance(project).openFile(virtualFile, true)
                            // startLine is 1-based; 0 means "no specific line" so we skip the caret jump.
                            if (startLine > 0 && fileEditor is com.intellij.openapi.fileEditor.TextEditor) {
                                val editor = fileEditor.editor
                                val offset = editor.document.getLineStartOffset((startLine - 1).coerceAtLeast(0))
                                editor.caretModel.moveToOffset(offset)
                                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                            }
                        }
                    } else {
                        val tempFile = File.createTempFile("gradum_cmd_", ".sh")
                        tempFile.writeText(filePath)
                        tempFile.deleteOnExit()
                        val tempVirtual: VirtualFile? =
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
                        if (tempVirtual != null) {
                            withContext(Dispatchers.Main) {
                                FileEditorManager.getInstance(project).openFile(tempVirtual, true)
                            }
                        }
                    }
                } catch (exception: Exception) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(GradumToolWindowFactory::class.java)
                        .warn("Failed to open target in editor: $filePath", exception)
                }
            }
        }
    }

    val onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) -> Unit =
        { filePath, originalContent, modifiedContent ->
            /**
             * DiffViewer.showFileDiff runs on the platform EDT internally,
             * so we can call it directly. The platform accepts null Project
             * for floating diff windows.
             */
            DiffViewer.showFileDiff(
                project = toolWindow?.project,
                path = filePath,
                originalContent = originalContent,
                modifiedContent = modifiedContent,
            )
        }

    /**
     * Opens a sent attachment (image / file / text) in the IDE editor. The
     * file comes from the user's original `VirtualFile` — no path parsing
     * needed because the bubble already resolved it at upload time.
     */
    val onAttachmentClick: (VirtualFile) -> Unit = { file ->
        val project: Project? = toolWindow?.project
        if (project != null && file.isValid) {
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    val inputState = ChatInputState(
        models = session.models.toList(),
        pinnedModels = session.pinnedModels.toList(),
        selectedModel = session.selectedModel,
        isAutoSelected = session.isAutoSelected,
        modelsLoaded = session.modelsLoaded,
        editorContext = editorContext,
        attachedFiles = session.attachedFiles,
        pendingMessages = session.pendingMessages,
        isAttachmentLimitReached = session.isAttachmentLimitReached,
        selectedPermission = session.selectedPermission,
        isFocused = session.isFocused,
        isSending = session.isSending,
        isPendingQueueFull = session.isPendingQueueFull,
        isExpanded = session.isExpanded,
        isMenuVisible = session.isMenuVisible,
        showAddMenu = session.showAddMenu
    )

    val inputActions = ChatInputActions(
        onSend = onSend,
        onStop = onStop,
        onUploadImage = eventCallbacks.onUploadImage,
        onClearText = eventCallbacks.onClearText,
        onToggleMenu = eventCallbacks.onToggleMenu,
        onDismissMenu = eventCallbacks.onDismissMenu,
        onToggleAddMenu = eventCallbacks.onToggleAddMenu,
        onDismissAddMenu = eventCallbacks.onDismissAddMenu,
        onToggleExpanded = eventCallbacks.onToggleExpanded,
        onFocusChange = eventCallbacks.onFocusChange,
        onSelectPermission = eventCallbacks.onSelectPermission,
        onRemoveFile = eventCallbacks.onRemoveFile,
        onSelectModel = { model ->
            session.selectedModel = model
            session.isAutoSelected = false
        },
        onTogglePin = { model ->
            if (session.pinnedModels.any { it.name == model.name && it.serverName == model.serverName })
                session.pinnedModels.removeAll { it.name == model.name && it.serverName == model.serverName }
            else
                session.pinnedModels.add(model)
        },
        onSelectAuto = {
            /**
             * Honor the server's recommendation so the Auto button picks
             * a model instead of just flipping a flag. Setting selectedModel
             * = null caused a latent bug: the plugin-side "isAutoSelected"
             * branch in buildModelConfig forced provider = "ollama".
             */
            session.selectedModel = session.recommendedModel ?: session.models.firstOrNull()
            session.isAutoSelected = true
        },
        onSelectFile = eventCallbacks.onSelectFile,
        onRemovePending = eventCallbacks.onRemovePending,
        onPasteAsContext = eventCallbacks.onPasteAsContext
    )

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (session.hasSentMessage) {
            ChatScreen(
                messages = session.messages,
                isLoading = session.isSending,
                isWaitingForResponse = session.isWaitingForResponse,
                sendingPhase = session.sendingPhase,
                textState = session.textState,
                inputState = inputState,
                inputActions = inputActions,
                onDeleteMessage = onDeleteMessage,
                onRetryMessage = onRetryMessage,
                onCopyAsContext = eventCallbacks.onCopyAsContext,
                onOpenInEditor = onOpenInEditor,
                onViewDiff = onViewDiff,
                onAttachmentClick = onAttachmentClick,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            WelcomeScreen(
                inputState = inputState,
                textState = session.textState,
                suggestionVariants = session.suggestionVariants,
                modifier = Modifier.fillMaxSize(),
                inputActions = inputActions
            ) { session.suggestionVariants = List(4) { Random.nextInt(5) } }
        }
    }
}


/** Maximum size of the *original* (pre-encoding) image file in bytes (5 MB). */
private const val MAX_IMAGE_BYTES: Long = 5L * 1024L * 1024L
