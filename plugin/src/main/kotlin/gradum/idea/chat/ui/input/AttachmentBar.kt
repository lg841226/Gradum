/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AttachmentBar.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.editor.AttachedContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Attachment bar below the toolbar, showing selected attachments.
 */
@Composable
fun AttachmentBar(attachedFiles: List<AttachedContext>, onRemoveFile: (AttachedContext) -> Unit) {
    if (attachedFiles.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachedFiles.forEach { attachedContext ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    key = attachedContext.iconKey,
                    contentDescription = attachedContext.displayName,
                    modifier = Modifier.size(16.dp)
                )
                Text(text = attachedContext.displayName, color = JewelTheme.globalColors.text.normal)
                IconTooltipButton(
                    tooltip = message("gradum.remove"),
                    iconKey = AllIconsKeys.Actions.Close,
                    contentDescription = message("gradum.remove"),
                    onClick = { onRemoveFile(attachedContext) },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
