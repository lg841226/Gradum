/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AttachmentBar.kt  2026-08-25 01:43:26 Changed by gwy
 */

package gradum.idea.chat.ui.input

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.editor.AttachedContext
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Attachment bar below the toolbar, showing selected attachments
 * as a flat horizontal row of [file-type icon, name, remove] chips.
 * Image attachments do not decode a preview bitmap here — the
 * full-fidelity preview is rendered by [MessageAttachmentPreview]
 * once the message is sent, using the user's original file.
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
      .horizontalScroll(state = rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically
  ) {
    attachedFiles.forEach { attachedContext: AttachedContext ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
        modifier = Modifier.padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
      ) {
        Icon(
          key = attachedContext.iconKey,
          contentDescription = attachedContext.displayName,
          modifier = Modifier.size(16.dp)
        )
        Text(
          maxLines = 1,
          text = attachedContext.displayName,
          color = JewelTheme.globalColors.text.normal
        )
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
