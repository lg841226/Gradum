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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import gradum.idea.PluginConfig
import gradum.idea.mcp.McpRegistryResult
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderConfigFile.McpServerEntry
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import gradum.idea.utils.findExecutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val MCP_TAB_ADD_KEY = "mcp-tab-add"
internal const val MCP_FIELD_WIDTH_DP: Int = 480
internal const val MCP_INPUT_DEBOUNCE_MS: Long = PluginConfig.INPUT_DEBOUNCE_MS
private const val NOTIFICATION_GROUP_ID: String = "Gradum"

/**
 * Settings page section for the `mcpServers` array in
 * `../.gradum/settings.json`.
 *
 * Mirrors the cloud-provider tabs layout: servers are removable capsule
 * tabs in a horizontal strip with a trailing add button; the
 * selected server's form (name / command / working dir) renders
 * underneath. Every field edit is debounced by [MCP_INPUT_DEBOUNCE_MS]
 * and written straight to the settings file through [ProviderConfigFile]:
 * there is no Apply button and no second copy of the config, so the file
 * stays the single source of truth. Environment variables have no input
 * field here: their row opens settings.json itself in the IDE editor
 * (caret on the selected server), and each panel write rereads env maps
 * from disk so the two surfaces never clobber each other. A search box on
 * top queries the official online registry ([gradum.idea.mcp.McpRegistryClient]) and
 * imports stdio servers as new tabs. The embedded server reads
 * `mcpServers` only at startup, so the section ends with a restart hint
 * instead of a connection test.
 */
@Composable
internal fun McpServersSettings() {
  val loaded: List<McpServerItem> = remember {
    ProviderConfigFile.readMcpServers().mapIndexed { index, entry -> entry.toItem(id = index.toLong()) }
  }
  var pendingNameFocus: Boolean by remember { mutableStateOf(value = false) }
  var items: List<McpServerItem> by remember { mutableStateOf(value = loaded) }
  var selectedId: Long? by remember { mutableStateOf(value = loaded.firstOrNull()?.id) }
  var nextId: Long by remember { mutableStateOf(value = (loaded.maxOfOrNull { it.id } ?: -1L) + 1) }
  var missingRuntime: RuntimeRequirement? by remember { mutableStateOf(value = null) }

  val selectedIndex: Int = items.indexOfFirst { it.id == selectedId }
  val selected: McpServerItem? = items.getOrNull(selectedIndex)

  LaunchedEffect(selected?.commandText) {
    val requirement: RuntimeRequirement? = runtimeRequirementFor(commandText = selected?.commandText ?: "")
    val found: Boolean =
      requirement != null &&
        withContext(Dispatchers.IO) { findExecutable(executable = requirement.executable) }
    missingRuntime = if (found) null else requirement
  }

  /**
   * Writes the working copy back to the file. Env maps are not part of
   * the working copy: they are edited directly in settings.json through
   * the form's open-in-editor button, so every write re-reads them from
   * disk by name and splices them into the entries being saved: a
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
    notifyServerAdded(name = item.name)
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
   * settings.json immediately like any other edit: the embedded server
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
      providedEnvs = mapOf(
        finalName to result.envHints.associate { it.name to it.value })
    )
    selectedId = item.id
    notifyServerAdded(name = finalName)
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
        val warning: RuntimeRequirement? = missingRuntime
        if (warning != null) {
          RuntimeMissingBanner(requirement = warning)
          Spacer(Modifier.height(GradumSpacing.sml))
        }
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
 * Removable capsule tab for one MCP server: a direct copy of the
 * cloud-provider tab: click selects the server, the trailing "x"
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
 * Bottom-right balloon after a server lands in settings.json: the
 * embedded runtime reads `mcpServers` only at startup, so the entry
 * stays inert until the runtime restarts. Without an open project
 * (settings opened from the welcome screen) there is no window to
 * anchor the balloon on and the restart hint under the form already
 * says the same, so the notification is skipped.
 */
private fun notifyServerAdded(name: String) {
  val project: Project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
  val notification = NotificationGroupManager.getInstance()
    .getNotificationGroup(NOTIFICATION_GROUP_ID)
    .createNotification(
      title = message("gradum.settings.mcp.added.title", name),
      content = message("gradum.settings.mcp.added.restart"),
      type = NotificationType.INFORMATION
    )
  notification.notify(project)
}
