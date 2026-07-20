/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-20 16:39:31 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

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
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import java.awt.Desktop
import java.io.IOException
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/**
 * Tolerance (in dp) for "the user is at the bottom of the chat". The
 * jump-to-bottom button stays hidden while the user is within this
 * distance of the end; once they cross it, the button appears. Set
 * deliberately to 64 dp (~2.5 lines of body text) so a small read-up
 * gesture — a quick glance at the previous bubble — does not
 * accidentally trigger the button, while a clear "I want to read
 * history" scroll does.
 */
private val NearBottomThresholdDp: androidx.compose.ui.unit.Dp = 64.dp

/**
 * The active conversation screen: scrollable history on top, input
 * pinned to the bottom. Shown after the user has sent at least one
 * message.
 *
 * ## Jump-to-bottom button
 *
 * A [JumpToBottomButton] floats above the input section, anchored
 * to the message column's bottom edge. It fades in when the user is
 * more than [NearBottomThresholdDp] (64 dp) away from the end of the
 * chat, and fades out as soon as the user returns. The 64 dp
 * tolerance prevents the button from flickering on tiny overscroll
 * at the very end of the chat.
 *
 * ### Show / hide rules (mode-aware, position-based)
 *
 * The pill's current action depends on the Option-toggled mode, so
 * the hide rule depends on the mode:
 *  - **Default mode** ("Jump to latest"): user is within 64 dp of
 *    the end of the chat → **hidden**. Visible when the user has
 *    scrolled up past the tolerance.
 *  - **Alternative mode** ("Jump to top"): user is within 64 dp
 *    of the start of the chat → **hidden**. Visible when the user
 *    has scrolled down past the tolerance.
 *  - **Initial state at startup** → **hidden** in default mode
 *    (the chat auto-scrolls to the end on first composition, and
 *    once the user is at the end the button stays hidden).
 *
 * Mode toggling happens on Option-key rising edges captured by
 * [LocalWindowInfo.keyboardModifiers]; see [JumpToBottomButton].
 *
 * The "near the bottom" / "near the top" checks are
 * `derivedStateOf` of the live `scrollState.value` vs.
 * `scrollState.maxValue` — see [isNearBottom] / [isNearTop]
 * below.
 *
 * ### Auto-scroll strategy — "force scroll to bottom" wins
 *
 * When new content lands (a new message, or a new render block
 * appended to the streaming last message), the chat follows it with
 * a smooth `animateScrollTo(maxValue)` as long as the user was near
 * the bottom when the content arrived. If the user has scrolled up
 * past the 64 dp tolerance, new content does **not** yank them
 * back — the button appears instead, and clicking it returns them
 * to the bottom. This is the "force scroll to bottom" priority:
 * the chat is "locked" to the bottom by default, the user has to
 * actively scroll up to break out, and the button is the exit ramp
 * back to the bottom.
 *
 * ### Why a `wasAtBottom` snapshot, not the live `isNearBottom`
 *
 * Reading the live `scrollState.value` vs. `scrollState.maxValue`
 * inside the auto-scroll effect would flip to "not at bottom" the
 * instant new content grows `maxValue` in the layout pass that
 * precedes the effect, even when the user was originally at the
 * bottom. The effect instead reads [wasAtBottom], a snapshot of
 * [isNearBottom] taken at the moment of the user's last scroll
 * (i.e. whenever `scrollState.value` changes). The snapshot is
 * updated only when the user actually moves, not when content
 * grows, so it preserves the user's pre-content position.
 *
 * ### Fresh-user-message exception
 *
 * A new user message always force-scrolls to the bottom, regardless
 * of where the user was reading. Hitting Enter is unambiguous "I
 * want to see this conversation from now on" intent, and the
 * alternative (showing the just-sent message in place, then jumping
 * back) is much more disorienting.
 *
 * ## Layout
 *
 * The message column and the input section are siblings inside a
 * vertical Column. The messages column is wrapped in a Box that
 * hosts the floating jump-to-bottom button as an overlay. The
 * button's `Alignment.BottomCenter` anchor lives on that Box, so
 * the button always sits right above the input section regardless
 * of how tall the input grows (multi-line input, attachment chips,
 * model selector row, etc.). A small `GradumSpacing.lg` (12 dp)
 * bottom padding gives the pill breathing room from the input top
 * edge.
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
    withFrameNanos { }
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
