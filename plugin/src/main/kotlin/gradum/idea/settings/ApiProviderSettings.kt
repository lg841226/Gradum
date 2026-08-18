/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ApiProviderSettings.kt  2026-08-17 18:24:32 Changed by gwy
 */

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderCoordinator
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderSettings
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val POLL_FIELD_WIDTH_DP = 56
private const val MIN_POLL_INTERVAL_SECONDS = 2
private const val MAX_POLL_INTERVAL_SECONDS = 60

/**
 * Settings page section that owns both provider rows.
 *
 * Persists edits through [ProviderSettings] and mirrors the latest
 * configuration into [ProviderCoordinator] on first composition and on
 * every relevant change. Provider rows themselves are pure subscribers
 * — they do not run probes.
 */
@Composable
internal fun ApiProviderSettings() {
  val settings: ProviderSettings = remember { ProviderSettings.getInstance() }
  val state: ProviderSettings.State = settings.snapshot

  val ollamaKeyState = remember { TextFieldState(initialText = state.ollamaApiKey) }
  val ollamaUrlState = remember { TextFieldState(initialText = state.ollamaBaseUrl) }
  val lmStudioKeyState = remember { TextFieldState(initialText = state.lmStudioApiKey) }
  val lmStudioUrlState = remember { TextFieldState(initialText = state.lmStudioBaseUrl) }

  LaunchedEffect(Unit) {
    pushAllKindsToCoordinator(settings)
    ProviderConfigFile.updateAllowRemote("lmstudio", state.lmStudioAllowRemote)
  }

  // Spacing is controlled explicitly here (not via a uniform spacedBy):
  // header + auto-detect get a compact md gap, while the three provider
  // panels (Ollama / LM Studio / Cloud) are separated by a wider lg gap.
  Column {
    GroupHeader(
      modifier = Modifier.fillMaxWidth(),
      text = message("gradum.settings.provider.section")
    )
    Spacer(Modifier.height(GradumSpacing.md))
    AutoDetectRow(
      enabled = state.autoDetectEnabled,
      checked = state.autoDetectEnabled,
      intervalSeconds = state.pollIntervalSeconds,
      onCheckedChange = { isChecked ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.autoDetectEnabled = isChecked
        }
        settings.update(transform, persistToDisk = false)
        pushAllKindsToCoordinator(settings)
      },
      onIntervalChange = { seconds ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.pollIntervalSeconds = seconds
        }
        settings.update(transform, persistToDisk = false)
        pushAllKindsToCoordinator(settings)
      },
    )
    Spacer(Modifier.height(GradumSpacing.ml))
    ApiProviderRow(
      kind = ProviderKind.OLLAMA,
      apiKeyState = ollamaKeyState,
      baseUrlState = ollamaUrlState,
      onUrlChange = { newUrl ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.setBaseUrl(ProviderKind.OLLAMA, newUrl)
        }
        settings.update(transform)
        pushKindToCoordinator(settings, ProviderKind.OLLAMA)
      },
      onApiKeyChange = { newKey ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.setApiKey(ProviderKind.OLLAMA, newKey)
        }
        settings.update(transform)
        pushKindToCoordinator(settings, ProviderKind.OLLAMA)
      },
      extraToggle = ExtraToggle(
        checked = state.ollamaAutoFilter,
        messageKey = "gradum.settings.provider.ollama.autofilter"
      ),
      onExtraToggleChange = { isChecked ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.ollamaAutoFilter = isChecked
        }
        settings.update(transform, persistToDisk = false)
      },
    )

    Spacer(Modifier.height(GradumSpacing.ml))

    ApiProviderRow(
      kind = ProviderKind.LM_STUDIO,
      apiKeyState = lmStudioKeyState,
      baseUrlState = lmStudioUrlState,
      onUrlChange = { newUrl ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.setBaseUrl(ProviderKind.LM_STUDIO, newUrl)
        }
        settings.update(transform)
        pushKindToCoordinator(settings, ProviderKind.LM_STUDIO)
      },
      onApiKeyChange = { newKey ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.setApiKey(ProviderKind.LM_STUDIO, newKey)
        }
        settings.update(transform)
        pushKindToCoordinator(settings, ProviderKind.LM_STUDIO)
      },
      extraToggle = ExtraToggle(
        messageKey = "gradum.settings.provider.lmstudio.allowremote",
        checked = state.lmStudioAllowRemote,
      ),
      onExtraToggleChange = { isChecked ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.lmStudioAllowRemote = isChecked
        }
        settings.update(transform, persistToDisk = false)
        ProviderConfigFile.updateAllowRemote("lmstudio", isChecked)
      },
    )

    Spacer(Modifier.height(GradumSpacing.ml))

    CloudProviderTabsSection(
      settings = settings,
      enabledKinds = ProviderKind.cloudKinds.filter { state.isEnabled(it) },
    )
  }
}

