@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import gradum.idea.PluginConfig
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderConfigFile.McpServerEntry
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val MCP_TAB_ADD_KEY = "mcp-tab-add"
private const val MCP_LABEL_WIDTH_DP: Int = 160
private const val MCP_FIELD_WIDTH_DP: Int = 480
private const val MCP_INPUT_DEBOUNCE_MS: Long = PluginConfig.INPUT_DEBOUNCE_MS

/**
 * In-session working copy of one `mcpServers[]` entry. The file stays
 * the single source of truth: every debounced field edit is validated
 * and written back through [ProviderConfigFile.writeMcpServers]; raw
 * text fields mirror the file until the user edits them. Env maps are
 * intentionally absent — they are edited in the IDE editor against
 * settings.json and re-read from disk on every write.
 */
private data class McpServerItem(
  val id: Long,
  val name: String,
  val commandText: String,
  val workingDir: String
)

private fun McpServerEntry.toItem(id: Long): McpServerItem = McpServerItem(
  id = id,
  name = name,
  commandText = ProviderConfigFile.formatCommandText(command),
  workingDir = workingDir.orEmpty()
)

/**
 * Validates the working copy and converts it to the file model, or
 * returns null when the entry must not be written (blank name or a
 * command that tokenizes to nothing). The returned entry carries an
 * empty env; persist splices the env from the file by name so
 * hand-edits in settings.json survive panel writes.
 */
private fun McpServerItem.toEntryOrNull(): McpServerEntry? {
  val trimmedName: String = name.trim()
  if (trimmedName.isEmpty()) return null
  val tokens: List<String> = ProviderConfigFile.parseCommandText(commandText)
  if (tokens.isEmpty()) return null
  return McpServerEntry(
    name = trimmedName,
    command = tokens,
    workingDir = workingDir.trim().ifEmpty { null }
  )
}

/**
 * Settings page section for the `mcpServers` array in
 * `~/.gradum/settings.json`.
 *
 * Mirrors the cloud-provider tabs layout: servers are removable capsule
 * tabs in a horizontal strip with a trailing add button; the
 * selected server's form (name / command / working dir) renders
 * underneath. Every field edit is debounced by [MCP_INPUT_DEBOUNCE_MS]
 * and written straight to the settings file through [ProviderConfigFile]:
 * there is no Apply button and no second copy of the config, so the file
 * stays the single source of truth. Environment variables have no input
 * field here — their row opens settings.json itself in the IDE editor
 * (caret on the selected server), and each panel write re-reads env maps
 * from disk so the two surfaces never clobber each other. The embedded
 * server reads `mcpServers` only at startup, so the section ends with a
 * restart hint instead of a connection test.
 */
