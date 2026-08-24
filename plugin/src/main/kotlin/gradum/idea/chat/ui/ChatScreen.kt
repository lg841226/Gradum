/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-08-24 23:20:59 Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.chat.state.ChatSessionState
import gradum.idea.chat.ui.chat.AssistantChatBubble
import gradum.idea.chat.ui.chat.MessageTimestamp
import gradum.idea.chat.ui.chat.SubChatView
import gradum.idea.chat.ui.chat.UserChatBubble
import gradum.idea.chat.ui.input.ChatInputSection
import gradum.idea.chat.ui.markdown.*
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

private val NearBottomThresholdDp: androidx.compose.ui.unit.Dp = 256.dp
private val FootnoteScrollPadding: androidx.compose.ui.unit.Dp = GradumSpacing.xxl

/**
 * Active conversation: scrollable history + input pinned to bottom.
 *
 * Auto-scrolls to the bottom when new content arrives and the user was at
 * the bottom; otherwise a `JumpToBottomButton` appears.
 */
@Composable
fun ChatScreen(
  state: ChatSessionState,
  modifier: Modifier = Modifier
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
    val elapsedSeconds = remember { mutableStateOf(0) }
    LaunchedEffect(state.subAgentState.startTimestamp) {
      if (state.subAgentState.startTimestamp > 0L) {
        while (state.subAgentState.isActive) {
          elapsedSeconds.value =
            ((System.currentTimeMillis() - state.subAgentState.startTimestamp) / 1000).toInt()
          delay(1_000L.milliseconds)
        }
      }
    }

    SubChatView(
      onBack = onBackToMainChat,
      title = subChatTitle.ifBlank { state.subAgentState.title },
      modelName = state.subAgentState.modelName,
      userQuery = state.subAgentState.userQuery,
      errorMessage = state.subAgentState.errorMessage,
      modifier = modifier,
      hasCompleted = !state.subAgentState.isActive,
      wasInterrupted = state.subAgentState.wasInterrupted,
      subAgentResponse = state.subAgentState.streamingResponse,
      transcriptMarkdown = subChatTranscriptMarkdown,
      toolCalls = state.subAgentState.toolCalls
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

  var lastSeenMessageCount by remember { mutableIntStateOf(state.messages.size) }

  val autoScrollToBottom: Boolean = LocalAutoScrollToBottom.current
  val messageLoadEnabled: Boolean = LocalMessageLoadEnabled.current
  val messageLoadCount: Int = LocalMessageLoadCount.current

  val displayMessages: List<ChatMessage> = remember(state.messages, messageLoadEnabled, messageLoadCount) {
    if (messageLoadEnabled && state.messages.size > messageLoadCount) state.messages.takeLast(messageLoadCount)
    else state.messages
  }

  val lastMessage: ChatMessage? = displayMessages.lastOrNull()
  val lastBlockCount: Int = lastMessage?.renderBlocks?.size ?: 0

  LaunchedEffect(key1 = state.messages.size, key2 = lastBlockCount, key3 = autoScrollToBottom) {
    if (!autoScrollToBottom) return@LaunchedEffect
    if (state.messages.size > lastSeenMessageCount) {
      val newMessages: List<ChatMessage> = state.messages.subList(lastSeenMessageCount, state.messages.size)
      lastSeenMessageCount = state.messages.size
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
      val stickyRegistry: StickySectionRegistry = remember { StickySectionRegistry() }

      CompositionLocalProvider(value = LocalStickySectionRegistry provides stickyRegistry) {
        Column(
          modifier = Modifier
            .verticalScroll(scrollState)
            .onGloballyPositioned {
              stickyRegistry.columnOriginInWindow = it.localToWindow(relativeToLocal = Offset.Zero)
            }
        ) {
          displayMessages.forEachIndexed { index: Int, message: ChatMessage ->
            val shouldShowTimestamp: Boolean = index == 0 || formatTimestamp(message.timestamp) !=
              formatTimestamp(displayMessages.getOrNull(index - 1)?.timestamp ?: 0L)
            val isLastAssistant: Boolean =
              index == displayMessages.lastIndex && !message.isUserMessage && state.isLoading

            if (LocalShowTimestamp.current && shouldShowTimestamp) {
              MessageTimestamp(
                timestamp = message.timestamp,
                modifier = Modifier.padding(vertical = GradumSpacing.lg)
              )
            }

            when {
              message.isUserMessage -> UserChatBubble(
                message = message,
                onCopyAsContext = state.onCopyAsContext,
                onAttachmentClick = state.onAttachmentClick,
                onDeleteMessage = { state.onDeleteMessage(index) }
              )

              else -> {
                val footnoteRegistry: FootnoteRegistry = remember(key1 = message) {
                  FootnoteRegistry(
                    getColumnOrigin = { stickyRegistry.columnOriginInWindow },
                    getCurrentScrollOffset = { scrollState.value.toFloat() },
                  ).also { registry: FootnoteRegistry ->
                    registry.scrollToPosition = { position: Float, label: String ->
                      coroutineScope.launch {
                        val paddingPx: Float = with(receiver = density) { FootnoteScrollPadding.toPx() }
                        scrollState.animateScrollTo(
                          value = (position - paddingPx).toInt().coerceAtLeast(minimumValue = 0)
                        )
                        registry.onJumpComplete(label)
                      }
                    }
                  }
                }
                CompositionLocalProvider(value = LocalFootnoteRegistry provides footnoteRegistry) {
                  AssistantChatBubble(
                    message = message,
                    sendingPhase =
                      if (index == displayMessages.lastIndex && !message.isUserMessage &&
                        (state.isLoading || state.sendingPhase.isNotBlank())
                      ) state.sendingPhase
                      else "",
                    onRetry = { state.onRetryMessage(index) },
                    isLoading = isLastAssistant,
                    actionsEnabled = !state.isWaitingForResponse,
                    onUrlClick = { url: String ->
                      try {
                        Desktop.getDesktop().browse(URI(url))
                      } catch (iOException: IOException) {
                        logger.warn("Failed to open URL: $url", iOException)
                      }
                    },
                    selectedPermission = state.selectedPermission,
                    onOpenInEditor = state.onOpenInEditor,
                    onViewDiff = state.onViewDiff,
                    onSubChatClick = onSubChatClick
                  )
                }
              }
            }
          }
        }
      }

      val enableStickySections: Boolean = LocalEnableStickySections.current

      val activeSection = if (enableStickySections) {
        stickyRegistry.entries.firstOrNull { entry: StickySectionEntry ->
          scrollState.value >= entry.topInColumn && scrollState.value < entry.bottomInColumn
        }
      } else null
      if (enableStickySections) {
        Box(modifier = Modifier.fillMaxWidth()) {
          stickyRegistry.entries.forEach { section: StickySectionEntry ->
            val isActive: Boolean = section == activeSection
            val remaining: Float = section.bottomInColumn - scrollState.value
            val toolbarHeight: Float = section.toolbarHeight
            val alpha: Float = if (isActive && toolbarHeight > 0f) {
              val linear: Float = ((remaining - toolbarHeight) / (toolbarHeight * 0.5f)).coerceIn(0f, 1f)
              FastOutSlowInEasing.transform(fraction = linear)
            } else if (isActive) 1f else 0f
            if (alpha > 0f) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(color = JewelTheme.globalColors.panelBackground)
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
        onJumpToTop = {
          coroutineScope.launch {
            scrollState.animateScrollTo(value = 0)
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
      state = state.inputState,
      textState = state.textState,
      actions = state.inputActions,
      hasSentMessage = state.hasSentMessage,
      selectedPermission = state.selectedPermission
    )
  }
}
