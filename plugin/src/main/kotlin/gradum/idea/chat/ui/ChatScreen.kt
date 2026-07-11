/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-11 10:15:42 Changed by gwy
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
  onViewDiff: (filePath: String, originalContent: String, modifiedContent: String)
  -> Unit = { _, _, _ -> },
  onAttachmentClick: (VirtualFile) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val scrollState = rememberScrollState()
  val density = LocalDensity.current

  val lastMessage: ChatMessage? = messages.lastOrNull()
  val lastBlockCount: Int = lastMessage?.renderBlocks?.size ?: 0

  // "Locked" = the chat auto-follows new content. Default true so
  // the moment the screen mounts, the user sees the latest messages
  // (and any subsequent streaming block). The user breaks the lock
  // by dragging up past [LockThresholdDp]; clicking the
  // jump-to-bottom button re-engages it.
  var isLockedToBottom by remember { mutableStateOf(true) }
  val lockThresholdPx: Float = with(density) { LockThresholdDp.toPx() }

  // The last `scrollState.value` we observed. `animateScrollTo` only
  // ever INCREASES `value`, so a strictly negative delta between
  // successive observations is unambiguous user drag-up — exactly
  // the signal we need to break the lock. Layout-driven `maxValue`
  // changes do not move `value`, so they cannot produce a false
  // positive.
  var lastObservedValue by remember { mutableIntStateOf(scrollState.value) }

  // Auto-scroll on new content while the lock is engaged.
  LaunchedEffect(messages.size, lastBlockCount, isLockedToBottom) {
    if (!isLockedToBottom) return@LaunchedEffect
    withFrameNanos { }
    scrollState.animateScrollTo(scrollState.maxValue)
  }

  // Detect user drag-up: any negative delta in `scrollState.value`
  // that exceeds the lock threshold flips the lock off.
  LaunchedEffect(scrollState.value) {
    val currentValue: Int = scrollState.value
    val delta: Int = currentValue - lastObservedValue
    lastObservedValue = currentValue

    if (isLockedToBottom && delta < -lockThresholdPx) {
      isLockedToBottom = false
    }
  }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .weight(1f)
        .width(680.dp)
        .fillMaxWidth()
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
        isVisible = !isLockedToBottom,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = GradumSpacing.lg),
        onClick = {
          // Re-engage the lock; the auto-scroll LaunchedEffect above
          // is keyed on `isLockedToBottom` and will animate the
          // scroll to the bottom on the next frame.
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
      actions = inputActions,
      textState = textState
    )
  }
}
