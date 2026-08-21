/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-08-20 09:38:19 Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.core.FastOutSlowInEasing
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
import gradum.idea.chat.ui.chat.AssistantChatBubble
import gradum.idea.chat.ui.chat.MessageTimestamp
import gradum.idea.chat.ui.chat.UserChatBubble
import gradum.idea.chat.ui.input.ChatInputSection
import gradum.idea.chat.ui.markdown.FootnoteRegistry
import gradum.idea.chat.ui.markdown.LocalFootnoteRegistry
import gradum.idea.chat.ui.markdown.LocalStickySectionRegistry
import gradum.idea.chat.ui.markdown.StickySectionRegistry
import gradum.idea.settings.LocalAutoScrollToBottom
import gradum.idea.settings.LocalShowTimestamp
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.IOException
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/** Tolerance (dp) for "user is at the bottom". Hides jump-to-bottom button when within this range. */
private val NearBottomThresholdDp: androidx.compose.ui.unit.Dp = 256.dp

/** Gap (dp) left between the viewport top and a footnote definition after a jump. */
private val FootnoteScrollPadding: androidx.compose.ui.unit.Dp = GradumSpacing.xxl

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
  messages: List<ChatMessage>,
  sendingPhase: String,
  isLoading: Boolean,
  selectedPermission: String,
  isWaitingForResponse: Boolean,
  hasSentMessage: Boolean = false,
  textState: TextFieldState,
  inputState: ChatInputState,
  inputActions: ChatInputActions,
  onDeleteMessage: (Int) -> Unit,
  onRetryMessage: (Int) -> Unit,
  onCopyAsContext: (String) -> Unit,
  onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { _, _, _ -> },
  onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> },
  onAttachmentClick: (VirtualFile) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val scrollState = rememberScrollState()
  val density = LocalDensity.current
  val coroutineScope = rememberCoroutineScope()
  val nearBottomThresholdPx: Float = with(density) { NearBottomThresholdDp.toPx() }

  val lastMessage: ChatMessage? = messages.lastOrNull()
  val lastBlockCount: Int = lastMessage?.renderBlocks?.size ?: 0

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

  var lastSeenMessageCount by remember { mutableIntStateOf(messages.size) }

  val autoScrollToBottom: Boolean = LocalAutoScrollToBottom.current

  LaunchedEffect(messages.size, lastBlockCount, autoScrollToBottom) {
    if (!autoScrollToBottom) return@LaunchedEffect
    if (messages.size > lastSeenMessageCount) {
      val newMessages: List<ChatMessage> = messages.subList(lastSeenMessageCount, messages.size)
      lastSeenMessageCount = messages.size
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
        .weight(1f).widthIn(max = 680.dp)
    ) {
      val stickyRegistry = remember { StickySectionRegistry() }

      CompositionLocalProvider(LocalStickySectionRegistry provides stickyRegistry) {
        Column(
          modifier = Modifier
            .verticalScroll(scrollState)
            .onGloballyPositioned { stickyRegistry.columnOriginInWindow = it.localToWindow(Offset.Zero) }
        ) {
          messages.forEachIndexed { index, message ->
            val shouldShowTimestamp = index == 0 || formatTimestamp(message.timestamp) !=
              formatTimestamp(messages.getOrNull(index - 1)?.timestamp ?: 0L)
            val isLastAssistant = index == messages.lastIndex && !message.isUserMessage && isLoading

            if (LocalShowTimestamp.current && shouldShowTimestamp) {
              MessageTimestamp(
                timestamp = message.timestamp,
                modifier = Modifier.padding(vertical = GradumSpacing.lg)
              )
            }

            when {
              message.isUserMessage -> UserChatBubble(
                message = message,
                onDeleteMessage = { onDeleteMessage(index) },
                onCopyAsContext = onCopyAsContext,
                onAttachmentClick = onAttachmentClick
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
                    sendingPhase = if (isLastAssistant) sendingPhase else "",
                    onRetry = { onRetryMessage(index) },
                    onUrlClick = { url ->
                      try {
                        Desktop.getDesktop().browse(URI(url))
                      } catch (iOException: IOException) {
                        logger.warn("Failed to open URL: $url", iOException)
                      }
                    },
                    isLoading = isLastAssistant,
                    actionsEnabled = !isWaitingForResponse,
                    selectedPermission = selectedPermission,
                    onOpenInEditor = onOpenInEditor,
                    onViewDiff = onViewDiff,
                  )
                }
              }
            }
          }
        }
      }

      val activeSection = stickyRegistry.entries.firstOrNull { entry ->
        scrollState.value >= entry.topInColumn && scrollState.value < entry.bottomInColumn
      }
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
                .onGloballyPositioned { section.toolbarHeight = it.size.height.toFloat() }
                .graphicsLayer { this.alpha = alpha }
            ) {
              section.toolbar()
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
      state = inputState,
      textState = textState,
      actions = inputActions,
      selectedPermission = selectedPermission,
      hasSentMessage = hasSentMessage
    )
  }
}
