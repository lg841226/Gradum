/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumCallbacks.kt  2026-07-30 11:52:24 Changed by gwy
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
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.chat.state.GradumChatSession.Companion.MAX_ATTACHMENTS
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.editor.*
import gradum.idea.encodeImageToAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.charset.StandardCharsets

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class)
private val logger = Logger.getInstance(MarkdownProcessor::class.java)

private val MARKDOWN_EXTENSIONS = setOf(
  ".md", ".markdown", ".mdown", ".mkd", ".mkdn", ".mdwn"
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
 * Holds all UI event callbacks for the Gradum chat interface.
 *
 * Separated from the main [GradumUI] composable to keep callback logic
 * independent of UI rendering.
 */
data class GradumCallbacks(
  val onDeleteMessage: (Int) -> Unit,
  val onRetryMessage: (Int) -> Unit,
  val onSend: () -> Unit,
  val onStop: () -> Unit,
  val onOpenInEditor: (String, Int, Int) -> Unit,
  val onViewDiff: (String, String, String) -> Unit,
  val onAttachmentClick: (VirtualFile) -> Unit,
  val eventCallbacks: EventCallbacks,
)

/**
 * Low-level UI event callbacks for menu, focus, and file operations.
 */
data class EventCallbacks(
  val onFocusChange: (Boolean) -> Unit,
  val onToggleExpanded: () -> Unit,
  val onToggleMenu: () -> Unit,
  val onDismissMenu: () -> Unit,
  val onSelectPermission: (String) -> Unit,
  val onToggleAddMenu: () -> Unit,
  val onDismissAddMenu: () -> Unit,
  val onSelectFile: (VirtualFile) -> Unit,
  val onRemoveFile: (AttachedContext) -> Unit,
  val onUploadImage: () -> Unit,
  val onCopyAsContext: (String) -> Unit,
  val onPasteAsContext: (String) -> Unit,
  val onClearText: () -> Unit,
  val onRemovePending: (PendingMessage) -> Unit,
)

/**
 * Creates and remembers all Gradum callbacks.
 *
 * @param session The chat session state
 * @param toolWindow The IntelliJ tool window (nullable for testing)
 * @param coroutineScope Scope for launching coroutines
 */
@Composable
fun rememberGradumCallbacks(
  session: GradumChatSession,
  toolWindow: ToolWindow?,
  coroutineScope: CoroutineScope,
): GradumCallbacks {
  val onViewDiff = rememberViewDiffCallback(toolWindow)
  val onStop = rememberStopCallback(session, coroutineScope)
  val onDeleteMessage = rememberDeleteMessageCallback(session)
  val eventCallbacks = rememberEventCallbacks(toolWindow, session)
  val onAttachmentClick = rememberAttachmentClickCallback(toolWindow)
  val onSend = rememberSendCallback(toolWindow, session, coroutineScope)
  val onOpenInEditor = rememberOpenInEditorCallback(toolWindow, coroutineScope)
  val onRetryMessage = rememberRetryMessageCallback(session, toolWindow, coroutineScope)

  return GradumCallbacks(
    onSend = onSend,
    onStop = onStop,
    onViewDiff = onViewDiff,
    onOpenInEditor = onOpenInEditor,
    eventCallbacks = eventCallbacks,
    onRetryMessage = onRetryMessage,
    onDeleteMessage = onDeleteMessage,
    onAttachmentClick = onAttachmentClick
  )
}

@Composable
private fun rememberEventCallbacks(
  toolWindow: ToolWindow?, session: GradumChatSession
): EventCallbacks {
  return EventCallbacks(
    onFocusChange = { session.isFocused = it },
    onToggleExpanded = { session.isExpanded = !session.isExpanded },
    onToggleMenu = { session.isMenuVisible = !session.isMenuVisible },
    onDismissMenu = { session.isMenuVisible = false },
    onSelectPermission = { permission ->
      session.selectedPermission = permission
      session.isMenuVisible = false
    },
    onToggleAddMenu = { session.showAddMenu = !session.showAddMenu },
    onDismissAddMenu = { session.showAddMenu = false },
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
    onRemoveFile = { attachedContext ->
      when (attachedContext) {
        is AttachedFile -> session.attachedFiles.removeAll {
          it is AttachedFile && it.file.path == attachedContext.file.path
        }

        is AttachedText -> session.attachedFiles.removeAll {
          it is AttachedText && it.content == attachedContext.content
        }

        is AttachedQuote -> session.attachedFiles.removeAll {
          it is AttachedQuote && it.content == attachedContext.content
        }

        is AttachedImage -> session.attachedFiles.removeAll {
          it is AttachedImage && it.id == attachedContext.id
        }
      }
    },
    onUploadImage = uploadImageCallback(toolWindow, session),
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
    onClearText = { session.textState.edit { delete(0, length) } },
    onRemovePending = { pending -> session.pendingMessages.remove(pending) },
  )
}

private fun uploadImageCallback(
  toolWindow: ToolWindow?,
  session: GradumChatSession
): () -> Unit = Unit@{
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
            is AttachedQuote -> false
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
  { userMessageIndex ->
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
}

@Composable
private fun rememberRetryMessageCallback(
  session: GradumChatSession,
  toolWindow: ToolWindow?,
  coroutineScope: CoroutineScope,
): (Int) -> Unit = remember(session, toolWindow, coroutineScope) {
  { assistantMessageIndex: Int ->
    val userMessageIndex = (assistantMessageIndex - 1 downTo 0)
      .firstOrNull { session.messages[it].isUserMessage }

    if (userMessageIndex != null) {
      val userMessage = session.messages[userMessageIndex]

      // Debug mode: re-read file from editor instead of sending to LLM
      if (session.selectedPermission == PermissionMode.DEBUG) {
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
            session.loadDebugMarkdown(content)
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
}

@Composable
private fun rememberSendCallback(
  toolWindow: ToolWindow?, session: GradumChatSession, coroutineScope: CoroutineScope
): () -> Unit = remember(session, toolWindow, coroutineScope) {
  {
    val rawText: String = session.textState.text.toString()
    val hasModel = session.selectedModel != null || session.isAutoSelected

    // Debug mode: load focused Markdown file directly without calling LLM
    if (session.selectedPermission == PermissionMode.DEBUG) {
      if (rawText.isNotBlank()) {
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
            session.messages.add(ChatMessage(role = "user", content = rawText))
            session.hasSentMessage = true
            session.loadDebugMarkdown(content)
          } catch (ioError: IOException) {
            logger.error("IO error reading markdown file: ${currentFile.name}", ioError)
          } catch (securityError: SecurityException) {
            logger.error("Security error accessing markdown file: ${currentFile.name}", securityError)
          } catch (generalError: Exception) {
            logger.warn("Unexpected error processing markdown file: ${currentFile.name}", generalError)
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
}

@Composable
private fun rememberStopCallback(
  session: GradumChatSession,
  coroutineScope: CoroutineScope,
): () -> Unit = remember(session, coroutineScope) {
  { coroutineScope.launch { session.stopSession() } }
}

@Composable
private fun rememberOpenInEditorCallback(
  toolWindow: ToolWindow?,
  coroutineScope: CoroutineScope,
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
      project = toolWindow?.project,
      path = filePath,
      originalContent = originalContent,
      modifiedContent = modifiedContent,
    )
  }
}

@Composable
private fun rememberAttachmentClickCallback(
  toolWindow: ToolWindow?,
): (VirtualFile) -> Unit = remember(toolWindow) {
  { file ->
    val project = toolWindow?.project
    if (project != null && file.isValid) {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
  }
}
