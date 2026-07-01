/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AttachmentBar.kt  2026-06-30 23:35:47 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
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
fun AttachmentBar(
    attachedFiles: List<AttachedContext>,
    onRemoveFile: (AttachedContext) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachedFiles.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachedFiles.forEach { attachedContext ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
                modifier = Modifier.padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.md)
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
