/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-12 18:41:13 Changed by gwy
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
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import java.awt.Desktop
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/**
 * Drag distance (in dp) the user has to pull up before the chat
 * "unlocks" from the bottom. Set deliberately high (64 dp ≈ 2.5
 * lines of body text) so a small read-up gesture — a quick glance
 * at the previous bubble — does not accidentally break the lock,
 * while a clear "I want to read history" pull does.
 */
private val LockThresholdDp: androidx.compose.ui.unit.Dp = 64.dp

/**
 * The active conversation screen: scrollable history on top, input pinned
 * to the bottom. Shown after the user has sent at least one message.
 *
 * A [JumpToBottomButton] floats above the input section. It fades in
 * (150 ms) whenever the chat is in the *unlocked* state — i.e. the
 * user has actively pulled away from the bottom — and fades out
 * (150 ms) once the user re-engages the lock.
 *
 * Auto-scroll strategy — the chat is "locked" to the bottom by
 * default. Any new content (a new message, or a new render block
 * appended to the streaming last message) is followed automatically
 * with a smooth `animateScrollTo(scrollState.maxValue)`. The user
 * breaks the lock by dragging up by more than [LockThresholdDp]
 * (64 dp); from that point on, the chat stops following new content
 * and the jump-to-bottom button shows. Clicking the button re-engages
 * the lock and scrolls to the bottom on the next frame.
 *
 * One exception to the lock — a fresh user message always re-engages
 * the lock and forces the scroll to the bottom, even if the user had
 * been 50 bubbles up reading history. Hitting Enter is unambiguous
 * "I want to see this conversation from now on" intent, and the
 * alternative (jumping back to the user's just-sent message, then
 * back to wherever they were reading) is much more disorienting.
 *
 * Why "always scroll" instead of "only when at bottom":
 *   the at-bottom check (`scrollState.value >= maxValue - tolerancePx`)
 *   is observed AFTER the new content is in the layout, at which
 *   point `maxValue` has already grown and `value` has not. Even
 *   though the user did not actively scroll, the formula reads
 *   "not at bottom" and the chat would refuse to follow. Tracking
 *   the user's drag direction via the `scrollState.value` delta
 *   sidesteps this race entirely — `animateScrollTo` only ever
 *   increases `value`, so a strictly negative delta is unambiguous
 *   user intent to leave the lock.
 *
 * Layout note — the message column and the input section are siblings
 * inside a vertical Column. The messages column is wrapped in a Box
 * that hosts the floating jump-to-bottom button as an overlay. The
 * button's `Alignment.BottomCenter` anchor lives on that Box, so the
 * button always sits right above the input section regardless of how
 * tall the input grows (multi-line input, attachment chips, model
 * selector row, etc.). A small `GradumSpacing.lg` (12 dp) bottom
 * padding gives the pill breathing room from the input top edge.
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

  val lastMessage: ChatMessage? = messages.lastOrNull()
  val lastBlockCount: Int = lastMessage?.renderBlocks?.size ?: 0

  var isLockedToBottom by remember { mutableStateOf(true) }
  var lastObservedValue by remember { mutableIntStateOf(scrollState.value) }
  var lastSeenMessageCount by remember { mutableIntStateOf(messages.size) }

  val lockThresholdPx: Float = with(density) { LockThresholdDp.toPx() }

  LaunchedEffect(messages.size, lastBlockCount, isLockedToBottom) {
    if (messages.size > lastSeenMessageCount) {
      val newMessages: List<ChatMessage> =
        messages.subList(lastSeenMessageCount, messages.size)
      if (newMessages.any { it.isUserMessage })
        isLockedToBottom = true
    }

    lastSeenMessageCount = messages.size

    if (!isLockedToBottom) return@LaunchedEffect

    withFrameNanos { }
    scrollState.animateScrollTo(scrollState.maxValue)
  }

  LaunchedEffect(scrollState.value) {
    if (!isLockedToBottom) return@LaunchedEffect
    val maxValue: Int = scrollState.maxValue
    if (scrollState.value < lastObservedValue && maxValue > 0 &&
      maxValue - scrollState.value > lockThresholdPx
    ) {
      isLockedToBottom = false
    }
    lastObservedValue = scrollState.value
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
                } catch (exception: Exception) {
                  logger.warn("Failed to open URL: $url", exception)
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
        isVisible = !isLockedToBottom,
        onClick = {
          isLockedToBottom = true
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
