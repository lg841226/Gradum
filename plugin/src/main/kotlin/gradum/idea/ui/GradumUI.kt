/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumUI.kt  2026-08-24 18:59:19 Changed by gwy
 */

package gradum.idea.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import gradum.idea.GradumToolWindowFactory
import gradum.idea.PluginConfig
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.MarkdownTlsScenario
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.chat.state.ChatSessionState
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.state.GradumChatSession.Companion.MAX_ATTACHMENTS
import gradum.idea.chat.state.sendMessage
import gradum.idea.chat.ui.ChatScreen
import gradum.idea.chat.ui.home.WelcomeScreen
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.editor.*
import gradum.idea.encodeImageToAttachment
import gradum.idea.provider.ProviderSettings
import gradum.idea.settings.AppearanceSettings
import gradum.idea.settings.ProvideAppearance
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.*
import kotlinx.io.IOException
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class)
private val logger = Logger.getInstance(MarkdownProcessor::class.java)

private val MARKDOWN_EXTENSIONS = setOf(".md", ".markdown", ".mdown", ".mkd", ".mkdn", ".mdwn")
private val SCENARIO_EXTENSIONS = setOf(".tls", ".tls.xml", ".xml")
private val MAX_IMAGE_BYTES: Long = PluginConfig.MAX_IMAGE_UPLOAD_BYTES
private val MAX_MARKDOWN_LINES: Int = PluginConfig.MAX_MARKDOWN_LINES

private fun truncateToMaxLines(content: String): String {
  val lines = content.lines()
  return if (lines.size > MAX_MARKDOWN_LINES) {
    lines.take(MAX_MARKDOWN_LINES).joinToString("\n") +
      "**Truncated to $MAX_MARKDOWN_LINES lines — this Markdown file has ${lines.size} lines**"
  } else content
}

private fun sendPlaybackXml(
  scenarioXml: String,
  scenarioLabel: String,
  session: GradumChatSession,
  coroutineScope: CoroutineScope,
  preserveUserMessage: Boolean = false
) {
  val displayName: String = message("gradum.debug.model.name")
  val providerName: String = session.selectedModel?.provider ?: ""
  val serverLabel: String = session.selectedModel?.serverName ?: ""

  if (!preserveUserMessage)
    session.messages.add(ChatMessage(role = "user", content = message("gradum.debug.play", scenarioLabel)))

  session.messages.add(
    ChatMessage(content = "", role = "assistant", modelName = displayName, provider = providerName, serverName = serverLabel)
  )
  session.hasSentMessage = true
  session.isSending = true
  session.isWaitingForResponse = true
  logger.info("Sending tool-call scenario '$scenarioLabel' to server for playback")

  session.currentJob = coroutineScope.launch {
    session.sendMessage(contextPath = "", attachments = emptyList(), toolCallXml = scenarioXml, userMessage = message("gradum.debug.play", scenarioLabel))
  }
}

private fun sendPlaybackScenario(session: GradumChatSession, currentFile: VirtualFile, coroutineScope: CoroutineScope) {
  val scenarioXml: String = try {
    String(currentFile.contentsToByteArray(), StandardCharsets.UTF_8)
  } catch (ioError: IOException) {
    logger.error("IO error reading scenario file: ${currentFile.name}", ioError); return
  } catch (securityError: SecurityException) {
    logger.error("Security error accessing scenario file: ${currentFile.name}", securityError); return
  } catch (generalError: Exception) {
    logger.warn("Unexpected error reading scenario file: ${currentFile.name}", generalError); return
  }
  sendPlaybackXml(scenarioXml, currentFile.name, session, coroutineScope)
}

/**
 * Main composable for the Gradum chat interface.
 */
