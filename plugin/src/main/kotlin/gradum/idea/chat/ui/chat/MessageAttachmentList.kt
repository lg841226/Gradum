/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageAttachmentList.kt  2026-07-29 18:32:58 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.editor.AttachedContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text

/**
 * File / text attachments attached to a user message. Rendered as a
 * FlowRow of chips below the bubble.
 *
 * Uses [FlowRow] to support adaptive wrapping: 1-column on narrow panels,
 * 2+ columns when space permits.
 *
 * Chips are right-aligned via `Arrangement.spacedBy(..., Alignment.End)`,
 * so a partial last row sits against the right edge rather than the left.
 *
 * Chips are intentionally immutable: the message is already sent,
 * removing them would diverge UI from history.
 */
@Composable
fun MessageAttachmentList(
  attachments: List<AttachedContext>,
  modifier: Modifier = Modifier
) {
  if (attachments.isEmpty()) return
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml, Alignment.End),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
  ) {
    attachments.forEach { attachment -> AttachmentChip(attachment = attachment) }
  }
}

@Composable
private fun AttachmentChip(attachment: AttachedContext) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(color = JewelTheme.globalColors.borders.normal.copy(alpha = 0.8f))
      .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
      .clickable { /* open file in editor — wired by parent */ },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    Icon(
      key = attachment.iconKey,
      contentDescription = null,
      modifier = Modifier.size(14.dp)
    )
    Text(
      maxLines = 1,
      text = attachment.displayName,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 100.dp)
    )
  }
}
