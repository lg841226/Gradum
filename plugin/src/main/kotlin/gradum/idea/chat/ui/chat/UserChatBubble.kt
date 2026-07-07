/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * UserChatBubble.kt  2026-07-07 12:29:08 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import org.jetbrains.jewel.ui.typography

/**
 * Duration of the user-bubble expand / collapse size animation, in ms.
 * Matches the project's other tween-based animations (rise, fade) so
 * transitions feel consistent across the chat panel.
 */
private const val EXPAND_ANIMATION_MS: Int = 200

/**
 * Maximum height of the user bubble while expanded. Content past this
 * point is reachable via the bubble's internal vertical scroll rather
 * than letting the bubble grow to fill the entire chat panel. At the
 * current typography (16sp regular × 1.5 line-height ≈ 24dp per line)
 * 400dp fits roughly 10 lines of body text — enough to read a pasted
 * stack trace in context without scrolling the entire chat panel.
 */
private val EXPAND_MAX_HEIGHT: Dp = 400.dp

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
  var isExpanded by remember { mutableStateOf(false) }
  val content = message.content

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
              topStart = 14.dp, topEnd = 14.dp,
              bottomStart = 14.dp, bottomEnd = 0.dp
            )
          )
          .background(color = JewelTheme.globalColors.borders.normal)
          .padding(10.dp)
          // Cap the bubble's height once expanded so a 200-line paste
          // doesn't push the bubble taller than the chat panel itself.
          // Anything past [EXPAND_MAX_HEIGHT] stays reachable through
          // the inner [verticalScroll] below. The collapsed 1-line
          // state is unaffected — heightIn allows the layout to be
          // smaller than its cap, so the bubble still snaps back to
          // a single line when [isExpanded] flips to false.
          .heightIn(max = EXPAND_MAX_HEIGHT)
          // Smoothly grow / shrink the bubble when the user toggles
          // the expand button. animateContentSize detects that the
          // inner Text's measured height changed (because maxLines
          // went from 1 to Int.MAX_VALUE, or back) and tweens the
          // Box's outer size to match. Place it on the Box (not the
          // inner Row) so the padding and rounded corners animate
          // together with the content — otherwise the panel would
          // appear to "snap" outward.
          .animateContentSize(
            animationSpec = tween(
              durationMillis = EXPAND_ANIMATION_MS,
              easing = FastOutSlowInEasing,
            )
          )
      ) {
        Row(
          modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
          SelectionContainer {
            Text(
              text = content,
              maxLines = if (isExpanded) Int.MAX_VALUE else 1,
              style = JewelTheme.typography.regular.copy(
                lineHeight = JewelTheme.typography.regular.fontSize * 1.5f
              ),
              overflow = if (isExpanded) TextOverflow.Visible else TextOverflow.Ellipsis
            )
          }
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
                contentDescription = if (isExpanded) message("gradum.collapse") else message("gradum.expand"),
                key = if (isExpanded) GradumIcons.CollapseAll else GradumIcons.ExpandAll
              )
            }
          }
        }
        Tooltip(tooltip = {
          Text(text = message("gradum.reset.tooltip"))
        }) {
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
                    modifier = Modifier.padding(GradumSpacing.sml),
                    contentDescription = message("gradum.delete.confirm")
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
              onClick = { showResetPopup = false; onDeleteMessage() }
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
