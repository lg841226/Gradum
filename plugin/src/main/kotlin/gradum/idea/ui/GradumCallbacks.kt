/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumCallbacks.kt  2026-08-17 08:55:38 Changed by gwy
 */

package gradum.idea.ui

import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import gradum.idea.GradumToolWindowFactory
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.MarkdownTlsScenario
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.state.GradumChatSession.Companion.MAX_ATTACHMENTS
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.editor.*
import gradum.idea.encodeImageToAttachment
import gradum.idea.settings.AppearanceSettings
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File
import java.nio.charset.StandardCharsets

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class)
private val logger = Logger.getInstance(MarkdownProcessor::class.java)

private val MARKDOWN_EXTENSIONS = setOf(
  ".md", ".markdown", ".mdown", ".mkd", ".mkdn", ".mdwn"
)

/** Files treated as tool-call debug playable scenarios in DEBUG mode. */
private val SCENARIO_EXTENSIONS = setOf(
  ".tls", ".tls.xml", ".xml"
)

/** Maximum size of the *original* (pre-encoding) image file in bytes (5 MB). */
private const val MAX_IMAGE_BYTES: Long = 5L * 1024L * 1024L

/** Maximum lines of Markdown content rendered in debug mode to prevent OOM. */
private const val MAX_MARKDOWN_LINES: Int = 4_000

private fun truncateToMaxLines(content: String): String {
  val lines = content.lines()
  return if (lines.size > MAX_MARKDOWN_LINES) {
    lines.take(MAX_MARKDOWN_LINES).joinToString("\n") +
      "**Truncated to $MAX_MARKDOWN_LINES lines — this Markdown file has ${lines.size} lines**"
  } else content
}

