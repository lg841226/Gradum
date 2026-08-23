/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-08-23 21:06:53 Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.chat.state.SubAgentState
import gradum.idea.chat.ui.chat.AssistantChatBubble
import gradum.idea.chat.ui.chat.MessageTimestamp
import gradum.idea.chat.ui.chat.SubChatView
import gradum.idea.chat.ui.chat.UserChatBubble
import gradum.idea.chat.ui.input.ChatInputSection
import gradum.idea.chat.ui.markdown.FootnoteRegistry
import gradum.idea.chat.ui.markdown.LocalFootnoteRegistry
import gradum.idea.chat.ui.markdown.LocalStickySectionRegistry
import gradum.idea.chat.ui.markdown.StickySectionRegistry
import gradum.idea.settings.*
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

private val logger = Logger.getInstance("ChatScreen"::class.java)

/** Tolerance (dp) for "user is at the bottom". Hides jump-to-bottom button when within this range. */
private val NearBottomThresholdDp: androidx.compose.ui.unit.Dp = 256.dp

/** Gap (dp) left between the viewport top and a footnote definition after a jump. */
private val FootnoteScrollPadding: androidx.compose.ui.unit.Dp = GradumSpacing.xxl

/** State and data parameters for [ChatScreen]. */
data class ChatScreenParams(
  val isLoading: Boolean,
  val sendingPhase: String,
  val selectedPermission: String,
  val messages: List<ChatMessage>,
  val textState: TextFieldState,
  val inputState: ChatInputState,
  val subAgentState: SubAgentState,
  val isWaitingForResponse: Boolean,
  val hasSentMessage: Boolean = false,
  val inputActions: ChatInputActions
)

/** Callback lambdas for [ChatScreen]. */
data class ChatScreenCallbacks(
  val onDeleteMessage: (Int) -> Unit,
  val onRetryMessage: (Int) -> Unit,
  val onCopyAsContext: (String) -> Unit,
  val onAttachmentClick: (VirtualFile) -> Unit = {},
  val onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { _, _, _ -> },
  val onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> }
)

/**
 * Active conversation: scrollable history + input pinned to bottom.
 *
 * Auto-scrolls to the bottom when new content arrives and the user was at
 * the bottom; otherwise a `JumpToBottomButton` appears. The scroll decision
 * uses a `wasAtBottom` snapshot taken at the user's last scroll — reading
 * `scrollState.value` live would flip to "not at bottom" while `maxValue`
 * grows during the layout pass. New user messages always force-scroll.
 */
