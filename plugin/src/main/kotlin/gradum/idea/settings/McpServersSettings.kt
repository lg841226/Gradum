@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
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
import gradum.idea.chat.ui.common.verticalEdgeFade
import gradum.idea.mcp.McpRegistryClient
import gradum.idea.mcp.McpRegistryResult
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderConfigFile.McpServerEntry
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.typography
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val MCP_TAB_ADD_KEY = "mcp-tab-add"
private const val MCP_LABEL_WIDTH_DP: Int = 160
private const val MCP_FIELD_WIDTH_DP: Int = 480
private const val MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP: Int = 320
private const val MCP_REGISTRY_SCROLLBAR_RESERVE_DP: Int = 18
private const val MCP_INPUT_DEBOUNCE_MS: Long = PluginConfig.INPUT_DEBOUNCE_MS

/**
 * In-session working copy of one `mcpServers[]` entry. The file stays
 * the single source of truth: every debounced field edit is validated
 * and written back through [ProviderConfigFile.writeMcpServers]; raw
 * text fields mirror the file until the user edits them. Env maps are
 * intentionally absent — they are edited in the IDE editor against
 * settings.json and re-read from disk on every write.
 */
internal data class McpServerItem(
  val id: Long,
  val name: String,
  val commandText: String,
  val workingDir: String
)