@Composable
fun GradumUI(toolWindow: ToolWindow? = null, session: GradumChatSession) {
  val editorContext: EditorContext = toolWindow?.project?.let {
    EditorUtils.getEditorContext(it)
  } ?: EditorContext.EMPTY
  val coroutineScope = rememberCoroutineScope()

  TabNameEffect(session, toolWindow)
  ModelPollingEffect(session, coroutineScope)

  val state: ChatSessionState = rememberChatSessionState(
    session = session,
    editorContext = editorContext,
    toolWindow = toolWindow,
    coroutineScope = coroutineScope,
  )

  Box(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center
  ) {
    ProvideAppearance {
      if (session.hasSentMessage) {
        ChatScreen(state = state, modifier = Modifier.fillMaxSize())
      } else {
        WelcomeScreen(state = state, modifier = Modifier.fillMaxSize(), welcomeLayout = AppearanceSettings.getInstance().snapshot.welcomeLayout)
      }
    }
  }
}

@Composable
private fun rememberChatSessionState(
  session: GradumChatSession,
  editorContext: EditorContext,
  toolWindow: ToolWindow?,
  coroutineScope: CoroutineScope,
): ChatSessionState {
  val onClearText = remember(session) { { session.textState.edit { delete(0, length) } } }

  val onStop: () -> Unit = remember(session, coroutineScope) { { coroutineScope.launch { session.stopSession() }; Unit } }

  val onDeleteMessage = remember(session) { { userMessageIndex: Int -> session.deleteMessage(userMessageIndex) } }

  val onUploadImage = remember(toolWindow, session) {
    {
      val remainingSlots: Int = MAX_ATTACHMENTS - session.attachedFiles.size
      if (remainingSlots > 0) {
        val currentProject = toolWindow?.project
        if (currentProject != null) {
          val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff")
          val fileDescriptor = FileChooserDescriptorFactory.multiFiles().apply {
            withFileFilter { file -> file.extension?.lowercase() in imageExtensions }
            title = "Select Images"
          }
          val baseDir = currentProject.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
          FileChooser.chooseFiles(fileDescriptor, currentProject, baseDir) { files ->
            files.asSequence()
              .filter { it.extension?.lowercase() in imageExtensions }
              .filter { imageFile -> session.attachedFiles.none { existing -> (existing is AttachedFile && existing.file.path == imageFile.path) || (existing is AttachedImage && existing.originalName == imageFile.name && imageFile.length == existing.originalSizeBytes) } }
              .take(remainingSlots)
              .forEach { file ->
                if (file.length <= MAX_IMAGE_BYTES) {
                  val attachment: AttachedImage? = encodeImageToAttachment(file)
                  if (attachment != null) session.attachedFiles.add(attachment)
                }
              }
          }
        }
      }
    }
  }

  val onCopyAsContext = remember(session) {
    { text: String ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS) {
        val previewText = if (text.length > 30) text.take(30) + "..." else text
        session.attachedFiles.add(AttachedText(content = text, preview = previewText))
      }
    }
  }

  val onPasteAsContext = remember(session) {
    { text: String ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS) {
        val previewText = if (text.length > 30) text.take(30) + "..." else text
        session.attachedFiles.add(AttachedText(content = text, preview = previewText))
      }
    }
  }

  val onSelectFile = remember(session) {
    { file: VirtualFile ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS && session.attachedFiles.none { it is AttachedFile && it.file.path == file.path }) {
        val iconKey = if (file.isDirectory) AllIconsKeys.Actions.ProjectDirectory else (getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown)
        session.attachedFiles.add(AttachedFile(file = file, iconKey = iconKey))
      }
    }
  }

  val onSelectPermission = remember(session) {
    { permission: String ->
      session.selectedPermission = permission
      session.isMenuVisible = false
      AppearanceSettings.getInstance().update { it.lastPermission = permission }
    }
  }

  val onRemoveFile: (AttachedContext) -> Unit = remember(session) {
    { attachedContext: AttachedContext ->
      when (attachedContext) {
        is AttachedFile -> {
          session.attachedFiles.removeAll { it is AttachedFile && it.file.path == attachedContext.file.path }; Unit
        }

        is AttachedText -> {
          session.attachedFiles.removeAll { it is AttachedText && it.content == attachedContext.content }; Unit
        }

        is AttachedImage -> {
          session.attachedFiles.removeAll { it is AttachedImage && it.id == attachedContext.id }; Unit
        }
      }
    }
  }

  val onRemovePending: (PendingMessage) -> Unit = remember(session) { { pending: PendingMessage -> session.pendingMessages.remove(pending); Unit } }

  val onToggleExpanded = remember(session) {
    { session.isExpanded = !session.isExpanded; AppearanceSettings.getInstance().update { it.lastContextEnabled = session.isExpanded } }
  }

  val onAttachmentClick = remember(toolWindow) {
    { file: VirtualFile ->
      val project = toolWindow?.project
      if (project != null && file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
    }
  }

  val onViewDiff = remember(toolWindow) {
    { filePath: String, originalContent: String, modifiedContent: String ->
      gradum.idea.chat.ui.common.DiffViewer.showFileDiff(path = filePath, project = toolWindow?.project, originalContent = originalContent, modifiedContent = modifiedContent)
    }
  }

  val onOpenInEditor = remember(toolWindow, coroutineScope) {
    { filePath: String, startLine: Int, _: Int ->
      val project = toolWindow?.project
      if (project != null && filePath.isNotBlank()) {
        coroutineScope.launch(Dispatchers.IO) {
          try {
            val absolutePath = if (File(filePath).isAbsolute) filePath else project.basePath?.let { "$it/$filePath" } ?: filePath
            val virtualFile: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (virtualFile != null && virtualFile.exists()) {
              withContext(Dispatchers.Main) {
                val editors = FileEditorManager.getInstance(project).openFile(virtualFile, true)
                val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
                if (startLine > 0 && textEditor != null) {
                  val editor = textEditor.editor
                  val offset = editor.document.getLineStartOffset((startLine - 1).coerceAtLeast(0))
                  editor.caretModel.moveToOffset(offset)
                  editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
              }
            } else {
              val tempFile = File.createTempFile("gradum_cmd_", ".sh")
              tempFile.writeText(filePath)
              tempFile.deleteOnExit()
              val tempVirtual: VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
              if (tempVirtual != null) {
                withContext(Dispatchers.Main) { FileEditorManager.getInstance(project).openFile(tempVirtual, true) }
              }
            }
          } catch (exception: Exception) {
            Logger.getInstance(GradumToolWindowFactory::class.java).warn("Failed to open target in editor: $filePath", exception)
          }
        }
      }
    }
  }

  val onRetryMessage = remember(toolWindow, session, coroutineScope) {
    { assistantMessageIndex: Int ->
      val userMessageIndex = (assistantMessageIndex - 1 downTo 0).firstOrNull { session.messages[it].isUserMessage } ?: return@remember
      val userMessage = session.messages[userMessageIndex]

      if (PermissionMode.isDebugMode(session.selectedPermission)) {
        val toolProject = toolWindow?.project
        val currentEditorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
        val currentFile = currentEditorContext?.currentFile
        if (currentFile != null && MARKDOWN_EXTENSIONS.any { currentFile.name.endsWith(it, ignoreCase = true) }) {
          try {
            val editors = FileEditorManager.getInstance(toolProject).getEditors(currentFile)
            val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
            val content = truncateToMaxLines(textEditor?.editor?.document?.text ?: String(currentFile.contentsToByteArray(), StandardCharsets.UTF_8))
            val messagesToRemove = assistantMessageIndex - userMessageIndex + 1
            repeat(messagesToRemove) { session.messages.removeAt(userMessageIndex) }
            session.messages.add(userMessageIndex, ChatMessage(role = "user", content = userMessage.content))
            session.hasSentMessage = true
            val compiled: String? = MarkdownTlsScenario.compile(content, currentFile.name)
            if (compiled != null) sendPlaybackXml(compiled, currentFile.name, session, coroutineScope, preserveUserMessage = true)
            else session.loadDebugMarkdown(content)
          } catch (ioError: IOException) {
            logger.error("IO error reading file: ${currentFile.path}", ioError)
          } catch (securityError: SecurityException) {
            logger.error("Security error accessing file: ${currentFile.path}", securityError)
          } catch (processingError: Exception) {
            logger.warn("Unexpected error processing markdown: ${currentFile.name}", processingError)
          }
        }
        return@remember
      }

      val activeProject = toolWindow?.project
      val currentEditorContext = activeProject?.let { EditorUtils.getEditorContext(it) }
      val focusedPath = currentEditorContext?.currentFile?.path ?: ""
      val openFiles = currentEditorContext?.allOpenFiles ?: emptyList()
      val (resolvedText, anyReplaced) = GradumChatSession.resolveInlineTags(userMessage.content, focusedPath, openFiles)

      val displayName = session.selectedModel?.name ?: ""
      val providerName = session.selectedModel?.provider ?: ""
      val serverLabel = session.selectedModel?.serverName ?: ""

      val messagesToRemove = assistantMessageIndex - userMessageIndex + 1
      repeat(messagesToRemove) { session.messages.removeAt(userMessageIndex) }

      session.messages.add(userMessageIndex, ChatMessage(role = "user", content = userMessage.content, attachments = userMessage.attachments))
      session.messages.add(userMessageIndex + 1, ChatMessage(content = "", role = "assistant", modelName = displayName, provider = providerName, serverName = serverLabel))

      session.isSending = true
      session.isWaitingForResponse = true
      session.currentJob = coroutineScope.launch {
        val contextPath = if (session.isExpanded && !anyReplaced) focusedPath else ""
        session.sendMessage(resolvedText, userMessage.attachments, contextPath)
      }
    }
  }

  val onSend = remember(toolWindow, session, coroutineScope) {
    {
      val rawText: String = session.textState.text.toString()
      val hasModel = session.selectedModel != null

      if (PermissionMode.isDebugMode(session.selectedPermission)) {
        if (rawText.isNotBlank()) {
          val toolProject = toolWindow?.project
          val currentEditorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
          val currentFile = currentEditorContext?.currentFile
          if (currentFile != null) {
            if (SCENARIO_EXTENSIONS.any { currentFile.name.endsWith(it, ignoreCase = true) }) {
              sendPlaybackScenario(session, currentFile, coroutineScope)
            } else if (MARKDOWN_EXTENSIONS.any { currentFile.name.endsWith(it, ignoreCase = true) }) {
              try {
                val editors = FileEditorManager.getInstance(toolProject).getEditors(currentFile)
                val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
                val content = truncateToMaxLines(textEditor?.editor?.document?.text ?: String(currentFile.contentsToByteArray(), StandardCharsets.UTF_8))
                val compiled: String? = MarkdownTlsScenario.compile(content, currentFile.name)
                if (compiled != null) sendPlaybackXml(compiled, currentFile.name, session, coroutineScope)
                else {
                  session.hasSentMessage = true; session.messages.add(ChatMessage(role = "user", content = rawText)); session.loadDebugMarkdown(content)
                }
              } catch (ioError: IOException) {
                logger.error("IO error reading markdown file: ${currentFile.name}", ioError)
              } catch (securityError: SecurityException) {
                logger.error("Security error accessing markdown file: ${currentFile.name}", securityError)
              } catch (generalError: Exception) {
                logger.warn("Unexpected error processing markdown file: ${currentFile.name}", generalError)
              }
            }
          }
        }
        session.textState.edit { delete(0, length) }
        return@remember
      }

      if (rawText.isNotBlank() && hasModel) {
        val toolProject = toolWindow?.project
        val currentEditorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
        val focusedPath = currentEditorContext?.currentFile?.path ?: ""
        val openFiles = currentEditorContext?.allOpenFiles ?: emptyList()
        val (resolvedText, anyReplaced) = GradumChatSession.resolveInlineTags(rawText, focusedPath, openFiles)
        if (session.isSending) {
          if (!session.isPendingQueueFull) {
            session.pendingMessages.add(PendingMessage(content = resolvedText, attachments = session.attachedFiles.toList()))
          }
        } else {
          val attachedList = session.attachedFiles.toList()
          val displayName = session.selectedModel?.name ?: ""
          val providerName = session.selectedModel?.provider ?: ""
          val serverLabel = session.selectedModel?.serverName ?: ""
          session.messages.add(ChatMessage(role = "user", content = rawText, attachments = attachedList))
          session.messages.add(ChatMessage(content = "", role = "assistant", modelName = displayName, provider = providerName, serverName = serverLabel))
          session.isSending = true
          session.hasSentMessage = true
          session.isWaitingForResponse = true
          session.currentJob = coroutineScope.launch {
            val contextPath = if (session.isExpanded && !anyReplaced) focusedPath else ""
            session.sendMessage(resolvedText, attachedList, contextPath)
          }
        }
        session.textState.edit { delete(0, length) }
        session.attachedFiles.clear()
      }
    }
  }

  val onSelectModel = remember(session) { { model: ModelInfo? -> session.selectedModel = model } }

  val onTogglePin: (ModelInfo) -> Unit = remember(session) {
    { model: ModelInfo ->
      if (session.pinnedModels.any { it.sameAs(model) }) session.pinnedModels.removeAll { it.sameAs(model) }
      else session.pinnedModels.add(model)
      Unit
    }
  }

  val onRefreshModels: () -> Unit = remember(session, coroutineScope) { { coroutineScope.launch { session.loadModels() }; Unit } }

  val onSelectThinkingLevel = remember(session) { { level: ThinkingLevel -> session.setThinkingLevel(level) } }

  // Build ChatInputActions
  val inputActions = remember(
    onSend, onStop, onUploadImage, onClearText, session,
    onCopyAsContext, onPasteAsContext, onSelectFile, onSelectPermission,
    onRemoveFile, onRemovePending, onSelectModel, onTogglePin, onRefreshModels, onToggleExpanded
  ) {
    ChatInputActions(
      onSend = onSend,
      onStop = onStop,
      onUploadImage = onUploadImage,
      onClearText = onClearText,
      onToggleMenu = { session.isMenuVisible = !session.isMenuVisible },
      onDismissMenu = { session.isMenuVisible = false },
      onToggleAddMenu = { session.showAddMenu = !session.showAddMenu },
      onDismissAddMenu = { session.showAddMenu = false },
      onToggleExpanded = onToggleExpanded,
      onFocusChange = { session.isFocused = it },
      onSelectPermission = onSelectPermission,
      onRemoveFile = onRemoveFile,
      onSelectModel = onSelectModel,
      onTogglePin = onTogglePin,
      onRefreshModels = onRefreshModels,
      onSelectFile = onSelectFile,
      onRemovePending = onRemovePending,
      onPasteAsContext = onPasteAsContext,
      onSelectThinkingLevel = onSelectThinkingLevel
    )
  }

  return ChatSessionState(
    messages = session.messages,
    textState = session.textState,
    isLoading = session.isSending,
    sendingPhase = session.sendingPhase,
    hasSentMessage = session.hasSentMessage,
    selectedPermission = session.selectedPermission,
    isWaitingForResponse = session.isWaitingForResponse,
    inputState = ChatInputState(
      models = session.models.toList(),
      pinnedModels = session.pinnedModels.toList(),
      selectedModel = session.selectedModel,
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
      showAddMenu = session.showAddMenu,
      thinkingLevel = session.thinkingLevel,
    ),
    subAgentState = session.subAgentState,
    sessions = session.sessions,
    isMergeModeActive = session.isMergeModeActive,
    mergeSelectedIds = session.mergeSelection.toSet(),
    suggestionVariants = session.suggestionVariants,
    inputActions = inputActions,
    onSend = onSend,
    onStop = onStop,
    onRetryMessage = onRetryMessage,
    onDeleteMessage = onDeleteMessage,
    onAttachmentClick = onAttachmentClick,
    onOpenInEditor = onOpenInEditor,
    onViewDiff = onViewDiff,
    onCopyAsContext = onCopyAsContext,
    onPasteAsContext = onPasteAsContext,
    onClearText = onClearText,
    onToggleMenu = { session.isMenuVisible = !session.isMenuVisible },
    onDismissMenu = { session.isMenuVisible = false },
    onUploadImage = onUploadImage,
    onToggleAddMenu = { session.showAddMenu = !session.showAddMenu },
    onDismissAddMenu = { session.showAddMenu = false },
    onToggleExpanded = onToggleExpanded,
    onFocusChange = { session.isFocused = it },
    onSelectPermission = onSelectPermission,
    onRemoveFile = onRemoveFile,
    onSelectModel = onSelectModel,
    onTogglePin = onTogglePin,
    onRefreshModels = onRefreshModels,
    onSelectFile = onSelectFile,
    onRemovePending = onRemovePending,
    onSelectThinkingLevel = onSelectThinkingLevel,
    onRefreshSuggestions = { session.suggestionVariants = List(4) { Random.nextInt(5) } },
    onOpenSession = { id -> coroutineScope.launch { session.switchSession(id) }; Unit },
    onStartMerge = { session.enterMergeMode() },
    onCancelMerge = { session.exitMergeMode() },
    onMergeSelected = { coroutineScope.launch { session.mergeSelectedSessions() }; Unit },
    onDeleteSelected = { session.deleteSessions(session.mergeSelection.toList()) },
    onClearMergeSelection = { session.mergeSelection.clear() },
    onToggleMergeSelection = { id -> session.toggleMergeSelection(id) },
    onRenameSession = { id, title -> coroutineScope.launch { session.renameSession(id, title) }; Unit },
    onDeleteSession = { id -> session.deleteSession(id) },
  ) {}
}

@Composable
private fun TabNameEffect(session: GradumChatSession, toolWindow: ToolWindow?) {
  LaunchedEffect(session.hasSentMessage, session.isSending, session.selectedPermission) {
    val tabContent = toolWindow?.contentManager?.contents?.firstOrNull() ?: return@LaunchedEffect
    if (PermissionMode.isDebugMode(session.selectedPermission)) {
      tabContent.displayName = message("gradum.debug.mode"); return@LaunchedEffect
    }
    val tabName: String = if (session.hasSentMessage) message("gradum.toolwindow.newchat") else message("gradum.toolwindow.welcome")
    if (session.isSending) {
      val spinnerFrames = charArrayOf('\u280B', '\u2819', '\u2839', '\u2838', '\u283C', '\u2834', '\u2826', '\u2827')
      var frameIndex = 0
      while (session.isSending) {
        tabContent.displayName = "$tabName  ${spinnerFrames[frameIndex]}"
        frameIndex = (frameIndex + 1) % spinnerFrames.size; delay(100.milliseconds)
      }
    }
    tabContent.displayName = tabName
  }
}

@Composable
private fun ModelPollingEffect(session: GradumChatSession, coroutineScope: CoroutineScope) {
  val settings = remember { ProviderSettings.getInstance() }
  val autoDetect: Boolean = settings.snapshot.autoDetectEnabled
  val pollIntervalSeconds: Int = settings.snapshot.pollIntervalSeconds
  LaunchedEffect(autoDetect, pollIntervalSeconds) {
    if (!session.modelsLoaded) coroutineScope.launch { session.loadModels() }
    session.startModelPolling(autoDetect, pollIntervalSeconds * 1000L, coroutineScope)
  }
  DisposableEffect(Unit) { onDispose { session.stopModelPolling() } }
}
