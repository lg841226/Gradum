/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * UserChatBubble.kt  2026-07-06 14:15:11 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedFile
import gradum.idea.editor.AttachedImage
import gradum.idea.editor.AttachedText
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Right-aligned user message bubble with copy and reset buttons.
 *
 * Image attachments render as a compact preview row *above* the
 * bubble ([MessageAttachmentPreview]). File and text attachments
 * render as a chevron-toggled, animated list *below* the bubble
 * ([MessageAttachmentList]). The two halves never overlap because
 * they live in different render slots.
 *
 * @param onAttachmentClick Forwarded to [MessageAttachmentPreview];
 *   file chips have no click action (they are display-only after
 *   the message is sent).
 */
@Composable
fun UserChatBubble(
  message: ChatMessage,
  onDeleteMessage: () -> Unit = {},
  onCopyAsContext: (String) -> Unit = {},
  onAttachmentClick: (VirtualFile) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var isCopied by remember { mutableStateOf(false) }
  var showResetPopup by remember { mutableStateOf(false) }
  var isAttachmentsExpanded by remember { mutableStateOf(true) }

  val imageAttachments: List<AttachedImage> = message.attachments.filterIsInstance<AttachedImage>()
  val fileAttachments: List<AttachedContext> = message.attachments.filter {
    it is AttachedFile || it is AttachedText
  }

  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Column(horizontalAlignment = Alignment.End) {
      if (imageAttachments.isNotEmpty()) {
        MessageAttachmentPreview(
          attachments = imageAttachments,
          onAttachmentClick = onAttachmentClick
        )
        Spacer(modifier = Modifier.height(GradumSpacing.md))
      }
      Box(
        modifier = Modifier
          .clip(
            RoundedCornerShape(
              topStart = 16.dp,
              topEnd = 16.dp,
              bottomStart = 16.dp,
              bottomEnd = 0.dp
            )
          )
          .background(color = JewelTheme.globalColors.borders.normal)
          .padding(10.dp)
      ) {
        SelectionContainer {
          Text(text = message.content)
        }
      }
      if (fileAttachments.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier
            .clickable { isAttachmentsExpanded = !isAttachmentsExpanded }
            .padding(horizontal = GradumSpacing.sm),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
        ) {
          Icon(
            contentDescription = null,
            key = if (isAttachmentsExpanded) AllIconsKeys.General.ChevronDown
            else AllIconsKeys.General.ChevronRight
          )
          Text(
            text = message("gradum.attachments"),
            fontWeight = FontWeight.Medium,
            color = JewelTheme.globalColors.text.normal
          )
        }
        Spacer(Modifier.height(GradumSpacing.sm))
        AnimatedVisibility(visible = isAttachmentsExpanded) {
          MessageAttachmentList(attachments = fileAttachments)
        }
      }
      Spacer(modifier = Modifier.height(GradumSpacing.md))
      Row {
        MessageCopyButton(
          message = message,
          isCopied = isCopied,
          onCopy = { isCopied = true },
          onReset = { isCopied = false },
          onCopyAsContext = onCopyAsContext
        )
        Spacer(modifier = Modifier.width(GradumSpacing.sm))
        Tooltip(tooltip = {
          Text(text = message("gradum.reset.tooltip"))
        }
        ) {
          IconButton(onClick = { showResetPopup = true }) {
            Icon(key = AllIconsKeys.General.Reset, contentDescription = message("gradum.reset"))
          }
        }
        if (showResetPopup) {
          PopupMenu(
            horizontalAlignment = Alignment.End,
            onDismissRequest = { showResetPopup = false; true }
          ) {
            passiveItem {
              Column(modifier = Modifier.padding(horizontal = 6.dp)) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = GradumSpacing.xs),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    key = GradumIcons.Warning,
                    contentDescription = message("gradum.delete.confirm"),
                    modifier = Modifier.padding(end = 6.dp)
                  )
                  Text(text = message("gradum.delete.confirm"))
                }
                Spacer(modifier = Modifier.height(GradumSpacing.sm))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.Center
                ) {
                  Text(
                    text = message("gradum.delete.revert.warning"),
                    color = JewelTheme.globalColors.text.info
                  )
                }
              }
            }
            separator()
            selectableItem(
              selected = false,
              onClick = {
                showResetPopup = false
                onDeleteMessage()
              }
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  key = AllIconsKeys.General.Reset,
                  contentDescription = message("gradum.delete.action"),
                  modifier = Modifier.padding(end = 6.dp)
                )
                Text(text = message("gradum.delete.action"))
              }
            }
          }
        }
      }
    }
  }
}