/**
 * Stable [LazyRow] key for the trailing "+" add-provider chip, so it is
 * not treated as a data item and never runs the removal animation.
 */
private const val CLOUD_TAB_ADD_KEY = "cloud-tab-add"

/**
 * Cloud-provider section rendered below the two fixed local providers.
 *
 * The user's cloud providers (Zhipu / DeepSeek / MiniMax) are organized
 * as removable capsule tabs. Clicking a tab selects it (outlined) and
 * shows its [ApiProviderRow] panel; the trailing "+" opens a [PopupMenu]
 * listing the not-yet-added providers. Removing a tab deletes its config
 * and shifts the selection one tab to the left; an empty tab strip shows
 * a hint instead of a panel.
 */
@Composable
private fun CloudProviderTabsSection(
  settings: ProviderSettings, enabledKinds: List<ProviderKind>
) {
  val state: ProviderSettings.State = settings.snapshot
  var selectedKind by remember { mutableStateOf(enabledKinds.firstOrNull()) }
  val currentSelection: ProviderKind? = selectedKind?.takeIf { it in enabledKinds }
  val activeKind: ProviderKind? = currentSelection ?: enabledKinds.firstOrNull()
  var showAddMenu by remember { mutableStateOf(false) }
  val addableKinds: List<ProviderKind> = ProviderKind.cloudKinds.filterNot { it in enabledKinds }

  val keyState = activeKind?.let {
    remember(it) { TextFieldState(initialText = state.configFor(it).second) }
  }
  val urlState = activeKind?.let {
    remember(it) { TextFieldState(initialText = state.configFor(it).first) }
  }

  Text(
    fontWeight = FontWeight.SemiBold,
    text = message("gradum.settings.provider.cloudtabs.title")
  )

  Spacer(Modifier.height(GradumSpacing.sml))

  LazyRow(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    items(enabledKinds, key = { it }) { kind ->
      CloudTab(
        kind = kind,
        isSelected = kind == activeKind,
        onClick = { selectedKind = kind },
        onRemove = {
          selectedKind = removeCloudProvider(settings, enabledKinds, kind)
        },
        modifier = Modifier.animateItem(
          fadeInSpec = tween(durationMillis = 150),
          fadeOutSpec = tween(durationMillis = 150),
          placementSpec = tween(durationMillis = 200)
        ),
      )
    }
    item(key = CLOUD_TAB_ADD_KEY) {
      Box {
        CloudAddIcon(
          onClick = { showAddMenu = true },
          enabled = addableKinds.isNotEmpty()
        )
        if (showAddMenu) {
          PopupMenu(
            horizontalAlignment = Alignment.Start,
            onDismissRequest = { showAddMenu = false; true }
          ) {
            addableKinds.forEach { kind ->
              selectableItem(
                selected = false,
                onClick = {
                  showAddMenu = false
                  val transform: (ProviderSettings.State) -> Unit = { s ->
                    s.setEnabled(kind, true)
                    s.setApiKey(kind, state.configFor(kind).second)
                    s.setBaseUrl(kind, state.configFor(kind).first.ifBlank { kind.defaultBaseUrl })
                  }
                  settings.update(transform)
                  pushKindToCoordinator(settings, kind)
                  selectedKind = kind
                },
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    key = kind.icon ?: GradumIcons.Cloud
                  )
                  Spacer(Modifier.width(GradumSpacing.sml))
                  Text(text = message(kind.displayKey))
                }
              }
            }
          }
        }
      }
    }
  }

  Spacer(Modifier.height(GradumSpacing.sml))

  if (activeKind == null) {
    Text(
      color = JewelTheme.globalColors.text.info,
      text = message("gradum.settings.provider.cloudtabs.empty")
    )
  } else {
    ApiProviderRow(
      kind = activeKind,
      apiKeyState = keyState!!,
      baseUrlState = urlState!!,
      showTitle = false,
      onUrlChange = { newUrl ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.setBaseUrl(activeKind, newUrl)
        }
        settings.update(transform)
        pushKindToCoordinator(settings, activeKind)
      },
      onApiKeyChange = { newKey ->
        val transform: (ProviderSettings.State) -> Unit = { s ->
          s.setApiKey(activeKind, newKey)
        }
        settings.update(transform)
        pushKindToCoordinator(settings, activeKind)
      },
    )
  }
}

