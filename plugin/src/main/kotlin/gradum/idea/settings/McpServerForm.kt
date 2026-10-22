@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val MCP_LABEL_WIDTH_DP: Int = 160

// Detail form for the selected server. Each field mirrors its
// [McpServerItem] text, shows [Outline.Error] while the value is
// invalid, and pushes the edit up after [MCP_INPUT_DEBOUNCE_MS]; an
// invalid value is flagged and never reaches the settings file. The
// environment row is a button: env vars live in settings.json and are
// edited there in the IDE editor, not in this panel.
@Composable
internal fun McpServerForm(
  item: McpServerItem,
  otherNames: Set<String>,
  pendingNameFocus: Boolean,
  onNameFocusHandled: () -> Unit,
  onNameChange: (String) -> Unit,
  onCommandChange: (String) -> Unit,
  onWorkingDirChange: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val nameState: TextFieldState = remember(item.id) { TextFieldState(initialText = item.name) }
  val commandState: TextFieldState = remember(item.id) { TextFieldState(initialText = item.commandText) }
  val workingDirState: TextFieldState = remember(item.id) { TextFieldState(initialText = item.workingDir) }
  val nameFocusRequester = remember { FocusRequester() }

  LaunchedEffect(pendingNameFocus, item.id) {
    if (pendingNameFocus) {
      nameFocusRequester.requestFocus()
      onNameFocusHandled()
    }
  }

  val nameText: String = nameState.text.toString()
  val isNameValid: Boolean = nameText.isNotBlank() && nameText.trim() !in otherNames
  val commandText: String = commandState.text.toString()
  val isCommandValid: Boolean = ProviderConfigFile.parseCommandText(commandText).isNotEmpty()

  LaunchedEffect(nameText, item.id) {
    delay(duration = MCP_INPUT_DEBOUNCE_MS.milliseconds)
    if (nameText != item.name) onNameChange(nameText)
  }
  LaunchedEffect(commandText, item.id) {
    delay(duration = MCP_INPUT_DEBOUNCE_MS.milliseconds)
    if (commandText != item.commandText) onCommandChange(commandText)
  }
  LaunchedEffect(workingDirState.text, item.id) {
    delay(duration = MCP_INPUT_DEBOUNCE_MS.milliseconds)
    val text: String = workingDirState.text.toString()
    if (text != item.workingDir) onWorkingDirChange(text)
  }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.xxl)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)) {
      Text(
        fontWeight = FontWeight.SemiBold,
        text = message("gradum.settings.mcp.basic")
      )
      FieldRow(label = message("gradum.settings.mcp.name")) {
        TextField(
          state = nameState,
          textStyle = JewelTheme.editorTextStyle,
          modifier = Modifier
            .width(width = MCP_FIELD_WIDTH_DP.dp)
            .focusRequester(nameFocusRequester),
          outline = if (isNameValid) Outline.None else Outline.Error,
          placeholder = { Text(text = message("gradum.settings.mcp.name.placeholder")) }
        )
      }
      FieldRow(label = message("gradum.settings.mcp.command")) {
        TextField(
          state = commandState,
          textStyle = JewelTheme.editorTextStyle,
          modifier = Modifier.width(width = MCP_FIELD_WIDTH_DP.dp),
          outline = if (isCommandValid) Outline.None else Outline.Error,
          placeholder = { Text(text = message("gradum.settings.mcp.command.placeholder")) },
          trailingIcon = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
            ) {
              Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.command.clear")) }) {
                Icon(
                  key = AllIconsKeys.General.Delete,
                  contentDescription = message("gradum.settings.mcp.command.clear"),
                  modifier = Modifier.clickable {
                    commandState.edit { replace(start = 0, end = length, text = "") }
                  }
                )
              }
              Tooltip(tooltip = { Text(text = message("gradum.copy")) }) {
                Icon(
                  key = AllIconsKeys.General.Copy,
                  contentDescription = message("gradum.copy"),
                  modifier = Modifier.clickable {
                    CopyPasteManager.getInstance()
                      .setContents(StringSelection(commandState.text.toString()))
                  }
                )
              }
            }
          }
        )
      }
      FieldRow(label = message("gradum.settings.mcp.workingdir")) {
        TextField(
          state = workingDirState,
          textStyle = JewelTheme.editorTextStyle,
          modifier = Modifier.width(width = MCP_FIELD_WIDTH_DP.dp),
          outline = Outline.None,
          placeholder = { Text(text = message("gradum.settings.mcp.workingdir.placeholder")) },
          trailingIcon = {
            Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.workingdir")) }) {
              Icon(
                key = AllIconsKeys.Nodes.Folder,
                contentDescription = message("gradum.settings.mcp.workingdir"),
                modifier = Modifier.clickable {
                  val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                  FileChooser.chooseFiles(
                    descriptor,
                    ProjectManager.getInstance().defaultProject,
                    null
                  ) { files: List<VirtualFile> ->
                    val chosen: VirtualFile = files.firstOrNull() ?: return@chooseFiles
                    workingDirState.edit { replace(start = 0, end = length, text = chosen.path) }
                  }
                }
              )
            }
          }
        )
      }
      FieldRow(label = message("gradum.settings.mcp.env")) {
        OutlinedButton(onClick = { openSettingsFileInIdeEditor(serverName = item.name) }) {
          Text(text = message("gradum.settings.mcp.env.open"))
        }
      }
    }
  }
}

// One label and control row matching the model-provider field layout.
@Composable
private fun FieldRow(label: String, field: @Composable () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      modifier = Modifier.width(width = MCP_LABEL_WIDTH_DP.dp)
    )
    Spacer(Modifier.width(GradumSpacing.lg))
    field()
  }
}

// Opens `../.gradum/settings.json` in the IDE editor and lands the caret
// on the selected server's entry (falling back to the `mcpServers`
// header), so environment variables are edited in a real editor against
// the single source of truth. Creates a minimal `{}` file first when none
// exists yet; a no-op while no project window is open (the settings
// dialog is modal, so the editor becomes interactive once the dialog is
// closed).
@Suppress("UnstableApiUsage")
private fun openSettingsFileInIdeEditor(serverName: String) {
  val project: Project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
  val file: File = ProviderConfigFile.settingsFile
  if (!file.exists()) {
    file.parentFile?.mkdirs()
    file.writeText(text = "{}\n")
  }

  val virtualFile: VirtualFile =
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.toPath()) ?: return
  val editors: Array<FileEditor> =
    FileEditorManager.getInstance(project).openFile(virtualFile, true)
  val editor: Editor = (editors.firstOrNull() as? TextEditor)?.editor ?: return
  val text: String = WriteIntentReadAction.compute<String?> {
    FileDocumentManager.getInstance().getDocument(virtualFile)?.text
  } ?: file.readText()
  val nameIndex: Int = text.indexOf("\"name\": \"$serverName\"")
  val offset: Int = if (nameIndex >= 0) nameIndex else text.indexOf("\"mcpServers\"")

  if (offset >= 0) {
    @Suppress("UnstableApiUsage")
    WriteIntentReadAction.run {
      editor.caretModel.moveToOffset(offset)
      editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
  }
}