@Composable
fun ChatScreen(
  params: ChatScreenParams, callbacks: ChatScreenCallbacks, modifier: Modifier = Modifier
) {
  var subChatActive by remember { mutableStateOf(false) }
  var subChatTranscriptMarkdown by remember { mutableStateOf("") }
  var subChatTitle by remember { mutableStateOf("") }

  val onSubChatClick: (String, String, String) -> Unit = { transcriptMarkdown, _, title ->
    subChatTranscriptMarkdown = transcriptMarkdown
    subChatTitle = title
    subChatActive = true
  }

  val onBackToMainChat: () -> Unit = {
    subChatActive = false
    subChatTranscriptMarkdown = ""
    subChatTitle = ""
  }

  if (subChatActive) {
    // Elapsed time timer for the sub-agent view.
    val elapsedSeconds = remember { mutableStateOf(0) }
    LaunchedEffect(params.subAgentState.startTimestamp) {
      if (params.subAgentState.startTimestamp > 0L) {
        while (params.subAgentState.isActive) {
          elapsedSeconds.value =
            ((System.currentTimeMillis() - params.subAgentState.startTimestamp) / 1000).toInt()
          delay(1_000L.milliseconds)
        }
      }
    }

    SubChatView(
      modifier = modifier,
      onBack = onBackToMainChat,
      modelName = params.subAgentState.modelName,
      userQuery = params.subAgentState.userQuery,
      toolCalls = params.subAgentState.toolCalls,
      hasCompleted = !params.subAgentState.isActive,
      transcriptMarkdown = subChatTranscriptMarkdown,
      errorMessage = params.subAgentState.errorMessage,
      subAgentResponse = params.subAgentState.streamingResponse,
      title = subChatTitle.ifBlank { params.subAgentState.title }
    )
    return
  }

  val density = LocalDensity.current
  val scrollState = rememberScrollState()
  val coroutineScope = rememberCoroutineScope()
  val nearBottomThresholdPx: Float = with(density) { NearBottomThresholdDp.toPx() }

  val isNearBottom: Boolean by remember(scrollState) {
    derivedStateOf {
      val maxValue: Int = scrollState.maxValue
      maxValue == 0 || maxValue - scrollState.value <= nearBottomThresholdPx
    }
  }

  val isNearTop: Boolean by remember(scrollState) {
    derivedStateOf {
      scrollState.value <= nearBottomThresholdPx
    }
  }

  var wasAtBottom by remember { mutableStateOf(true) }
  LaunchedEffect(scrollState.value) {
    wasAtBottom = isNearBottom
  }

  var lastSeenMessageCount by remember { mutableIntStateOf(params.messages.size) }

  val autoScrollToBottom: Boolean = LocalAutoScrollToBottom.current
  val messageLoadEnabled: Boolean = LocalMessageLoadEnabled.current
  val messageLoadCount: Int = LocalMessageLoadCount.current

  val displayMessages: List<ChatMessage> = remember(params.messages, messageLoadEnabled, messageLoadCount) {
    if (messageLoadEnabled && params.messages.size > messageLoadCount) params.messages.takeLast(messageLoadCount)
    else params.messages
  }

  val lastMessage: ChatMessage? = displayMessages.lastOrNull()
  val lastBlockCount: Int = lastMessage?.renderBlocks?.size ?: 0

  LaunchedEffect(params.messages.size, lastBlockCount, autoScrollToBottom) {
    if (!autoScrollToBottom) return@LaunchedEffect
    if (params.messages.size > lastSeenMessageCount) {
      val newMessages: List<ChatMessage> = params.messages.subList(lastSeenMessageCount, params.messages.size)
      lastSeenMessageCount = params.messages.size
      if (newMessages.any { it.isUserMessage }) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
        return@LaunchedEffect
      }
    }
    if (wasAtBottom) {
      withFrameNanos {}
      scrollState.animateScrollTo(scrollState.maxValue)
    }
  }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .weight(1f)
        .widthIn(max = 680.dp)
    ) {
      val stickyRegistry = remember { StickySectionRegistry() }

      CompositionLocalProvider(LocalStickySectionRegistry provides stickyRegistry) {
        Column(
          modifier = Modifier
            .verticalScroll(scrollState)
            .onGloballyPositioned { stickyRegistry.columnOriginInWindow = it.localToWindow(Offset.Zero) }
        ) {
          displayMessages.forEachIndexed { index, message ->
            val shouldShowTimestamp = index == 0 || formatTimestamp(message.timestamp) !=
              formatTimestamp(displayMessages.getOrNull(index - 1)?.timestamp ?: 0L)
            val isLastAssistant = index == displayMessages.lastIndex && !message.isUserMessage && params.isLoading

            if (LocalShowTimestamp.current && shouldShowTimestamp) {
              MessageTimestamp(
                timestamp = message.timestamp,
                modifier = Modifier.padding(vertical = GradumSpacing.lg)
              )
            }

            when {
              message.isUserMessage -> UserChatBubble(
                message = message,
                onDeleteMessage = { callbacks.onDeleteMessage(index) },
                onCopyAsContext = callbacks.onCopyAsContext,
                onAttachmentClick = callbacks.onAttachmentClick
              )

              else -> {
                val footnoteRegistry: FootnoteRegistry = remember(message) {
                  FootnoteRegistry(
                    getColumnOrigin = { stickyRegistry.columnOriginInWindow },
                    getCurrentScrollOffset = { scrollState.value.toFloat() },
                  ).also { registry ->
                    registry.scrollToPosition = { position, label ->
                      coroutineScope.launch {
                        val paddingPx: Float = with(density) { FootnoteScrollPadding.toPx() }
                        scrollState.animateScrollTo(
                          (position - paddingPx).toInt().coerceAtLeast(0)
                        )
                        registry.onJumpComplete(label)
                      }
                    }
                  }
                }
                CompositionLocalProvider(LocalFootnoteRegistry provides footnoteRegistry) {
                  AssistantChatBubble(
                    message = message,
                    sendingPhase = if (isLastAssistant) params.sendingPhase else "",
                    onRetry = { callbacks.onRetryMessage(index) },
                    onUrlClick = { url ->
                      try {
                        Desktop.getDesktop().browse(URI(url))
                      } catch (iOException: IOException) {
                        logger.warn("Failed to open URL: $url", iOException)
                      }
                    },
                    isLoading = isLastAssistant,
                    onSubChatClick = onSubChatClick,
                    onViewDiff = callbacks.onViewDiff,
                    onOpenInEditor = callbacks.onOpenInEditor,
                    actionsEnabled = !params.isWaitingForResponse,
                    selectedPermission = params.selectedPermission
                  )
                }
              }
            }
          }
        }
      }

      val enableStickySections: Boolean = LocalEnableStickySections.current

      val activeSection = if (enableStickySections) {
        stickyRegistry.entries.firstOrNull { entry ->
          scrollState.value >= entry.topInColumn && scrollState.value < entry.bottomInColumn
        }
      } else null
      if (enableStickySections) {
        Box(modifier = Modifier.fillMaxWidth()) {
          stickyRegistry.entries.forEach { section ->
            val isActive = section == activeSection
            val remaining = section.bottomInColumn - scrollState.value
            val toolbarHeight = section.toolbarHeight
            val alpha = if (isActive && toolbarHeight > 0f) {
              val linear = ((remaining - toolbarHeight) / (toolbarHeight * 0.5f)).coerceIn(0f, 1f)
              FastOutSlowInEasing.transform(linear)
            } else if (isActive) 1f else 0f
            if (alpha > 0f) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(JewelTheme.globalColors.panelBackground)
                  .onGloballyPositioned { section.toolbarHeight = it.size.height.toFloat() }
                  .graphicsLayer { this.alpha = alpha }
              ) {
                section.toolbar()
              }
            }
          }
        }
      }

      JumpToBottomButton(
        isAtTop = isNearTop,
        isAtBottom = isNearBottom,
        onClick = {
          coroutineScope.launch {
            scrollState.animateScrollTo(scrollState.maxValue)
          }
        },
        {
          coroutineScope.launch {
            scrollState.animateScrollTo(0)
          }
        },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = GradumSpacing.lg)
      )
    }

    ChatInputSection(
      modifier = Modifier
        .padding(bottom = GradumSpacing.sml),
      state = params.inputState,
      textState = params.textState,
      actions = params.inputActions,
      hasSentMessage = params.hasSentMessage,
      selectedPermission = params.selectedPermission
    )
  }
}
