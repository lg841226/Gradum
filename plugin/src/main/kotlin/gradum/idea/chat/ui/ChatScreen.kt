/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-07-08 15:30:36 Changed by gwy
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
import java.net.URI

private val logger = Logger.getInstance("ChatScreen"::class.java)

/**
 * "At bottom" tolerance in dp. The jump-to-bottom button is hidden
 * whenever the user is within this many dp of the list's max scroll
 * value, so a tiny 1-px jitter from a fresh bubble layout doesn't
 * keep the button visible. 48 dp is generous — it lets the user
 * scroll the list a couple of paragraphs up before the button
 * shows, so a small "read the line just above" sweep doesn't pop
 * the pill in. With 24 dp the button felt like it was almost
 * always visible at the top of the input section whenever the
 * user was reading anything except the very last bubble; the wider
 * tolerance keeps the chat calm and reserves the button for the
 * "really scrolled away" case.
 */
private val AtBottomToleranceDp: androidx.compose.ui.unit.Dp = 48.dp

/**
 * The active conversation screen: scrollable history on top, input pinned
 * to the bottom. Shown after the user has sent at least one message.
 *
 * A [JumpToBottomButton] floats above the input section. It fades in
 * (150 ms) whenever the user is more than 24 dp away from the bottom
 * of the message list, and fades out (150 ms) once they return to the
 * bottom. When the user is already at the bottom, new messages AND
 * new streaming blocks auto-scroll into view — preserving the
 * read-the-latest flow without pulling focus away from older history
 * the user is reading.
 *
 * "New block" matters because a streaming assistant turn can grow by
 * adding new render blocks (e.g. a `<think>…</think>` block, then a
 * tool call, then the answer) without the message count changing. The
 * previous implementation only watched `messages.size` and therefore
 * stopped following the moment the LLM started emitting blocks into
 * an existing assistant message — the user would have to manually
 * click "jump to bottom" to keep up. The
 * [LaunchedEffect] below now watches both signals in a single
 * `(messageCount, lastBlockCount)` snapshot.
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
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current

  val lastMessage: ChatMessage? = messages.lastOrNull()
  val lastBlockCount: Int = lastMessage?.renderBlocks?.size ?: 0

  // The deepest position we've already auto-scrolled past. A Pair
  // rather than just a message count because streaming blocks land
  // on the *last* message and don't change `messages.size`. The
  // initial value mirrors the current state so a recompose on first
  // display never triggers a phantom scroll.
  val lastSeenSnapshot = remember {
    mutableStateOf(messages.size to lastBlockCount)
  }
  var unreadCount by remember { mutableIntStateOf(0) }
  var isJumpToBottomInFlight by remember { mutableStateOf(false) }

  val isAtBottom: Boolean by remember(scrollState, density) {
    derivedStateOf {
      val tolerancePx: Float = with(density) { AtBottomToleranceDp.toPx() }
      val maxValue: Int = scrollState.maxValue

      maxValue == 0 || scrollState.value >= maxValue - tolerancePx
    }
  }

  LaunchedEffect(messages.size, lastBlockCount) {
    val current: Pair<Int, Int> = messages.size to lastBlockCount
    if (current == lastSeenSnapshot.value) return@LaunchedEffect

    val (seenMsgCount, seenBlockCount) = lastSeenSnapshot.value
    val (currentMsgCount, currentBlockCount) = current

    when {
      currentMsgCount > seenMsgCount -> {
        // Whole message(s) appended. Same one-frame defer as the
        // block case below: `maxValue` only catches up after the
        // new bubble is laid out, so reading it before `withFrameNanos`
        // returns the *old* bottom and `animateScrollTo` lands short.
        val newMessages: List<ChatMessage> =
          messages.subList(seenMsgCount, currentMsgCount)
        val hasUserSend: Boolean = newMessages.any { it.isUserMessage }

        if (hasUserSend) {
          withFrameNanos { }
          scrollState.animateScrollTo(scrollState.maxValue)
        } else if (isAtBottom) {
          withFrameNanos { }
          scrollState.animateScrollTo(scrollState.maxValue)
        } else {
          unreadCount += currentMsgCount - seenMsgCount
        }
      }

      currentBlockCount > seenBlockCount -> {
        // New render block(s) appended to the streaming last message.
        // The most common shapes this catches:
        //   - thinking block finishes, response block starts
        //   - response block finishes, tool-call block starts
        //   - tool-call block finishes, response block resumes
        // In all three cases the previous code stopped following
        // because `messages.size` was unchanged.
        if (isAtBottom) {
          withFrameNanos { }
          scrollState.animateScrollTo(scrollState.maxValue)
        } else {
          unreadCount += currentBlockCount - seenBlockCount
        }
      }
      // The other two cases (`currentMsgCount < seenMsgCount` /
      // `currentBlockCount < seenBlockCount`) only happen when the
      // user retries a message and the session rebuilds the
      // messages list. The snapshot is reset by the very next
      // recompose, so we don't need a branch here.
    }
    lastSeenSnapshot.value = current
  }

  // When the user returns to the bottom (either manually, by
  // clicking the button, or by a new message/block arriving while
  // they were already there), reset the unread badge and snapshot
  // the current position so the next block doesn't re-fire the
  // "scroll to bottom" branch from a stale position.
  LaunchedEffect(isAtBottom) {
    if (isAtBottom) {
      unreadCount = 0
      lastSeenSnapshot.value = messages.size to lastBlockCount
    }
  }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // The message column is wrapped in a Box so the jump-to-bottom
    // button can overlay it without being measured into the
    // verticalScroll's content height. The button's
    // Alignment.BottomCenter anchor lives on THIS Box, not on
    // the outer Column — which is what keeps the button pinned
    // to the input section's top edge regardless of how tall the
    // input grows (multi-line text, attachment chips, model
    // selector row, etc.). A previous implementation anchored the
    // button to the screen's BottomCenter and offset it by a
    // hardcoded 160.dp, which broke the moment the input section
    // exceeded that budget — the pill ended up *inside* the
    // input box. The Box overlay pattern decouples the two.
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
        isVisible = !isAtBottom,
        enabled = !isJumpToBottomInFlight,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = GradumSpacing.lg),
        onClick = {
          if (isJumpToBottomInFlight) return@JumpToBottomButton
          isJumpToBottomInFlight = true
          scope.launch {
            try {
              scrollState.animateScrollTo(scrollState.maxValue)
            } finally {
              isJumpToBottomInFlight = false
            }
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
      actions = inputActions,
      textState = textState
    )
  }
}