@Composable
internal fun McpServersSettings() {
  val loaded: List<McpServerItem> = remember {
    ProviderConfigFile.readMcpServers().mapIndexed { index, entry -> entry.toItem(id = index.toLong()) }
  }
  var nextId: Long by remember { mutableStateOf(value = (loaded.maxOfOrNull { it.id } ?: -1L) + 1) }
  var items: List<McpServerItem> by remember { mutableStateOf(value = loaded) }
  var selectedId: Long? by remember { mutableStateOf(value = loaded.firstOrNull()?.id) }
  var pendingNameFocus: Boolean by remember { mutableStateOf(value = false) }

  val selectedIndex: Int = items.indexOfFirst { it.id == selectedId }
  val selected: McpServerItem? = items.getOrNull(selectedIndex)

  /**
   * Writes the working copy back to the file. Env maps are not part of
   * the working copy: they are edited directly in settings.json through
   * the form's open-in-editor button, so every write re-reads them from
   * disk by name and splices them into the entries being saved — a
   * panel edit can never revert an env change made in the IDE editor.
   */
  fun persist(updated: List<McpServerItem>) {
    items = updated
    val fileEnvs: Map<String, Map<String, String>> =
      ProviderConfigFile.readMcpServers()
        .associateBy(keySelector = { it.name.trim() }, valueTransform = { it.env })
    ProviderConfigFile.writeMcpServers(
      updated.mapNotNull { item ->
        val entry: McpServerEntry = item.toEntryOrNull() ?: return@mapNotNull null
        entry.copy(env = fileEnvs[entry.name].orEmpty())
      }
    )
  }

  fun commitItem(id: Long, transform: (McpServerItem) -> McpServerItem) {
    val current: McpServerItem = items.firstOrNull { it.id == id } ?: return
    val updated: McpServerItem = transform(current)
    val duplicateName: Boolean =
      items.any { it.id != id && it.name.trim() == updated.name.trim() && updated.name.isNotBlank() }
    if (duplicateName || updated.toEntryOrNull() == null) return
    persist(items.map { if (it.id == id) updated.copy(name = updated.name.trim()) else it })
  }

  fun onAddServer() {
    val existingNames: Set<String> = items.map { it.name.trim() }.toSet()
    var sequence = 1
    var candidate: String = message("gradum.settings.mcp.newname", sequence)
    while (candidate in existingNames) {
      sequence++
      candidate = message("gradum.settings.mcp.newname", sequence)
    }
    val item = McpServerItem(
      id = nextId,
      name = candidate,
      commandText = "",
      workingDir = ""
    )
    nextId++
    persist(items + item)
    selectedId = item.id
    pendingNameFocus = true
  }

  fun onRemove(id: Long) {
    val index: Int = items.indexOfFirst { it.id == id }
    if (index < 0) return
    val updated: List<McpServerItem> = items.filterIndexed { i, _ -> i != index }
    persist(updated)
    if (selectedId == id)
      selectedId = updated.getOrNull(index = (index - 1).coerceAtLeast(minimumValue = 0))?.id
  }

  Column {
    GroupHeader(
      modifier = Modifier.fillMaxWidth(),
      text = message("gradum.settings.mcp.section")
    )
    Column(
      modifier = Modifier.padding(start = SETTINGS_GROUP_INDENT)
    ) {
      Spacer(Modifier.height(GradumSpacing.md))
      LazyRow(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
      ) {
        items(items, key = { it.id }) { item ->
          McpTab(
            name = item.name,
            isSelected = item.id == selectedId,
            onClick = { selectedId = item.id },
            onRemove = { onRemove(item.id) },
            modifier = Modifier.animateItem(
              fadeInSpec = tween(durationMillis = 150),
              fadeOutSpec = tween(durationMillis = 150),
              placementSpec = tween(durationMillis = 200)
            ),
          )
        }
        item(key = MCP_TAB_ADD_KEY) {
          Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.add")) }) {
            IconButton(
              onClick = { onAddServer() },
              modifier = Modifier.padding(all = GradumSpacing.xs)
            ) {
              Icon(
                key = AllIconsKeys.General.Add,
                contentDescription = message("gradum.settings.mcp.add")
              )
            }
          }
        }
      }
      Spacer(Modifier.height(GradumSpacing.sml))
      if (selected == null) {
        Text(
          color = JewelTheme.globalColors.text.info,
          text = message("gradum.settings.mcp.empty")
        )
      } else {
        McpServerForm(
          item = selected,
          pendingNameFocus = pendingNameFocus,
          onNameFocusHandled = { pendingNameFocus = false },
          onNameChange = { text -> commitItem(selected.id) { it.copy(name = text) } },
          otherNames = items.filter { it.id != selected.id }.map { it.name.trim() }.toSet(),
          onCommandChange = { text -> commitItem(selected.id) { it.copy(commandText = text) } },
          onWorkingDirChange = { text -> commitItem(selected.id) { it.copy(workingDir = text) } }
        )
        Spacer(Modifier.height(GradumSpacing.ml))
        Text(
          modifier = Modifier.padding(bottom = GradumSpacing.xs),
          color = JewelTheme.globalColors.text.info,
          style = JewelTheme.typography.labelTextStyle,
          text = message("gradum.settings.mcp.restart")
        )
      }
    }
  }
}

/**
 * Removable capsule tab for one MCP server — a direct copy of the
 * cloud-provider tab: click selects the server, the trailing "×"
 * removes it and shifts the selection one tab to the left.
 */
@Composable
private fun McpTab(
  name: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .background(
        shape = RoundedCornerShape(percent = 80),
        color = if (isSelected) JewelTheme.globalColors.toolwindowBackground
        else Color.Transparent
      )
      .then(
        if (isSelected)
          Modifier.border(
            width = 1.dp,
            shape = RoundedCornerShape(percent = 80),
            color = JewelTheme.globalColors.borders.normal
          ) else Modifier
      )
      .padding(
        vertical = GradumSpacing.sm,
        horizontal = GradumSpacing.md
      )
      .clickable(onClick = onClick)
  ) {
    Icon(
      key = GradumIcons.Mcp,
      contentDescription = null
    )
    Spacer(Modifier.width(GradumSpacing.sml))
    Text(text = name)
    Spacer(Modifier.width(GradumSpacing.md))
    Tooltip(
      tooltip = { Text(text = message("gradum.remove")) }
    ) {
      Icon(
        key = AllIconsKeys.General.Close,
        modifier = Modifier.clickable(onClick = onRemove).size(12.dp),
        contentDescription = message("gradum.remove")
      )
    }
  }
}

/**
 * Detail form for the selected server. Each field mirrors its
 * [McpServerItem] text, shows [Outline.Error] while the value is
 * invalid, and pushes the edit up after [MCP_INPUT_DEBOUNCE_MS]; an
 * invalid value is flagged and never reaches the settings file. The
 * environment row is a button: env vars live in settings.json and are
 * edited there in the IDE editor, not in this panel.
 */
@Composable
private fun McpServerForm(
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

/** One label + control row matching the model-provider field layout. */
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

/**
 * Opens `~/.gradum/settings.json` in the IDE editor and lands the caret
 * on the selected server's entry (falling back to the `mcpServers`
 * header), so environment variables are edited in a real editor against
 * the single source of truth. Creates a minimal `{}` file first when none
 * exists yet; a no-op while no project window is open (the settings
 * dialog is modal, so the editor becomes interactive once the dialog is
 * closed).
 */
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
