/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * FileItem.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.editor.getLanguageIconKey
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A single file entry shown in the add-menu file list, with a language icon and bold name when selected.
 */
@Composable
fun FileItem(
  file: VirtualFile,
  isSelected: Boolean,
  modifier: Modifier = Modifier
) {
  val iconKey = getLanguageIconKey(file.extension)

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.xs),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      key = iconKey ?: AllIconsKeys.FileTypes.Unknown,
      contentDescription = file.fileType.name,
      modifier = Modifier
        .padding(end = GradumSpacing.md)
        .size(14.dp)
    )
    Column {
      Text(text = file.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
  }
}