/**
 * Sends an already-compiled tool-call scenario XML to the server for
 * playback. The server replays it through the real skill pipeline (no
 * LLM tokens), streams `playback_start` / `response` (AI reply text) /
 * `tool_call` / `tool_expect_mismatch` / `playback_end` NDJSON events
 * that the normal chat bubble already renders, and records a result file
 * under the project's `.gradum/recordings/`.
 *
 * @param preserveUserMessage when true the caller already restored the
 *   user bubble (retry path) so this only appends the assistant bubble;
 *   when false a fresh "Play scenario" user message is added (send path).
 */
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
        content = message(
          "gradum.debug.play",
          scenarioLabel
        )
      )
    )

  session.messages.add(
    ChatMessage(
      content = "",
      role = "assistant",
      modelName = displayName,
      provider = providerName,
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

/**
 * Sends the focused `.tls` / `.xml` file's content to the server as a
 * handwritten tool-call scenario.
 */
private fun sendPlaybackScenario(
  session: GradumChatSession, currentFile: VirtualFile, coroutineScope: CoroutineScope
) {
  val scenarioXml: String = try {
    String(currentFile.contentsToByteArray(), StandardCharsets.UTF_8)
  } catch (ioError: IOException) {
    logger.error("IO error reading scenario file: ${currentFile.name}", ioError)
    return
  } catch (securityError: SecurityException) {
    logger.error("Security error accessing scenario file: ${currentFile.name}", securityError)
    return
  } catch (generalError: Exception) {
    logger.warn("Unexpected error reading scenario file: ${currentFile.name}", generalError)
    return
  }
  sendPlaybackXml(scenarioXml, currentFile.name, session, coroutineScope)
}

/**
 * Holds all UI event callbacks for the Gradum chat interface.
 *
 * Separated from the main [GradumUI] composable to keep callback logic
 * independent of UI rendering.
 */
data class GradumCallbacks(
  val onSend: () -> Unit,
  val onStop: () -> Unit,
  val onRetryMessage: (Int) -> Unit,
  val onDeleteMessage: (Int) -> Unit,
  val onAttachmentClick: (VirtualFile) -> Unit,
  val onOpenInEditor: (String, Int, Int) -> Unit,
  val onViewDiff: (String, String, String) -> Unit,
  val eventCallbacks: EventCallbacks
)

/**
 * Low-level UI event callbacks for menu, focus, and file operations.
 */
data class EventCallbacks(
  val onClearText: () -> Unit,
  val onToggleMenu: () -> Unit,
  val onDismissMenu: () -> Unit,
  val onUploadImage: () -> Unit,
  val onToggleAddMenu: () -> Unit,
  val onToggleExpanded: () -> Unit,
  val onDismissAddMenu: () -> Unit,
  val onFocusChange: (Boolean) -> Unit,
  val onCopyAsContext: (String) -> Unit,
  val onPasteAsContext: (String) -> Unit,
  val onSelectFile: (VirtualFile) -> Unit,
  val onSelectPermission: (String) -> Unit,
  val onRemoveFile: (AttachedContext) -> Unit,
  val onRemovePending: (PendingMessage) -> Unit
)

/**
 * Creates and remembers all Gradum callbacks.
 *
 * @param session The chat session scanState
 * @param toolWindow The IntelliJ tool window (nullable for testing)
 * @param coroutineScope Scope for launching coroutines
 */
@Composable
fun rememberGradumCallbacks(
  session: GradumChatSession, toolWindow: ToolWindow?, coroutineScope: CoroutineScope
): GradumCallbacks {
  val onViewDiff = rememberViewDiffCallback(toolWindow)
  val onStop = rememberStopCallback(session, coroutineScope)
  val onDeleteMessage = rememberDeleteMessageCallback(session)
  val eventCallbacks = rememberEventCallbacks(toolWindow, session)
  val onAttachmentClick = rememberAttachmentClickCallback(toolWindow)
  val onSend = rememberSendCallback(toolWindow, session, coroutineScope)
  val onOpenInEditor = rememberOpenInEditorCallback(toolWindow, coroutineScope)
  val onRetryMessage = rememberRetryMessageCallback(toolWindow, session, coroutineScope)

  return GradumCallbacks(
    onSend = onSend,
    onStop = onStop,
    onViewDiff = onViewDiff,
    eventCallbacks = eventCallbacks,
    onRetryMessage = onRetryMessage,
    onOpenInEditor = onOpenInEditor,
    onDeleteMessage = onDeleteMessage,
    onAttachmentClick = onAttachmentClick
  )
}

@Composable
private fun rememberEventCallbacks(
  toolWindow: ToolWindow?, session: GradumChatSession
): EventCallbacks {
  return EventCallbacks(
    onDismissMenu = { session.isMenuVisible = false },
    onDismissAddMenu = { session.showAddMenu = false },
    onToggleMenu = { session.isMenuVisible = !session.isMenuVisible },
    onToggleAddMenu = { session.showAddMenu = !session.showAddMenu },
    onToggleExpanded = {
      session.isExpanded = !session.isExpanded
      AppearanceSettings.getInstance().update {
        it.lastContextEnabled = session.isExpanded
      }
    },
    onClearText = { session.textState.edit { delete(0, length) } },
    onUploadImage = uploadImageCallback(toolWindow, session),
    onFocusChange = { session.isFocused = it },
    onCopyAsContext = { text ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS) {
        val previewText = if (text.length > 30) text.take(30) + "..." else text
        session.attachedFiles.add(AttachedText(content = text, preview = previewText))
      }
    },
    onPasteAsContext = { text ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS) {
        val previewText = if (text.length > 30) text.take(30) + "..." else text
        session.attachedFiles.add(AttachedText(content = text, preview = previewText))
      }
    },
    onSelectFile = { file ->
      if (session.attachedFiles.size < MAX_ATTACHMENTS &&
        session.attachedFiles.none { it is AttachedFile && it.file.path == file.path }
      ) {
        val iconKey = if (file.isDirectory)
          AllIconsKeys.Actions.ProjectDirectory
        else
          getLanguageIconKey(file.extension) ?: AllIconsKeys.FileTypes.Unknown
        session.attachedFiles.add(AttachedFile(file = file, iconKey = iconKey))
      }
    },
    onSelectPermission = { permission ->
      session.selectedPermission = permission
      session.isMenuVisible = false
      AppearanceSettings.getInstance().update {
        it.lastPermission = permission
      }
    },
    onRemoveFile = { attachedContext ->
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
    },
  ) { pending -> session.pendingMessages.remove(pending) }
}