/**
 * Removes [kind] from the cloud-provider tab strip.
 *
 * Deletes its persisted config (URL / API key / enable flag) so the
 * embedded server stops probing it, stops the coordinator's poll loop,
 * and returns the next selection for the tab strip — the tab to the
 * left of the removed one, or `null` when no tab remains.
 */
private fun removeCloudProvider(
  settings: ProviderSettings, enabledKinds: List<ProviderKind>, kind: ProviderKind
): ProviderKind? {
  val index: Int = enabledKinds.indexOf(kind)
  val snapshot: ProviderSettings.State = settings.snapshot
  val transform: (ProviderSettings.State) -> Unit = { s ->
    s.setEnabled(kind, false)
    s.setBaseUrl(kind, "")
    s.setApiKey(kind, "")
  }
  settings.update(transform)
  ProviderCoordinator.reconfigure(
    kind = kind,
    apiKey = "",
    baseUrl = "",
    autoDetect = snapshot.autoDetectEnabled,
    pollIntervalMs = snapshot.pollIntervalSeconds * 1000L
  )
  val remaining: List<ProviderKind> = enabledKinds - kind
  return if (remaining.isEmpty()) null
  else remaining.getOrNull((index - 1).coerceAtLeast(0)) ?: remaining.last()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudTab(
  kind: ProviderKind, isSelected: Boolean, onClick: () -> Unit, onRemove: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .background(
        shape = RoundedCornerShape(80),
        color = if (isSelected) JewelTheme.globalColors.toolwindowBackground
        else Color.Transparent
      )
      .then(
        if (isSelected) Modifier.border(
          width = 1.dp,
          shape = RoundedCornerShape(80),
          color = JewelTheme.globalColors.borders.normal
        ) else Modifier
      )
      .padding(vertical = GradumSpacing.sm, horizontal = GradumSpacing.md)
      .clickable(onClick = onClick)
  ) {
    Icon(
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      key = kind.icon ?: GradumIcons.Cloud
    )
    Spacer(Modifier.width(GradumSpacing.sml))
    Text(text = message(kind.displayKey))
    Spacer(Modifier.width(GradumSpacing.md))
    Tooltip(
      tooltip = { Text(text = message("gradum.settings.provider.cloudtabs.remove")) }
    ) {
      Icon(
        key = AllIconsKeys.General.Close,
        modifier = Modifier.clickable(onClick = onRemove).size(12.dp),
        contentDescription = message("gradum.settings.provider.cloudtabs.remove")
      )
    }
  }
}

/**
 * Trailing "+" chip that opens the add-provider menu. Disabled (and
 * non-interactive) when every cloud provider is already added.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudAddIcon(enabled: Boolean, onClick: () -> Unit) {
  Tooltip(
    tooltip = { Text(text = message("gradum.settings.provider.cloudtabs.add")) }
  ) {
    IconButton(
      onClick = onClick, enabled = enabled,
      modifier = Modifier.padding(GradumSpacing.xs)
    ) {
      Icon(
        key = AllIconsKeys.General.Add,
        contentDescription = message("gradum.settings.provider.cloudtabs.add")
      )
    }
  }
}

/**
 * Auto-detect row with its scope hint.
 *
 * Renders as `Automatically check connection status when opening Gradum,
 * poll every [n] s` followed by an info-colored scope hint.
 * The field is disabled while auto-detect is off ([enabled] = false). Input handling:
 *
 * - while focused, invalid text (blank, non-numeric, out of range) shows
 *   a red error outline but is never touched mid-edit;
 * - on focus loss, blank/non-numeric text reverts to the last valid
 *   interval and out-of-range values are clamped to 1..60;
 * - valid values are pushed up through onIntervalChange and persisted.
 */
