@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.state.GradumChatSession.Companion.MAX_ATTACHMENTS
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun AddContextPopup(
  searchState: TextFieldState,
  filteredFiles: List<VirtualFile>,
  state: ChatInputState,
  actions: ChatInputActions,
  modifier: Modifier = Modifier
) {
  PopupMenu(
    onDismissRequest = { actions.onDismissAddMenu(); true },
    horizontalAlignment = Alignment.Start,
    modifier = modifier.heightIn(max = 300.dp)
  ) {
    passiveItem {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
      ) {
        Icon(
          key = GradumIcons.Search,
          contentDescription = message("gradum.add.popup.search")
        )
        Spacer(modifier = Modifier.width(GradumSpacing.md))
        TextField(
          state = searchState,
          undecorated = true,
          modifier = Modifier
            .defaultMinSize(minWidth = 160.dp)
            .widthIn(max = 200.dp),
          placeholder = { Text(text = message("gradum.add.popup.search.placeholder")) }
        )
      }
    }
    separator()

    selectableItem(
      selected = false,
      onClick = { state.editorContext.projectDir?.let(block = actions.onSelectFile) }
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            horizontal = GradumSpacing.md,
            vertical = GradumSpacing.xs
          ),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          key = AllIconsKeys.Actions.ProjectDirectory,
          contentDescription = message("gradum.add.popup.project.directory")
        )
        Spacer(modifier = Modifier.width(GradumSpacing.md))
        Text(text = message("gradum.add.popup.project.directory"))
      }
    }

    if (state.currentFileHasErrors) {
      selectableItem(
        selected = false,
        onClick = { actions.onAddFileErrors() }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = GradumSpacing.md,
              vertical = GradumSpacing.xs
            ),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            key = AllIconsKeys.Ide.FatalErrorRead,
            contentDescription = message("gradum.add.popup.file.errors")
          )
          Spacer(modifier = Modifier.width(GradumSpacing.md))
          Text(text = message("gradum.add.popup.file.errors"))
        }
      }
    }

    if (state.isCurrentModelSupportsVision && !state.isAttachmentLimitReached) {
      selectableItem(
        selected = false,
        onClick = { actions.onUploadImage() }
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = GradumSpacing.md,
              vertical = GradumSpacing.xs
            )
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              key = AllIconsKeys.Actions.ViewAsImage,
              contentDescription = message("gradum.add.popup.upload.image"),
              modifier = Modifier.size(GradumSpacing.lrl)
            )
            Spacer(modifier = Modifier.width(GradumSpacing.md))
            Text(text = message("gradum.add.popup.upload.image"))
          }
          Spacer(modifier = Modifier.height(GradumSpacing.sm))
          Text(
            color = JewelTheme.globalColors.text.info,
            text = message("gradum.add.popup.upload.image.hint", MAX_ATTACHMENTS),
            modifier = Modifier.padding(start = GradumSpacing.lrl + GradumSpacing.md)
          )
        }
      }
    }

    separator()

    passiveItem {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            horizontal = GradumSpacing.md,
            vertical = GradumSpacing.xs
          )
      ) {
        Text(
          text = message("gradum.add.popup.workspace"),
          fontWeight = FontWeight.Medium
        )
      }
    }

    if (state.editorContext.allOpenFiles.isEmpty()) {
      passiveItem {
        Text(
          text = message("gradum.add.popup.empty"),
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.padding(
            horizontal = GradumSpacing.md,
            vertical = GradumSpacing.sm
          )
        )
      }
    } else if (filteredFiles.isEmpty()) {
      passiveItem {
        Text(
          text = message("gradum.add.popup.no.results"),
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.padding(
            horizontal = GradumSpacing.md,
            vertical = GradumSpacing.sm
          )
        )
      }
    } else {
      filteredFiles.forEach { file: VirtualFile ->
        selectableItem(
          onClick = { actions.onSelectFile(file) },
          selected = file == state.editorContext.currentFile
        ) {
          FileItem(
            file = file,
            isSelected = file == state.editorContext.currentFile
          )
        }
      }
    }
  }
}
