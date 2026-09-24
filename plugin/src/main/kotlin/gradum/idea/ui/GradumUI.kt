/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumUI.kt  2026-09-25 01:19:20 Changed by gwy
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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType.CENTER
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
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
import gradum.idea.chat.ui.common.DiffViewer.showFileDiff
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
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class)
private val logger: Logger = Logger.getInstance(MarkdownProcessor::class.java)

private val MARKDOWN_EXTENSIONS = setOf(".md", ".markdown", ".mdown", ".mkd", ".mkdn", ".mdwn")
private val SCENARIO_EXTENSIONS = setOf(".tls", ".tls.xml", ".xml")
private const val MAX_IMAGE_BYTES: Long = PluginConfig.MAX_IMAGE_UPLOAD_BYTES
private const val MAX_MARKDOWN_LINES: Int = PluginConfig.MAX_MARKDOWN_LINES

private fun truncateToMaxLines(content: String): String {
  val lines = content.lines()
  return if (lines.size > MAX_MARKDOWN_LINES) {
    lines.take(n = MAX_MARKDOWN_LINES).joinToString(separator = "\n") +
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
    session.messages.add(
      ChatMessage(
        role = "user",
        content = message("gradum.debug.play", scenarioLabel)
      )
    )

  session.messages.add(
    ChatMessage(
      role = "assistant",
      provider = providerName,
      modelName = displayName,
      serverName = serverLabel
    )
  )
  session.hasSentMessage = true
  session.isSending = true
  session.isWaitingForResponse = true
  logger.info("Sending tool-call scenario '$scenarioLabel' to server for playback")

  session.currentJob = coroutineScope.launch {
    session.sendMessage(
      contextPath = "",
      attachments = emptyList(),
      toolCallXml = scenarioXml,
      userMessage = message("gradum.debug.play", scenarioLabel)
    )
  }
}

private fun sendPlaybackScenario(
  session: GradumChatSession, currentFile: VirtualFile, coroutineScope: CoroutineScope
) {
  val scenarioXml: String = try {
    String(bytes = currentFile.contentsToByteArray(), charset = StandardCharsets.UTF_8)
  } catch (ioError: IOException) {
    logger.error("IO error reading scenario file: ${currentFile.name}", ioError); return
  } catch (securityError: SecurityException) {
    logger.error("Security error accessing scenario file: ${currentFile.name}", securityError); return
  } catch (generalError: Exception) {
    logger.warn("Unexpected error reading scenario file: ${currentFile.name}", generalError); return
  }
  sendPlaybackXml(scenarioXml, scenarioLabel = currentFile.name, session, coroutineScope)
}

/**
 * Main composable for the Gradum chat interface.
 */
@Composable
fun GradumUI(toolWindow: ToolWindow? = null, session: GradumChatSession) {
  val editorContext: EditorContext = toolWindow?.project?.let {
    EditorUtils.getEditorContext(project = it)
  } ?: EditorContext.EMPTY
  val coroutineScope: CoroutineScope = rememberCoroutineScope()

  TabNameEffect(session, toolWindow)
  ModelPollingEffect(session, coroutineScope)

  val state: ChatSessionState = rememberChatSessionState(
    toolWindow = toolWindow,
    session = session,
    editorContext = editorContext,
    coroutineScope = coroutineScope,
  )

  Box(
    modifier = Modifier.fillMaxSize()
      .padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center
  ) {
    ProvideAppearance {
      if (session.hasSentMessage) {
        ChatScreen(
          state = state,
          modifier = Modifier.fillMaxSize()
        )
      } else {
        WelcomeScreen(
          state = state,
          modifier = Modifier.fillMaxSize(),
          welcomeLayout = AppearanceSettings.getInstance().snapshot.welcomeLayout
        )
      }
    }
  }
}

@Composable
private fun rememberChatSessionState(
  toolWindow: ToolWindow?,
  session: GradumChatSession,
  editorContext: EditorContext,
  coroutineScope: CoroutineScope
): ChatSessionState {
  val onClearText = remember(key1 = session) {
    {
      session.textState.edit { delete(0, length) }
    }
  }

  val onStop: () -> Unit = remember(key1 = session, key2 = coroutineScope) {
    {
      coroutineScope.launch { session.stopSession() }
    }
  }

  val onDeleteMessage = remember(key1 = session) {
    { userMessageIndex: Int -> session.deleteMessage(userMessageIndex) }
  }

  val onUploadImage: () -> Unit = remember(key1 = toolWindow, key2 = session) {
    {
      val remainingSlots: Int = MAX_ATTACHMENTS - session.attachedFiles.size
      if (remainingSlots > 0) {
        val currentProject: Project? = toolWindow?.project
        if (currentProject != null) {
          val imageExtensions: Set<String> = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff")
          val fileDescriptor: FileChooserDescriptor = FileChooserDescriptorFactory.multiFiles().apply {
            withFileFilter { file: VirtualFile ->
              file.extension?.lowercase() in imageExtensions
            }
            title = "Select Images"
          }
          val baseDir: VirtualFile? = currentProject.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
          }
          FileChooser.chooseFiles(fileDescriptor, currentProject, baseDir) { files ->
            files.asSequence()
              .filter { it.extension?.lowercase() in imageExtensions }
              .filter { imageFile: VirtualFile ->
                session.attachedFiles.none { existing: AttachedContext ->
                  (existing is AttachedFile && existing.file.path == imageFile.path)
                    || (existing is AttachedImage
                    && existing.originalName == imageFile.name
                    && imageFile.length == existing.originalSizeBytes)
                }
              }
              .take(n = remainingSlots)
              .forEach { file: VirtualFile ->
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

  val attachText: (String) -> Unit = remember(key1 = session) {
    { text: String ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS) {
        val previewText: String = if (text.length > 30) text.take(n = 30) + "..." else text
        session.attachedFiles.add(AttachedText(content = text, preview = previewText))
      }
    }
  }

  val onSelectFile: (VirtualFile) -> Unit = remember(key1 = session) {
    { file: VirtualFile ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS && session.attachedFiles.none {
          it is AttachedFile && it.file.path == file.path
        }) {
        val iconKey: IconKey =
          if (file.isDirectory) AllIconsKeys.Actions.ProjectDirectory
          else (getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown)
        session.attachedFiles.add(AttachedFile(file = file, iconKey = iconKey))
      }
    }
  }

  val onSelectPermission: (String) -> Unit = remember(key1 = session) {
    { permission: String ->
      session.selectedPermission = permission
      session.isMenuVisible = false
      AppearanceSettings.getInstance().update { it.lastPermission = permission }
    }
  }

  val onRemoveFile: (AttachedContext) -> Unit = remember(key1 = session) {
    { attachedContext: AttachedContext ->
      when (attachedContext) {
        is AttachedFile -> {
          session.attachedFiles.removeAll {
            it is AttachedFile && it.file.path == attachedContext.file.path
          }
        }

        is AttachedText -> {
          session.attachedFiles.removeAll {
            it is AttachedText && it.content == attachedContext.content
          }
        }

        is AttachedImage -> {
          session.attachedFiles.removeAll {
            it is AttachedImage && it.id == attachedContext.id
          }
        }
      }
    }
  }

  val onRemovePending: (PendingMessage) -> Unit = remember(key1 = session) {
    { pending: PendingMessage ->
      session.pendingMessages.remove(element = pending)
    }
  }

  val onToggleExpanded = remember(key1 = session) {
    {
      session.isExpanded = !session.isExpanded
      AppearanceSettings.getInstance().update {
        it.lastContextEnabled = session.isExpanded
      }
    }
  }

  val onAttachmentClick = remember(key1 = toolWindow) {
    { file: VirtualFile ->
      val project: Project? = toolWindow?.project
      if (project != null && file.isValid)
        FileEditorManager.getInstance(project).openFile(file, true)
    }
  }

  val onViewDiff = remember(key1 = toolWindow) {
    { filePath: String, originalContent: String, modifiedContent: String ->
      showFileDiff(
        path = filePath,
        project = toolWindow?.project,
        originalContent = originalContent,
        modifiedContent = modifiedContent
      )
    }
  }

  val onOpenInEditor = remember(key1 = toolWindow, key2 = coroutineScope) {
    { filePath: String, startLine: Int, _: Int ->
      val project: Project? = toolWindow?.project
      if (project != null && filePath.isNotBlank()) {
        coroutineScope.launch(context = Dispatchers.IO) {
          try {
            val absolutePath: String =
              if (File(filePath).isAbsolute) filePath
              else project.basePath?.let { "$it/$filePath" } ?: filePath

            val virtualFile: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (virtualFile != null && virtualFile.exists()) {
              withContext(Dispatchers.Main) {
                val editors = FileEditorManager.getInstance(project).openFile(virtualFile, true)
                val textEditor: TextEditor? = editors.filterIsInstance<TextEditor>().firstOrNull()
                if (startLine > 0 && textEditor != null) {
                  val editor: Editor = textEditor.editor
                  val offset: Int = editor.document.getLineStartOffset((startLine - 1).coerceAtLeast(minimumValue = 0))
                  editor.caretModel.moveToOffset(offset)
                  editor.scrollingModel.scrollToCaret(CENTER)
                }
              }
            } else {
              val tempFile: File = File.createTempFile("gradum_cmd_", ".sh")
              tempFile.writeText(filePath)
              tempFile.deleteOnExit()

              val tempVirtual: VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
              if (tempVirtual != null)
                withContext(Dispatchers.Main) {
                  FileEditorManager.getInstance(project).openFile(tempVirtual, true)
                }
            }
          } catch (exception: Exception) {
            Logger.getInstance(GradumToolWindowFactory::class.java)
              .warn("Failed to open target in editor: $filePath", exception)
          }
        }
      }
    }
  }

  val onRetryMessage: (Int) -> Unit = remember(key1 = toolWindow, key2 = session, key3 = coroutineScope) {
    { assistantMessageIndex: Int ->
      val userMessageIndex: Int = (assistantMessageIndex - 1 downTo 0).firstOrNull {
        session.messages[it].isUserMessage
      } ?: return@remember
      val userMessage: ChatMessage = session.messages[userMessageIndex]

      if (PermissionMode.isDebugMode(session.selectedPermission)) {
        val toolProject: Project? = toolWindow?.project
        val currentEditorContext: EditorContext? = toolProject?.let {
          EditorUtils.getEditorContext(project = it)
        }
        val currentFile = currentEditorContext?.currentFile

        if (currentFile != null && MARKDOWN_EXTENSIONS.any {
            currentFile.name.endsWith(suffix = it, ignoreCase = true)
          }) {
          try {
            val editors = FileEditorManager.getInstance(toolProject).getEditors(currentFile)
            val textEditor: TextEditor? = editors.filterIsInstance<TextEditor>().firstOrNull()
            val content: String = truncateToMaxLines(
              content = textEditor?.editor?.document?.text ?: String(
                bytes = currentFile.contentsToByteArray(),
                charset = StandardCharsets.UTF_8
              )
            )
            val messagesToRemove: Int = assistantMessageIndex - userMessageIndex + 1
            repeat(times = messagesToRemove) {
              session.messages.removeAt(userMessageIndex)
            }

            session.messages.add(
              userMessageIndex,
              element = ChatMessage(
                role = "user",
                content = userMessage.content
              )
            )
            session.hasSentMessage = true

            val compiled: String? = MarkdownTlsScenario.compile(
              markdown = content,
              scenarioName = currentFile.name
            )
            if (compiled != null)
              sendPlaybackXml(
                scenarioXml = compiled,
                scenarioLabel = currentFile.name,
                session,
                coroutineScope,
                preserveUserMessage = true
              )
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

      val activeProject: Project? = toolWindow?.project
      val currentEditorContext: EditorContext? = activeProject?.let {
        EditorUtils.getEditorContext(project = it)
      }
      val focusedPath: String = currentEditorContext?.currentFile?.path ?: ""
      val openFiles = currentEditorContext?.allOpenFiles ?: emptyList()
      val (resolvedText: String, anyReplaced: Boolean) =
        GradumChatSession.resolveInlineTags(
          text = userMessage.content,
          focusedFilePath = focusedPath, openFiles
        )

      val displayName: String = session.selectedModel?.name ?: ""
      val providerName: String = session.selectedModel?.provider ?: ""
      val serverLabel: String = session.selectedModel?.serverName ?: ""

      val messagesToRemove: Int = assistantMessageIndex - userMessageIndex + 1
      repeat(times = messagesToRemove) { session.messages.removeAt(userMessageIndex) }

      val nextMessageId: String = java.util.UUID.randomUUID().toString()
      session.messages.add(
        userMessageIndex,
        element = ChatMessage(
          role = "user",
          content = userMessage.content,
          attachments = userMessage.attachments,
          messageId = nextMessageId
        )
      )
      session.messages.add(
        index = userMessageIndex + 1,
        element = ChatMessage(
          role = "assistant",
          provider = providerName,
          modelName = displayName,
          serverName = serverLabel
        )
      )

      session.isSending = true
      session.isWaitingForResponse = true
      session.currentJob = coroutineScope.launch {
        val contextPath: String =
          if (session.isExpanded && !anyReplaced) focusedPath
          else ""
        session.sendMessage(userMessage = resolvedText, userMessage.attachments, contextPath, messageId = nextMessageId)
      }
    }
  }

  val onSend: () -> Unit = remember(key1 = toolWindow, key2 = session, key3 = coroutineScope) {
    {
      val rawText: String = session.textState.text.toString()
      val hasModel: Boolean = session.selectedModel != null

      if (PermissionMode.isDebugMode(session.selectedPermission)) {
        if (rawText.isNotBlank()) {
          val toolProject: Project? = toolWindow?.project
          val currentEditorContext: EditorContext? = toolProject?.let {
            EditorUtils.getEditorContext(project = it)
          }
          val currentFile: VirtualFile? = currentEditorContext?.currentFile
          if (currentFile != null) {
            if (SCENARIO_EXTENSIONS.any {
                currentFile.name.endsWith(suffix = it, ignoreCase = true)
              }) {
              sendPlaybackScenario(session, currentFile, coroutineScope)
            } else if (MARKDOWN_EXTENSIONS.any {
                currentFile.name.endsWith(suffix = it, ignoreCase = true)
              }) {
              try {
                val editors = FileEditorManager.getInstance(toolProject).getEditors(currentFile)
                val textEditor: TextEditor? = editors.filterIsInstance<TextEditor>().firstOrNull()
                val content: String = truncateToMaxLines(
                  content = textEditor?.editor?.document?.text ?: String(
                    bytes = currentFile.contentsToByteArray(),
                    charset = StandardCharsets.UTF_8
                  )
                )

                val compiled: String? = MarkdownTlsScenario.compile(
                  markdown = content,
                  scenarioName = currentFile.name
                )

                if (compiled != null)
                  sendPlaybackXml(
                    scenarioXml = compiled,
                    scenarioLabel = currentFile.name,
                    session,
                    coroutineScope
                  )
                else {
                  session.hasSentMessage = true
                  session.messages.add(ChatMessage(role = "user", content = rawText))
                  session.loadDebugMarkdown(content)
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
        val toolProject: Project? = toolWindow?.project
        val currentEditorContext: EditorContext? = toolProject?.let {
          EditorUtils.getEditorContext(project = it)
        }
        val focusedPath: String = currentEditorContext?.currentFile?.path ?: ""
        val openFiles: List<VirtualFile> = currentEditorContext?.allOpenFiles ?: emptyList()
        val (resolvedText: String, anyReplaced: Boolean) =
          GradumChatSession.resolveInlineTags(rawText, focusedFilePath = focusedPath, openFiles)
        if (session.isSending) {
          if (!session.isPendingQueueFull)
            session.pendingMessages.add(
              PendingMessage(
                content = resolvedText,
                attachments = session.attachedFiles.toList()
              )
            )

        } else {
          val displayName: String = session.selectedModel?.name ?: ""
          val providerName: String = session.selectedModel?.provider ?: ""
          val serverLabel: String = session.selectedModel?.serverName ?: ""
          val attachedList = session.attachedFiles.toList()
          val sendMessageId: String = java.util.UUID.randomUUID().toString()
          session.messages.add(
            ChatMessage(
              role = "user",
              content = rawText,
              attachments = attachedList,
              messageId = sendMessageId
            )
          )
          session.messages.add(
            ChatMessage(
              role = "assistant",
              provider = providerName,
              modelName = displayName,
              serverName = serverLabel
            )
          )
          session.isSending = true
          session.hasSentMessage = true
          session.isWaitingForResponse = true
          session.currentJob = coroutineScope.launch {
            val contextPath: String =
              if (session.isExpanded && !anyReplaced) focusedPath
              else ""
            session.sendMessage(userMessage = resolvedText, attachments = attachedList, contextPath, messageId = sendMessageId)
          }
        }
        session.textState.edit { delete(0, length) }
        session.attachedFiles.clear()
      }
    }
  }

  val onSelectModel: (ModelInfo?) -> Unit = remember(key1 = session) {
    { model: ModelInfo? ->
      session.selectedModel = model
    }
  }

  val onTogglePin: (ModelInfo) -> Unit = remember(key1 = session) {
    { model: ModelInfo ->
      if (session.pinnedModels.any {
          it.sameAs(other = model)
        }) session.pinnedModels.removeAll {
        it.sameAs(other = model)
      }
      else session.pinnedModels.add(model)
    }
  }

  val onRefreshModels: () -> Unit = remember(key1 = session, key2 = coroutineScope) {
    {
      coroutineScope.launch { session.loadModels() }
    }
  }

  val onSelectThinkingLevel: (ThinkingLevel) -> Unit = remember(key1 = session) {
    { level: ThinkingLevel ->
      session.setThinkingLevel(level)
    }
  }

  val inputActions: ChatInputActions = remember(
    onSend, onStop, onUploadImage, onClearText, session,
    attachText, attachText, onSelectFile, onSelectPermission,
    onRemoveFile, onRemovePending, onSelectModel, onTogglePin, onRefreshModels, onToggleExpanded
  ) {
    ChatInputActions(
      onSend = onSend,
      onStop = onStop,
      onClearText = onClearText,
      onTogglePin = onTogglePin,
      onRemoveFile = onRemoveFile,
      onSelectFile = onSelectFile,
      onSelectModel = onSelectModel,
      onUploadImage = onUploadImage,
      onRefreshModels = onRefreshModels,
      onRemovePending = onRemovePending,
      onPasteAsContext = attachText,
      onToggleExpanded = onToggleExpanded,
      onSelectPermission = onSelectPermission,
      onFocusChange = { session.isFocused = it },
      onSelectThinkingLevel = onSelectThinkingLevel,
      onDismissMenu = { session.isMenuVisible = false },
      onDismissAddMenu = { session.showAddMenu = false },
      onToggleAddMenu = { session.showAddMenu = !session.showAddMenu },
      onToggleMenu = { session.isMenuVisible = !session.isMenuVisible }
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
      editorContext = editorContext,
      isFocused = session.isFocused,
      isSending = session.isSending,
      isExpanded = session.isExpanded,
      models = session.models.toList(),
      showAddMenu = session.showAddMenu,
      modelsLoaded = session.modelsLoaded,
      selectedModel = session.selectedModel,
      attachedFiles = session.attachedFiles,
      thinkingLevel = session.thinkingLevel,
      isMenuVisible = session.isMenuVisible,
      pendingMessages = session.pendingMessages,
      pinnedModels = session.pinnedModels.toList(),
      selectedPermission = session.selectedPermission,
      isPendingQueueFull = session.isPendingQueueFull,
      isAttachmentLimitReached = session.isAttachmentLimitReached,
    ),
    inputActions = inputActions,
    onSend = onSend,
    onStop = onStop,
    onRetryMessage = onRetryMessage,
    onDeleteMessage = onDeleteMessage,
    onAttachmentClick = onAttachmentClick,
    onOpenInEditor = onOpenInEditor,
    onViewDiff = onViewDiff,
    onClearText = onClearText,
    onTogglePin = onTogglePin,
    onSelectFile = onSelectFile,
    sessions = session.sessions,
    onRemoveFile = onRemoveFile,
    onUploadImage = onUploadImage,
    onSelectModel = onSelectModel,
    onCopyAsContext = attachText,
    onRefreshModels = onRefreshModels,
    onRemovePending = onRemovePending,
    onPasteAsContext = attachText,
    onToggleExpanded = onToggleExpanded,
    subAgentState = session.subAgentState,
    onSelectPermission = onSelectPermission,
    onFocusChange = { session.isFocused = it },
    onStartMerge = { session.enterMergeMode() },
    onCancelMerge = { session.exitMergeMode() },
    isMergeModeActive = session.isMergeModeActive,
    onSelectThinkingLevel = onSelectThinkingLevel,
    suggestionVariants = session.suggestionVariants,
    mergeSelectedIds = session.mergeSelection.toSet(),
    onDismissMenu = { session.isMenuVisible = false },
    onDismissAddMenu = { session.showAddMenu = false },
    onClearMergeSelection = { session.mergeSelection.clear() },
    onToggleAddMenu = { session.showAddMenu = !session.showAddMenu },
    onToggleMenu = { session.isMenuVisible = !session.isMenuVisible },
    onMergeSelected = { coroutineScope.launch { session.mergeSelectedSessions() } },
    onDeleteSession = { id: String -> session.deleteSession(targetSessionId = id) },
    onToggleMergeSelection = { id: String -> session.toggleMergeSelection(sessionId = id) },
    onDeleteSelected = { session.deleteSessions(sessionIds = session.mergeSelection.toList()) },
    onOpenSession = { id -> coroutineScope.launch { session.switchSession(targetSessionId = id) } },
    onRefreshSuggestions = { session.suggestionVariants = List(size = 4) { Random.nextInt(until = 5) } },
    onRenameSession = { id: String, title: String -> coroutineScope.launch { session.renameSession(sessionId = id, newTitle = title) } },
    onRespondToAsk = { sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean ->
      coroutineScope.launch {
        session.apiClient.respondToAsk(
          sessionId = sessionId,
          requestId = requestId,
          choice = choice,
          text = text,
          cancelled = cancelled
        )
      }
    },
  ) {}
}

@Composable
private fun TabNameEffect(session: GradumChatSession, toolWindow: ToolWindow?) {
  LaunchedEffect(
    key1 = session.hasSentMessage,
    key2 = session.isSending,
    key3 = session.selectedPermission
  ) {
    val tabContent =
      toolWindow
        ?.contentManager
        ?.contents
        ?.firstOrNull()
        ?: return@LaunchedEffect

    if (PermissionMode.isDebugMode(session.selectedPermission)) {
      tabContent.displayName = message(key = "gradum.debug.mode")
      return@LaunchedEffect
    }

    val tabName: String = if (session.hasSentMessage) message("gradum.toolwindow.newchat") else message("gradum.toolwindow.welcome")
    if (session.isSending) {
      val spinnerFrames: CharArray = charArrayOf(
        '\u280B', '\u2819', '\u2839', '\u2838', '\u283C', '\u2834', '\u2826', '\u2827'
      )
      var frameIndex = 0
      while (session.isSending) {
        tabContent.displayName = "$tabName ${spinnerFrames[frameIndex]}"
        frameIndex = (frameIndex + 1) % spinnerFrames.size
        delay(duration = 100.milliseconds)
      }
    }
    tabContent.displayName = tabName
  }
}

@Composable
private fun ModelPollingEffect(session: GradumChatSession, coroutineScope: CoroutineScope) {
  val settings: ProviderSettings = remember { ProviderSettings.getInstance() }
  val autoDetect: Boolean = settings.snapshot.autoDetectEnabled
  val pollIntervalSeconds: Int = settings.snapshot.pollIntervalSeconds

  LaunchedEffect(key1 = autoDetect, key2 = pollIntervalSeconds) {
    if (!session.modelsLoaded) coroutineScope.launch { session.loadModels() }
    session.startModelPolling(
      autoDetect,
      pollIntervalMs = pollIntervalSeconds * 1000L, coroutineScope
    )
  }
  DisposableEffect(key1 = Unit) {
    onDispose {
      session.stopModelPolling()
    }
  }
}
