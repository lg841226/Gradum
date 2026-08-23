/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SubChatView.kt  2026-08-23 21:19:51 Changed by gwy
 */

package gradum.idea.chat.ui.chat


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.history.ChatTranscript
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.chat.ui.markdown.GradumMarkdown
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.settings.LocalShowModelName
import gradum.idea.settings.LocalShowTimestamp
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private val logger: Logger = Logger.getInstance("#gradum.idea.chat.ui.chat.SubChatView")

/**
 * Read-only sub-chat view that displays the sub-agent's full
 * conversation. Rendered in place of the main chat panel when
 * the user clicks on a delegate capsule.
 *
 * Layout mirrors the main [gradum.idea.chat.ui.ChatScreen] — centered message list
 * with a max width of 680dp, timestamps, and the same bubble rendering —
 * but without an input area.
 *
 * A back button at the top-left returns to the main conversation.
 * When the sub-agent is still running, the accumulated streaming
 * response text is shown instead of the empty conversation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubChatView(
  onBack: () -> Unit,
  title: String = "",
  modelName: String = "",
  userQuery: String = "",
  errorMessage: String = "",
  modifier: Modifier = Modifier,
  subAgentResponse: String = "",
  transcriptMarkdown: String = "",
  toolCalls: List<ToolCallInfo> = emptyList(),
  hasCompleted: Boolean = false
) {
  val historyMessages: List<ChatMessage> = remember(transcriptMarkdown) {
    if (transcriptMarkdown.isNotBlank()) {
      try {
        ChatTranscript.parseTranscript(transcriptMarkdown).messages
      } catch (parseException: Exception) {
        logger.warn("Failed to parse sub-agent transcript", parseException)
        emptyList()
      }
    } else emptyList()
  }
  val scrollState = rememberScrollState()

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 680.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      BackButton(onBack = onBack)
      if (title.isNotBlank()) {
        Spacer(Modifier.weight(1f))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
          if (hasCompleted) {
            Icon(
              contentDescription = null,
              key = AllIconsKeys.Actions.Checked
            )
          }
          val truncatedTitle =
            if (title.length > 30) title.take(30) + "…"
            else title
          val showTooltip = title.length > 30
          val titleContent = @Composable {
            Text(
              maxLines = 1,
              text = truncatedTitle,
              overflow = TextOverflow.Ellipsis,
              style = rememberGradumParagraphTextStyle().copy(
                fontWeight = FontWeight.Medium
              ),
              modifier = Modifier.padding(end = GradumSpacing.sml)
            )
          }
          if (showTooltip) {
            Tooltip(
              modifier = Modifier,
              tooltip = { Text(text = title) }
            ) {
              titleContent()
            }
          } else titleContent()
        }
      }
    }

    Box(
      modifier = Modifier
        .weight(1f)
        .widthIn(max = 680.dp)
    ) {
      if (historyMessages.isNotEmpty()) {
        SubChatConversationContent(
          messages = historyMessages,
          scrollState = scrollState
        )
      } else {
        SubChatStreamingContent(
          modelName = modelName,
          userQuery = userQuery,
          errorMessage = errorMessage,
          subAgentResponse = subAgentResponse,
          scrollState = scrollState,
          toolCalls = toolCalls
        )
      }
    }
  }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(vertical = GradumSpacing.md),
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
  ) {
    IconButton(onClick = onBack) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.Actions.Exit
      )
    }
    Text(
      text = message("gradum.subchat.back"),
      style = rememberGradumParagraphTextStyle()
    )
  }
}

@Composable
private fun SubChatStreamingContent(
  modelName: String,
  userQuery: String,
  errorMessage: String,
  subAgentResponse: String,
  scrollState: ScrollState,
  toolCalls: List<ToolCallInfo>
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState)
  ) {
    if (userQuery.isNotBlank()) {
      UserChatBubble(
        message = ChatMessage(role = "user", content = userQuery),
        showActions = false
      )
      Spacer(Modifier.height(GradumSpacing.xxl))
    }

    ModelNameHeader(modelName = modelName)

    toolCalls.forEachIndexed { index, toolCall ->
      ToolCallBlock(
        onSubChatClick = null,
        block = toolCall.toRenderBlock(),
        onViewDiff = { _, _, _ -> },
        onOpenInEditor = { _, _, _ -> },
      )
      if (index < toolCalls.lastIndex || subAgentResponse.isNotBlank())
        Spacer(modifier = Modifier.height(GradumSpacing.lg))
    }

    if (errorMessage.isNotBlank()) {
      Spacer(modifier = Modifier.height(GradumSpacing.lg))
      Text(
        text = errorMessage,
        color = JewelTheme.globalColors.text.error,
        style = JewelTheme.typography.editorTextStyle
      )
    }

    if (subAgentResponse.isNotBlank()) {
      GradumMarkdown(
        text = subAgentResponse, modifier = Modifier
      ) {
        isSimplified = true
        withSelection = true
        animationEnabled = false
      }
    }
    Spacer(Modifier.height(GradumSpacing.xxl))
  }
}

@Composable
private fun ModelNameHeader(modelName: String) {
  if (LocalShowModelName.current && modelName.isNotBlank()) {
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        contentDescription = null,
        key = GradumIcons.ColorLogo
      )
      Text(
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        text = formatModelName(modelName)
      )
    }
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
  }
}

@Composable
private fun SubChatConversationContent(
  scrollState: ScrollState, messages: List<ChatMessage>
) {
  Column(
    modifier = Modifier
      .verticalScroll(scrollState)
  ) {
    messages.forEachIndexed { index, message ->
      val shouldShowTimestamp: Boolean = index == 0 ||
        formatTimestamp(message.timestamp) !=
        formatTimestamp(messages.getOrNull(index - 1)?.timestamp ?: 0L)

      if (LocalShowTimestamp.current && shouldShowTimestamp) {
        MessageTimestamp(
          timestamp = message.timestamp,
          modifier = Modifier.padding(vertical = GradumSpacing.lg)
        )
      }

      if (message.isUserMessage) {
        UserChatBubble(
          message = message,
          showActions = false
        )
        Spacer(Modifier.height(GradumSpacing.xxl))
      } else {
        AssistantChatBubble(
          message = message,
          showActions = false,
          onSubChatClick = null,
          onViewDiff = { _, _, _ -> },
          onOpenInEditor = { _, _, _ -> }
        )
      }
    }
  }
}

private fun ToolCallInfo.toRenderBlock(): RenderBlock.ToolCall = RenderBlock.ToolCall(
  alias = alias,
  result = result,
  success = success,
  pending = pending,
  arguments = arguments,
  toolCallId = toolCallId,
  errorDetail = errorDetail,
  errorMessage = errorMessage,
  timeoutSeconds = timeoutSeconds
)
