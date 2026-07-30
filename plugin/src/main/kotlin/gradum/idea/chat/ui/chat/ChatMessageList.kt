/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatMessageList.kt  2026-07-29 21:24:44 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.input.PermissionMode

private val TimestampSpacing = GradumSpacing.xs

@Composable
fun ChatMessageList(
  messages: List<ChatMessage>,
  modifier: Modifier = Modifier,
  isLoading: Boolean = false,
  sendingPhase: String = "",
  selectedPermission: String = PermissionMode.READONLY,
  onDeleteMessage: (Int) -> Unit = {},
  onRetryMessage: (Int) -> Unit = {},
  onCopyAsContext: (String) -> Unit = {},
  onAttachmentClick: (VirtualFile) -> Unit = {}
) {
  val scrollState = rememberScrollState()

  Column(
    modifier = modifier.verticalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    messages.forEachIndexed { index, message ->
      val isLastAssistant: Boolean = index == messages.lastIndex && !message.isUserMessage && isLoading
      val shouldShowTimestamp: Boolean = index == 0 ||
        formatTimestamp(message.timestamp) != formatTimestamp(messages[index - 1].timestamp)
      if (shouldShowTimestamp) {
        Spacer(modifier = Modifier.height(TimestampSpacing))
        MessageTimestamp(timestamp = message.timestamp)
        Spacer(modifier = Modifier.height(TimestampSpacing))
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
          sendingPhase = if (isLastAssistant) sendingPhase else "",
          selectedPermission = selectedPermission,
          isLoading = isLastAssistant,
          onRetry = { onRetryMessage(index) }
        )
      }
    }
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
  }
}
