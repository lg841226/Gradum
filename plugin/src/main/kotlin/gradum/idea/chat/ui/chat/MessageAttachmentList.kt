/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageAttachmentList.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import gradum.idea.editor.AttachedContext
import gradum.idea.chat.ui.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text

/**
 * Renders the attachments attached to a chat message as a vertical list
 * of chips under the bubble. Each chip shows the file-type icon and the
 * file name. Chips are intentionally immutable — they cannot be removed
 * after the message has been sent.
 */
@Composable
fun MessageAttachmentList(
    attachments: List<AttachedContext>,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Box(modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            attachments.forEach { attachment -> AttachmentChip(attachment = attachment) }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: AttachedContext) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color = JewelTheme.globalColors.borders.normal)
            .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
    ) {
        Icon(key = attachment.iconKey, contentDescription = attachment.displayName, modifier = Modifier.size(14.dp))
        Text(text = attachment.displayName)
    }
}
