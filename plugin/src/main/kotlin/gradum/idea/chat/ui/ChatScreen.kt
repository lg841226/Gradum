/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatScreen.kt  2026-08-31 19:21:55 Changed by gwy
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
  state: ChatSessionState, modifier: Modifier = Modifier
) {
  var subChatTitle by remember { mutableStateOf(value = "") }
  var subChatActive by remember { mutableStateOf(value = false) }
  var subChatTranscriptMarkdown by remember { mutableStateOf(value = "") }

  val onSubChatClick: (String, String, String) -> Unit = { transcriptMarkdown, _, title ->
    subChatTranscriptMarkdown = transcriptMarkdown
    subChatTitle = title
    subChatActive = true
  }

  val onBackToMainChat: () -> Unit = {
    subChatActive = false
    subChatTitle = ""
    subChatTranscriptMarkdown = ""
  }

  if (subChatActive) {
    val elapsedSeconds = remember { mutableStateOf(value = 0) }
    LaunchedEffect(key1 = state.subAgentState.startTimestamp) {
      if (state.subAgentState.startTimestamp > 0L) {
        while (state.subAgentState.isActive) {
          elapsedSeconds.value =
            ((System.currentTimeMillis() - state.subAgentState.startTimestamp) / 1000).toInt()
          delay(duration = 1_000L.milliseconds)
        }
      }
    }

    SubChatView(
      modifier = modifier,
      onBack = onBackToMainChat,
      toolCalls = state.subAgentState.toolCalls,
      userQuery = state.subAgentState.userQuery,
      modelName = state.subAgentState.modelName,
      hasCompleted = !state.subAgentState.isActive,
      transcriptMarkdown = subChatTranscriptMarkdown,
      errorMessage = state.subAgentState.errorMessage,
      wasInterrupted = state.subAgentState.wasInterrupted,
      subAgentResponse = state.subAgentState.streamingResponse,
      title = subChatTitle.ifBlank { state.subAgentState.title }
    )
    return
  }

  val density = LocalDensity.current
  val scrollState = rememberScrollState()
  val coroutineScope = rememberCoroutineScope()
  val nearBottomThresholdPx: Float = with(receiver = density) { NearBottomThresholdDp.toPx() }

  val isNearBottom: Boolean by remember(key1 = scrollState) {
    derivedStateOf {
      val maxValue: Int = scrollState.maxValue
      maxValue == 0 || maxValue - scrollState.value <= nearBottomThresholdPx
    }
  }

  val isNearTop: Boolean by remember(key1 = scrollState) {
    derivedStateOf {
      scrollState.value <= nearBottomThresholdPx
    }
  }

  var wasAtBottom by remember { mutableStateOf(value = true) }
  LaunchedEffect(key1 = scrollState.value) {
    wasAtBottom = isNearBottom
  }

  var lastSeenMessageCount by remember { mutableIntStateOf(value = state.messages.size) }

  val messageLoadCount: Int = LocalMessageLoadCount.current
  val autoScrollToBottom: Boolean = LocalAutoScrollToBottom.current
  val messageLoadEnabled: Boolean = LocalMessageLoadEnabled.current

  val displayMessages: List<ChatMessage> =
    remember(key1 = state.messages, key2 = messageLoadEnabled, key3 = messageLoadCount) {
      if (messageLoadEnabled && state.messages.size > messageLoadCount)
        state.messages.takeLast(n = messageLoadCount)
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
        withFrameNanos {}
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
                    onViewDiff = state.onViewDiff,
                    onSubChatClick = onSubChatClick,
                    onOpenInEditor = state.onOpenInEditor,
                    selectedPermission = state.selectedPermission,
                    onRespondToAsk = state.onRespondToAsk,
                    dismissedAskRequestIds = state.dismissedAskRequestIds,
                    onDismissAsk = state.onDismissAsk
                  )
                }
              }
            }
          }
        }
      }

      val enableStickySections: Boolean = LocalEnableStickySections.current

      val activeSection: StickySectionEntry? =
        if (enableStickySections) {
          stickyRegistry.entries.firstOrNull { entry: StickySectionEntry ->
            scrollState.value >= entry.topInColumn && scrollState.value < entry.bottomInColumn
          }
        } else null

      if (enableStickySections) {
        StickyOverlay(
          activeSection = activeSection,
          sections = stickyRegistry.entries,
          modifier = Modifier.fillMaxWidth(),
          scrollOffset = scrollState.value.toFloat()
        )
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
      state = state.inputState,
      actions = state.inputActions,
      textState = state.textState,
      modifier = Modifier
        .padding(bottom = GradumSpacing.sml),
      hasSentMessage = state.hasSentMessage,
      selectedPermission = state.selectedPermission
    )
  }
}

/**
 * Renders sticky section toolbars that fade in/out as the user scrolls.
 *
 * Each [StickySectionEntry] registers its toolbar height via
 * [onGloballyPositioned][androidx.compose.ui.layout.onGloballyPositioned];
 * when the active section's remaining space shrinks below twice the toolbar
 * height the toolbar starts fading out, producing a smooth collapse effect.
 */
@Composable
private fun StickyOverlay(
  scrollOffset: Float,
  modifier: Modifier = Modifier,
  sections: List<StickySectionEntry>,
  activeSection: StickySectionEntry?
) {
  Box(modifier = modifier) {
    for (section: StickySectionEntry in sections) {
      val alpha: Float = sectionToolbarAlpha(
        section = section,
        scrollOffset = scrollOffset,
        isActive = section == activeSection
      )
      if (alpha > 0f) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(color = JewelTheme.globalColors.panelBackground)
            .onGloballyPositioned { coords ->
              section.toolbarHeight = coords.size.height.toFloat()
            }
            .graphicsLayer { this.alpha = alpha }
        ) {
          section.toolbar()
        }
      }
    }
  }
}

/**
 * Computes the alpha for a sticky section toolbar.
 *
 * - Inactive sections: `0f` (hidden).
 * - Active section with a measured toolbar: linearly fade from `1f` to `0f`
 *   as the remaining space shrinks from `toolbarHeight` to `0f`, eased
 *   with [FastOutSlowInEasing].
 * - Active section before first measurement (`toolbarHeight == 0`): fully
 *   opaque so the toolbar is visible immediately.
 */
private fun sectionToolbarAlpha(
  isActive: Boolean,
  scrollOffset: Float,
  section: StickySectionEntry
): Float {
  if (!isActive) return 0f
  val toolbarHeight: Float = section.toolbarHeight
  if (toolbarHeight <= 0f) return 1f
  val remaining: Float = section.bottomInColumn - scrollOffset
  val linear: Float = ((remaining - toolbarHeight) / (toolbarHeight * 0.5f)).coerceIn(0f, 1f)
  return FastOutSlowInEasing.transform(fraction = linear)
}
