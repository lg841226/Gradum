/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.IOException
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/** Tolerance (dp) for "user is at the bottom". Hides jump-to-bottom button when within this range. */
private val NearBottomThresholdDp: androidx.compose.ui.unit.Dp = 64.dp

/**
 * Active conversation: scrollable history + input pinned to bottom.
 *
 * ## Jump-to-bottom button
 * A `JumpToBottomButton` floats above the input, fading in when the user scrolls past
 * `NearBottomThresholdDp` and fading out on return. 64dp tolerance prevents flicker.
 *
 * ### Show/hide rules
 * - **Default** ("Jump to latest"): hidden when within 64dp of bottom; visible otherwise.
 * - **Alt** ("Jump to top"): hidden when within 64dp of top; visible otherwise.
 * - **Startup**: hidden (auto-scroll to bottom on first render).
 *
 * Toggling modes via Option key (see `JumpToBottomButton`; captured via `LocalWindowInfo.keyboardModifiers`).
 * "Near bottom/top" checks are `derivedStateOf` — see `isNearBottom` / `isNearTop`.
 *
 * ### Auto-scroll
 * `animateScrollTo(maxValue)` fires when new content arrives **and** the user was near bottom
 * at the moment of arrival. If the user scrolled up, content doesn't yank them back — the
 * button appears instead. This is "force scroll to bottom" priority: locked by default, break
 * out by scrolling up, return via button.
 *
 * ### Why `wasAtBottom` snapshot (not live `isNearBottom`)
 * Reading `scrollState.value` inside the effect would flip to "not at bottom" when `maxValue`
 * grows during the layout pass preceding the effect, even if the user was at the bottom.
 * The effect reads `wasAtBottom`, a snapshot of `isNearBottom` taken at the user's last scroll.
 *
 * ### Fresh-user-message exception
 * New user messages always force-scroll to bottom regardless of position. Hitting Enter is
 * unambiguous "I want to see the conversation from now" intent.
 *
 * ## Layout
 * Messages Column + input section are siblings. The button sits in a Box overlay above the input,
 * anchored `BottomCenter` with `GradumSpacing.lg` bottom padding.
 */
@Composable
fun ChatScreen(
  messages: List<ChatMessage>,
  isLoading: Boolean,
  isWaitingForResponse: Boolean,
  sendingPhase: String,
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

  LaunchedEffect(messages.size, lastBlockCount) {
    if (messages.size > lastSeenMessageCount) {
      val newMessages: List<ChatMessage> = messages.subList(lastSeenMessageCount, messages.size)
      lastSeenMessageCount = messages.size
      if (newMessages.any { it.isUserMessage }) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
        return@LaunchedEffect
      }
    }
    if (!wasAtBottom) return@LaunchedEffect
    withFrameNanos {}
    scrollState.animateScrollTo(scrollState.maxValue)
  }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .widthIn(max = 680.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(scrollState)
      ) {
        messages.forEachIndexed { index, message ->
          val shouldShowTimestamp = index == 0 || formatTimestamp(message.timestamp) !=
            formatTimestamp(messages.getOrNull(index - 1)?.timestamp ?: 0L)
          val isLastAssistant = index == messages.lastIndex && !message.isUserMessage && isLoading

          if (shouldShowTimestamp) {
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

            else -> AssistantChatBubble(
              message = message,
              onViewDiff = onViewDiff,
              isLoading = isLastAssistant,
              onOpenInEditor = onOpenInEditor,
              actionsEnabled = !isWaitingForResponse,
              sendingPhase = if (isLastAssistant) sendingPhase else "",
              onRetry = { onRetryMessage(index) },
              onUrlClick = { url ->
                try {
                  Desktop.getDesktop().browse(URI(url))
                } catch (iOException: IOException) {
                  logger.warn("Failed to open URL: $url", iOException)
                }
              },
            )
          }
        }
      }

      JumpToBottomButton(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = GradumSpacing.lg),
        isAtBottom = isNearBottom,
        isAtTop = isNearTop,
        onClick = {
          coroutineScope.launch {
            scrollState.animateScrollTo(scrollState.maxValue)
          }
        },
        onJumpToTop = {
          coroutineScope.launch {
            scrollState.animateScrollTo(0)
          }
        }
      )
    }

    ChatInputSection(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 650.dp)
        .padding(bottom = GradumSpacing.sml),
      state = inputState,
      textState = textState,
      actions = inputActions
    )
  }
}