internal fun McpServerEntry.toItem(id: Long): McpServerItem = McpServerItem(
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
internal fun McpServerItem.toEntryOrNull(): McpServerEntry? {
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
 * First free tab name for a registry import: the base name when it is not
 * taken yet, otherwise `<base>-2`, `<base>-3`, … until it fits.
 */
internal fun uniqueMcpTabName(base: String, existing: Set<String>): String {
  var candidate: String = base
  var suffix: Int = 2
  while (candidate in existing) {
    candidate = "$base-$suffix"
    suffix++
  }
  return candidate
}

/**
 * Env splice used by persist: an entry keeps the env map stored under its
 * name in settings.json unless [providedEnvs] carries one for it (the
 * registry import pre-fills its hints this way).
 */
internal fun spliceMcpEnvs(
  entry: McpServerEntry,
  fileEnvs: Map<String, Map<String, String>>,
  providedEnvs: Map<String, Map<String, String>>
): McpServerEntry =
  entry.copy(env = providedEnvs[entry.name] ?: fileEnvs[entry.name].orEmpty())

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
 * from disk so the two surfaces never clobber each other. A search box on
 * top queries the official online registry ([McpRegistryClient]) and
 * imports stdio servers as new tabs. The embedded server reads
 * `mcpServers` only at startup, so the section ends with a restart hint
 * instead of a connection test.
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
   * [providedEnvs] overrides the splice for entries whose env should
   * come from somewhere else instead of the file (the online registry
   * imports pre-filled environment variables this way).
   */
  fun persist(
    updated: List<McpServerItem>,
    providedEnvs: Map<String, Map<String, String>> = emptyMap()
  ) {
    items = updated
    val fileEnvs: Map<String, Map<String, String>> =
      ProviderConfigFile.readMcpServers()
        .associateBy(keySelector = { it.name.trim() }, valueTransform = { it.env })
    ProviderConfigFile.writeMcpServers(
      updated.mapNotNull { item ->
        val entry: McpServerEntry = item.toEntryOrNull() ?: return@mapNotNull null
        spliceMcpEnvs(entry = entry, fileEnvs = fileEnvs, providedEnvs = providedEnvs)
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

  /**
   * Imports a result from the official online registry: builds the stdio
   * command entry, uniquifies the short name against existing tabs, and
   * pre-fills the env hints the registry advertised. The entry lands in
   * settings.json immediately like any other edit — the embedded server
   * picks it up after the next runtime restart.
   */
  fun onAddFromRegistry(result: McpRegistryResult) {
    val command: List<String> = result.command ?: return
    val finalName: String = uniqueMcpTabName(
      base = result.shortName.trim().ifEmpty { "mcp-server" },
      existing = items.map { it.name.trim() }.toSet()
    )
    val item = McpServerItem(
      id = nextId,
      name = finalName,
      commandText = ProviderConfigFile.formatCommandText(command),
      workingDir = ""
    )
    nextId++
    persist(
      updated = items + item,
      providedEnvs = mapOf(finalName to result.envHints.associate { it.name to it.value })
    )
    selectedId = item.id
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
      McpRegistrySearch(onAdd = { result -> onAddFromRegistry(result) })
      Spacer(Modifier.height(GradumSpacing.sml))
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

/**
 * Search box for the official online MCP registry: a width capped field
 * centered in the section, with the search action living inside it as a
 * trailing icon (a spinner while a request is in flight). Keystrokes are
 * debounced by [MCP_INPUT_DEBOUNCE_MS], and tapping the icon re-runs the
 * current query manually; either way the blocking HTTP call runs on
 * [Dispatchers.IO] and surfaces as a transient error line, an
 * empty result line, or a scrollable result panel capped at
 * [MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP]; each cut-off edge fades only while
 * content is still hidden beyond it and clears when that edge is reached.
 * Choosing a result hands it to [onAdd],
 * which writes a new entry into settings.json. Remote only servers render
 * with their import disabled.
 */
@Composable
private fun McpRegistrySearch(onAdd: (McpRegistryResult) -> Unit) {
  val searchState: TextFieldState = remember { TextFieldState() }
  val query: String = searchState.text.toString().trim()
  var results: List<McpRegistryResult>? by remember { mutableStateOf(value = null) }
  var failed: Boolean by remember { mutableStateOf(value = false) }
  var searching: Boolean by remember { mutableStateOf(value = false) }
  var searchTick: Int by remember { mutableStateOf(value = 0) }

  LaunchedEffect(query, searchTick) {
    if (query.isEmpty()) {
      results = null
      failed = false
      searching = false
      return@LaunchedEffect
    }
    delay(duration = MCP_INPUT_DEBOUNCE_MS.milliseconds)
    searching = true
    results = null
    failed = false
    val fetched: List<McpRegistryResult>? =
      withContext(context = Dispatchers.IO) {
        runCatching { McpRegistryClient.search(query = query) }.getOrNull()
      }
    results = fetched ?: emptyList()
    failed = fetched == null
    searching = false
  }

  Column {
    Box(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center
    ) {
      TextField(
        state = searchState,
        textStyle = JewelTheme.editorTextStyle,
        modifier = Modifier.width(MCP_FIELD_WIDTH_DP.dp),
        outline = Outline.None,
        placeholder = { Text(text = message("gradum.settings.mcp.registry.search")) },
        trailingIcon = {
          if (searching) {
            CircularProgressIndicator(modifier = Modifier.size(size = 16.dp))
          } else {
            Tooltip(
              tooltip = { Text(text = message("gradum.settings.mcp.registry.search.button")) }
            ) {
              Icon(
                key = GradumIcons.Search,
                contentDescription = message("gradum.settings.mcp.registry.search.button"),
                modifier = Modifier.clickable { searchTick++ }
              )
            }
          }
        }
      )
    }
    Spacer(Modifier.height(GradumSpacing.sm))

    val list: List<McpRegistryResult>? = results
    when {
      query.isEmpty() || list == null -> Unit
      failed -> Text(
        color = JewelTheme.globalColors.text.info,
        text = message("gradum.settings.mcp.registry.error")
      )

      list.isEmpty() -> Text(
        color = JewelTheme.globalColors.text.info,
        text = message("gradum.settings.mcp.registry.empty")
      )

      else -> {
        val scrollState = rememberScrollState()
        val scrollable: Boolean =
          scrollState.canScrollForward || scrollState.canScrollBackward
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP.dp)
            .then(
              if (scrollable)
                Modifier.verticalEdgeFade(
                  fadeTop = scrollState.canScrollBackward,
                  fadeBottom = scrollState.canScrollForward
                )
              else Modifier
            )
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .verticalScroll(scrollState)
              .padding(
                start = GradumSpacing.sm,
                top = GradumSpacing.sm,
                bottom = GradumSpacing.sm,
                end =
                  if (scrollable) MCP_REGISTRY_SCROLLBAR_RESERVE_DP.dp
                  else GradumSpacing.sm
              ),
            verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
          ) {
            list.forEach { result ->
              McpRegistryResultRow(result = result, onAdd = onAdd)
            }
          }
          if (scrollable) {
            VerticalScrollbar(
              scrollState = scrollState,
              modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
            )
          }
        }
      }
    }
  }
}

/**
 * One registry hit: icon + name + version chip + ecosystem badge + status
 * mark (the verified badge when the server is importable, the provider
 * row's failed-connection icon when it is not) and a two-line description.
 * Below them a meta line shows the installable package in the editor font
 * with the repository's blue ExternalLink right after it, then the
 * official publish month. The add icon stays in the trailing group;
 * remote-only servers get neither badge nor icon.
 */
@Composable
private fun McpRegistryResultRow(
  result: McpRegistryResult, onAdd: (McpRegistryResult) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    Column(
      modifier = Modifier.weight(weight = 1f),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
      ) {
        Icon(
          key = GradumIcons.Mcp,
          contentDescription = null
        )
        Text(
          fontWeight = FontWeight.SemiBold, text = result.shortName
        )
        result.version?.let { version -> McpVersionChip(version = version) }
        result.registryType?.let { type -> McpRegistryTypeBadge(registryType = type) }

        if (result.command != null) {
          Icon(
            key = GradumIcons.BadgeVerified,
            contentDescription = message("gradum.settings.mcp.badge.verified")
          )
        } else {
          Icon(
            key = AllIconsKeys.Vcs.Ignore_file,
            contentDescription = message("gradum.settings.mcp.registry.unsupported"),
          )
        }
      }
      Text(
        maxLines = 2,
        text = result.description,
        overflow = TextOverflow.Ellipsis,
        color = JewelTheme.globalColors.text.info,
        style = JewelTheme.typography.labelTextStyle
      )
      val hasMeta: Boolean =
        result.packageLine != null || result.repositoryUrl != null || result.publishedAt != null
      if (hasMeta) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
        ) {
          if (result.packageLine != null || result.repositoryUrl != null) {
            Row(
              modifier = Modifier.weight(weight = 1f, fill = false),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
            ) {
              result.packageLine?.let { line ->
                Text(
                  modifier = Modifier.weight(weight = 1f, fill = false),
                  text = line,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  color = JewelTheme.globalColors.text.info,
                  style = JewelTheme.typography.labelTextStyle,
                  fontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
                )
              }
              result.repositoryUrl?.let { url ->
                ExternalLink(
                  text = url,
                  onClick = { BrowserUtil.browse(url) }
                )
              }
            }
          }
          result.publishedAt?.let { published ->
            Text(
              text = message(key = "gradum.settings.mcp.registry.published", published),
              color = JewelTheme.globalColors.text.info,
              style = JewelTheme.typography.labelTextStyle
            )
          }
        }
      }
    }
    if (result.command != null) {
      Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.add")) }) {
        IconButton(onClick = { onAdd(result) }) {
          Icon(
            key = AllIconsKeys.General.Add,
            contentDescription = message("gradum.settings.mcp.add")
          )
        }
      }
    }
  }
}

@Composable
private fun McpVersionChip(version: String) {
  Badge(style = JewelTheme.badgeStyle.graySecondary) {
    Text(text = "v$version")
  }
}

@Composable
private fun McpRegistryTypeBadge(registryType: String) {
  Badge(style = JewelTheme.badgeStyle.blueSecondary) {
    Text(text = registryLabel(registryType = registryType))
  }
}

private fun registryLabel(registryType: String): String =
  when (registryType.lowercase()) {
    "pypi" -> "uv"
    "oci" -> "docker"
    else -> registryType.lowercase()
  }
