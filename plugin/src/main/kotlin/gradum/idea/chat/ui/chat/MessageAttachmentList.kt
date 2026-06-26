/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageAttachmentList.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.editor.AttachedContext
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(color = JewelTheme.globalColors.borders.normal, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(key = attachment.iconKey, contentDescription = attachment.displayName, modifier = Modifier.size(14.dp))
        Text(text = attachment.displayName)
    }
}