private fun uploadImageCallback(toolWindow: ToolWindow?, session: GradumChatSession): () -> Unit = Unit@{
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

@Composable
private fun rememberDeleteMessageCallback(
  session: GradumChatSession,
): (Int) -> Unit = remember(session) {
  { userMessageIndex -> session.deleteMessage(userMessageIndex) }
}

@Composable
private fun rememberRetryMessageCallback(
  toolWindow: ToolWindow?, session: GradumChatSession, coroutineScope: CoroutineScope
): (Int) -> Unit = remember(session, toolWindow, coroutineScope) {
  { assistantMessageIndex: Int ->
    val userMessageIndex = (assistantMessageIndex - 1 downTo 0)
      .firstOrNull { session.messages[it].isUserMessage }

    if (userMessageIndex != null) {
      val userMessage = session.messages[userMessageIndex]

      if (PermissionMode.isDebugMode(session.selectedPermission)) {
        val toolProject = toolWindow?.project
        val editorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
        val currentFile = editorContext?.currentFile
        if (currentFile != null &&
          MARKDOWN_EXTENSIONS.any { currentFile.name.endsWith(it, ignoreCase = true) }
        ) {
          try {
            val editors = FileEditorManager.getInstance(toolProject).getEditors(currentFile)
            val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
            val content = truncateToMaxLines(
              textEditor?.editor?.document?.text
                ?: String(currentFile.contentsToByteArray(), StandardCharsets.UTF_8)
            )
            val messagesToRemove = assistantMessageIndex - userMessageIndex + 1
            repeat(messagesToRemove) { session.messages.removeAt(userMessageIndex) }
            session.messages.add(userMessageIndex, ChatMessage(role = "user", content = userMessage.content))
            session.hasSentMessage = true

            // Retrying a Markdown doc that embeds <tls> blocks replays the
            // whole turn through the real tool pipeline, matching the send path.
            val compiled: String? = MarkdownTlsScenario.compile(content, currentFile.name)
            if (compiled != null) {
              sendPlaybackXml(compiled, currentFile.name, session, coroutineScope, preserveUserMessage = true)
            } else {
              session.loadDebugMarkdown(content)
            }
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
      val editorContext = activeProject?.let { EditorUtils.getEditorContext(it) }
      val focusedPath = editorContext?.currentFile?.path ?: ""
      val openFiles = editorContext?.allOpenFiles ?: emptyList()
      val (resolvedText, anyReplaced) = GradumChatSession.resolveInlineTags(
        userMessage.content,
        focusedPath, openFiles
      )

      val displayName = session.selectedModel?.name ?: ""
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
          content = "",
          role = "assistant",
          modelName = displayName,
          provider = providerName,
          serverName = serverLabel
        )
      )

      session.isSending = true
      session.isWaitingForResponse = true
      session.currentJob = coroutineScope.launch {
        val contextPath = if (session.isExpanded && !anyReplaced) focusedPath else ""
        session.sendMessage(resolvedText, userMessage.attachments, contextPath)
      }
    }
  }
}

@Composable
private fun rememberSendCallback(
  toolWindow: ToolWindow?, session: GradumChatSession, coroutineScope: CoroutineScope
): () -> Unit = remember(session, toolWindow, coroutineScope) {
  {
    val rawText: String = session.textState.text.toString()
    val hasModel = session.selectedModel != null

    // Debug mode: loadProperties focused Markdown file directly without calling LLM,
    // or run a focused tool-call scenario (.tls/.xml) through real playback.
    if (PermissionMode.isDebugMode(session.selectedPermission)) {
      if (rawText.isNotBlank()) {
        val toolProject = toolWindow?.project
        val editorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
        val currentFile = editorContext?.currentFile
        if (currentFile != null) {
          if (SCENARIO_EXTENSIONS.any { currentFile.name.endsWith(it, ignoreCase = true) }) {
            sendPlaybackScenario(session, currentFile, coroutineScope)
          } else if (MARKDOWN_EXTENSIONS.any { currentFile.name.endsWith(it, ignoreCase = true) }) {
            try {
              val editors = FileEditorManager.getInstance(toolProject).getEditors(currentFile)
              val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
              val content = truncateToMaxLines(
                textEditor?.editor?.document?.text
                  ?: String(currentFile.contentsToByteArray(), StandardCharsets.UTF_8)
              )
              // Markdown with embedded <tls> blocks replays as a scenario:
              // narration between blocks becomes AI reply text, blocks become
              // real tool calls. Plain Markdown still renders directly.
              val compiled: String? = MarkdownTlsScenario.compile(content, currentFile.name)
              if (compiled != null) {
                sendPlaybackXml(compiled, currentFile.name, session, coroutineScope)
              } else {
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
      val toolProject = toolWindow?.project
      val editorContext = toolProject?.let { EditorUtils.getEditorContext(it) }
      val focusedPath = editorContext?.currentFile?.path ?: ""
      val openFiles = editorContext?.allOpenFiles ?: emptyList()
      val (resolvedText, anyReplaced) = GradumChatSession.resolveInlineTags(rawText, focusedPath, openFiles)
      if (session.isSending) {
        if (!session.isPendingQueueFull) {
          session.pendingMessages.add(
            PendingMessage(
              content = resolvedText,
              attachments = session.attachedFiles.toList()
            )
          )
        }
      } else {
        val attachedList = session.attachedFiles.toList()
        val displayName = session.selectedModel?.name ?: ""
        val providerName = session.selectedModel?.provider ?: ""
        val serverLabel = session.selectedModel?.serverName ?: ""
        session.messages.add(ChatMessage(role = "user", content = rawText, attachments = attachedList))
        session.messages.add(
          ChatMessage(
            content = "",
            role = "assistant",
            modelName = displayName,
            provider = providerName,
            serverName = serverLabel
          )
        )
        session.isSending = true
        session.hasSentMessage = true
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
}

@Composable
private fun rememberStopCallback(
  session: GradumChatSession, coroutineScope: CoroutineScope
): () -> Unit = remember(session, coroutineScope) {
  { coroutineScope.launch { session.stopSession() } }
}

@Composable
private fun rememberOpenInEditorCallback(
  toolWindow: ToolWindow?, coroutineScope: CoroutineScope
): (String, Int, Int) -> Unit = remember(toolWindow, coroutineScope) {
  { filePath, startLine, _ ->
    val project = toolWindow?.project
    if (project != null && filePath.isNotBlank()) {
      coroutineScope.launch(Dispatchers.IO) {
        try {
          val absolutePath = if (File(filePath).isAbsolute) {
            filePath
          } else project.basePath?.let { "$it/$filePath" } ?: filePath

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
            val tempVirtual: VirtualFile? =
              LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
            if (tempVirtual != null) {
              withContext(Dispatchers.Main) {
                FileEditorManager.getInstance(project).openFile(tempVirtual, true)
              }
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

@Composable
private fun rememberViewDiffCallback(
  toolWindow: ToolWindow?,
): (String, String, String) -> Unit = remember(toolWindow) {
  { filePath, originalContent, modifiedContent ->
    gradum.idea.chat.ui.common.DiffViewer.showFileDiff(
      path = filePath,
      project = toolWindow?.project,
      originalContent = originalContent,
      modifiedContent = modifiedContent
    )
  }
}

@Composable
private fun rememberAttachmentClickCallback(toolWindow: ToolWindow?):
    (VirtualFile) -> Unit = remember(toolWindow) {
  { file ->
    val project = toolWindow?.project
    if (project != null && file.isValid) {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
  }
}