@Composable
private fun AutoDetectRow(
  enabled: Boolean,
  checked: Boolean,
  intervalSeconds: Int,
  onIntervalChange: (Int) -> Unit,
  onCheckedChange: (Boolean) -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  var isInputValid by remember { mutableStateOf(true) }
  var lastValidSeconds by remember { mutableStateOf(intervalSeconds) }
  val state = remember { TextFieldState(initialText = intervalSeconds.toString()) }

  fun validate(): Boolean {
    val raw: String = state.text.toString().trim()
    return raw.toIntOrNull()?.let {
      it in MIN_POLL_INTERVAL_SECONDS..MAX_POLL_INTERVAL_SECONDS
    } == true
  }

  fun normalizeAndCommit() {
    val rawInput: String = state.text.toString().trim()
    val parsedSeconds: Int? = rawInput.toIntOrNull()
    val clampedSeconds: Int = when {
      parsedSeconds == null -> lastValidSeconds
      else -> parsedSeconds.coerceIn(MIN_POLL_INTERVAL_SECONDS, MAX_POLL_INTERVAL_SECONDS)
    }
    val normalizedText: String = clampedSeconds.toString()
    if (state.text.toString() != normalizedText)
      state.edit { replace(0, length, normalizedText) }

    lastValidSeconds = clampedSeconds
    isInputValid = true
    if (clampedSeconds != intervalSeconds) onIntervalChange(clampedSeconds)
  }

  LaunchedEffect(state.text, isFocused) {
    if (isFocused) isInputValid = validate()
  }

  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange
      )
      Spacer(Modifier.width(GradumSpacing.sm))
      Text(text = message("gradum.settings.provider.autodetect.enable"))
      Spacer(Modifier.width(GradumSpacing.sml))
      TextField(
        state = state,
        enabled = enabled,
        modifier = Modifier
          .width(POLL_FIELD_WIDTH_DP.dp)
          .onFocusChanged { focusState ->
            if (isFocused && !focusState.isFocused)
              normalizeAndCommit()
            isFocused = focusState.isFocused
          },
        outline = if (enabled && !isInputValid) Outline.Error else Outline.None,
        placeholder = { Text("$MIN_POLL_INTERVAL_SECONDS~$MAX_POLL_INTERVAL_SECONDS") }
      )
      Spacer(Modifier.width(GradumSpacing.sml))
      Text(text = message("gradum.settings.provider.autodetect.poll.unit"))
    }
    Spacer(Modifier.height(GradumSpacing.xs))
    Text(
      color = JewelTheme.globalColors.text.info,
      text = message("gradum.settings.provider.autodetect.scope")
    )
  }
}

private fun pushAllKindsToCoordinator(settings: ProviderSettings) {
  val snapshot: ProviderSettings.State = settings.snapshot
  ProviderKind.localKinds.forEach { kind -> pushKindToCoordinator(settings, kind) }
  ProviderKind.cloudKinds
    .filter { snapshot.isEnabled(it) }
    .forEach { kind -> pushKindToCoordinator(settings, kind) }
}

private fun pushKindToCoordinator(settings: ProviderSettings, kind: ProviderKind) {
  val snapshot: ProviderSettings.State = settings.snapshot
  val (currentUrl, currentKey) = snapshot.configFor(kind)
  ProviderCoordinator.reconfigure(
    kind = kind,
    apiKey = currentKey,
    baseUrl = currentUrl,
    autoDetect = snapshot.autoDetectEnabled,
    pollIntervalMs = snapshot.pollIntervalSeconds * 1000L
  )
}
