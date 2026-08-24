/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * UserChatBubble.kt  2026-08-24 19:35:05 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.model.ChatMessage
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedFile
import gradum.idea.editor.AttachedImage
import gradum.idea.editor.AttachedText
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme.Companion.globalColors
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Maximum height of the user bubble while expanded. Content past this
 * point is reachable via the bubble's internal vertical scroll rather
 * than letting the bubble grow to fill the entire chat panel. At the
 * current typography (16sp regular × 1.5 line-height ≈ 24dp per line)
 * 200dp fits roughly 5 lines of body text — a tight cap, with most
 * longer pastes scrolling internally rather than growing the bubble.
 */
private val EXPAND_MAX_HEIGHT: Dp = 200.dp

/**
 * Right-aligned user message bubble with copy and resetAllState buttons.
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
  showActions: Boolean = true,
  onDeleteMessage: () -> Unit = {},
  onCopyAsContext: (String) -> Unit = {},
  onAttachmentClick: (VirtualFile) -> Unit = {}
) {
  var isCopied by remember { mutableStateOf(false) }
  var isExpanded by remember { mutableStateOf(false) }
  var showResetPopup by remember { mutableStateOf(false) }
  var isAttachmentsExpanded by remember { mutableStateOf(true) }

  val content = message.content
  val maxLines = if (isExpanded) Int.MAX_VALUE else 1
  val panelBackground = globalColors.borders.normal

  val imageAttachments: List<AttachedImage> =
    message.attachments.filterIsInstance<AttachedImage>()
  val fileAttachments: List<AttachedContext> =
    message.attachments.filter {
      it is AttachedFile || it is AttachedText
    }

  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End
  ) {
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
              topStart = 16.dp, topEnd = 16.dp,
              bottomStart = 16.dp, bottomEnd = 6.dp
            )
          )
          .background(color = panelBackground.copy(alpha = 0.8f))
          .padding(vertical = 8.dp, horizontal = 10.dp)
          .heightIn(max = EXPAND_MAX_HEIGHT)
          .animateContentSize(
            animationSpec = spring(
              stiffness = Spring.StiffnessMediumLow,
              dampingRatio = Spring.DampingRatioLowBouncy
            )
          )
      ) {
        Row(
          modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
          SelectionContainer {
            Text(
              text = content,
              maxLines = maxLines,
              lineHeight = JewelTheme.typography.labelTextStyle.fontSize * 1.5f,
              overflow =
                if (isExpanded) TextOverflow.Visible
                else TextOverflow.Ellipsis
            )
          }
        }
      }
      if (fileAttachments.isNotEmpty()) {
        Spacer(modifier = Modifier.height(GradumSpacing.lg))
        Row(
          modifier = Modifier
            .clickable { isAttachmentsExpanded = !isAttachmentsExpanded }
            .padding(horizontal = GradumSpacing.sm),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
        ) {
          Icon(
            contentDescription = null,
            key =
              if (isAttachmentsExpanded) AllIconsKeys.General.ChevronDown
              else AllIconsKeys.General.ChevronRight
          )
          Text(
            text = message("gradum.attachments"),
            fontWeight = FontWeight.Medium,
            color = globalColors.text.normal
          )
        }
        Spacer(Modifier.height(GradumSpacing.sm))
        AnimatedVisibility(visible = isAttachmentsExpanded) {
          MessageAttachmentList(attachments = fileAttachments)
        }
      }
      Spacer(modifier = Modifier.height(GradumSpacing.md))
      if (showActions) {
        Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
          MessageCopyButton(
            message = message,
            isCopied = isCopied,
            onCopy = { isCopied = true },
            onReset = { isCopied = false },
            onCopyAsContext = onCopyAsContext
          )
          if (content.length >= 100 || content.lines().size > 1) {
            Tooltip(tooltip = {
              Text(text = if (isExpanded) message("gradum.collapse") else message("gradum.expand"))
            }) {
              IconButton(onClick = { isExpanded = !isExpanded }) {
                Icon(
                  modifier = Modifier.size(16.dp),
                  contentDescription =
                    if (isExpanded) message("gradum.collapse")
                    else message("gradum.expand"),
                  key =
                    if (isExpanded) GradumIcons.CollapseAll
                    else GradumIcons.ExpandAll
                )
              }
            }
          }
          Tooltip(tooltip = {
            Text(text = message("gradum.reset.tooltip"))
          }) {
            IconButton(onClick = { showResetPopup = true }) {
              Icon(
                key = AllIconsKeys.General.Reset,
                contentDescription = message("gradum.reset")
              )
            }
          }
          if (showResetPopup) {
            PopupMenu(
              horizontalAlignment = Alignment.End,
              onDismissRequest = {
                showResetPopup = false
                true
              }
            ) {
              passiveItem {
                Column {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(
                        vertical = GradumSpacing.sm,
                        horizontal = GradumSpacing.sml
                      ),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Icon(
                      key = GradumIcons.Warning,
                      modifier = Modifier.padding(end = GradumSpacing.sml),
                      contentDescription = message("gradum.delete.confirm")
                    )
                    Text(text = message("gradum.delete.confirm"))
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
                    .padding(horizontal = GradumSpacing.sml),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    contentDescription = message("gradum.delete.action"),
                    key = AllIconsKeys.General.Reset,
                    modifier = Modifier.padding(end = GradumSpacing.sml)
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
}
