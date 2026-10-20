package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.editor.fileIcon
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text

/**
 * A single file entry shown in the add-menu file list, with the icon the IDE shows
 * for its file type and bold name when selected.
 */
@Composable
fun FileItem(
  file: VirtualFile,
  isSelected: Boolean,
  modifier: Modifier = Modifier
) {
  val icon = remember(key1 = file) { fileIcon(file) }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.xs),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      icon = icon,
      contentDescription = file.fileType.name,
      modifier = Modifier
        .padding(end = GradumSpacing.md)
        .size(14.dp)
    )
    Column {
      Text(
        text = file.name,
        fontWeight =
          if (isSelected) FontWeight.Bold
          else FontWeight.Normal
      )
    }
  }
}
