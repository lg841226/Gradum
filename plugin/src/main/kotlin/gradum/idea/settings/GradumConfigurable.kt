/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumConfigurable.kt  2026-08-15 19:00:00 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.scanCompletedAgo
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import javax.swing.JComponent
import kotlin.time.Duration.Companion.milliseconds

private val SETTINGS_PANEL_MAX_HEIGHT = 720.dp

/**
 * IntelliJ settings entry point for Gradum.
 *
 * Implements [Configurable.NoScroll] so the IDE does not wrap the
 * Compose root in its own scroll pane; the [SettingsPanel] composable
 * owns its own vertical scroll capped at [SETTINGS_PANEL_MAX_HEIGHT]
 * to keep the layout bounded even when a device pixel ratio is huge.
 */
class GradumConfigurable : Configurable, Configurable.NoScroll {

  override fun getDisplayName(): String = "Gradum"

  override fun createComponent(): JComponent = JewelComposePanel {
    SettingsPanel()
  }

  override fun isModified(): Boolean = false

  override fun apply() {}

  override fun reset() {}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsPanel() {
  val gitEnabled = remember { mutableStateOf(true) }
  val agentEnabled = remember { mutableStateOf(true) }
  var checkingUpdate by remember { mutableStateOf(false) }
  var versionsCopied by remember { mutableStateOf(false) }
  val autoOpenInEditor = remember { mutableStateOf(false) }
  var lastCheckedAt by remember { mutableStateOf(0L) }

  val updateScope = rememberCoroutineScope()
  val copyScope = rememberCoroutineScope()
  val versionSummary = remember {
    buildString {
      appendLine(message("gradum.settings.devtools") + " 2026.0730.383-beta")
      appendLine("Gradum Agent (0.9.2.3293)")
      append("Gradum Git Analysis (1.1.0.2388)")
    }
  }
  val onCopyVersions = {
    copyToClipboard(
      text = versionSummary,
      onCopied = { versionsCopied = true },
      onReset = { versionsCopied = false },
      scope = copyScope
    )
  }
  val onCheckForUpdates = {
    if (!checkingUpdate) {
      checkingUpdate = true
      updateScope.launch {
        delay(1500.milliseconds)
        checkingUpdate = false
        lastCheckedAt = System.currentTimeMillis()
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = SETTINGS_PANEL_MAX_HEIGHT)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = GradumSpacing.lg),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
  ) {
    GroupHeader(
      text = message("gradum.settings.main.and.updates")
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        contentDescription = null,
        key = GradumIcons.ColorLogo,
        modifier = Modifier.size(28.dp)
      )
      Spacer(Modifier.width(GradumSpacing.lg))
      Column(
        verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs),
        modifier = Modifier.weight(1f)
      ) {
        Text(
          text = message("gradum.settings.devtools"),
          fontWeight = FontWeight.SemiBold
        )
        Text(
          text = "2026.0730.383-beta",
          color = JewelTheme.globalColors.text.info
        )
      }
      Tooltip(
        tooltip = { Text(text = message("gradum.settings.copy.tooltip")) },
      ) {
        IconButton(onClick = { onCopyVersions() }) {
          Icon(
            key = if (versionsCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy,
            contentDescription = message("gradum.settings.copy.tooltip"),
          )
        }
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    ) {
      Tooltip(tooltip = { Text(text = message("gradum.settings.oss.tooltip")) }) {
        ExternalLink(
          text = message("gradum.settings.oss.link"),
          onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
        )
      }
      Tooltip(tooltip = { Text(text = message("gradum.settings.repo.tooltip")) }) {
        ExternalLink(
          text = message("gradum.settings.repo.link"),
          onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
        )
      }
    }
    Column {
      Text(text = message("gradum.settings.components.title"))
      Spacer(Modifier.height(GradumSpacing.md))
      Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
        SettingCheckboxRow(
          label = "Gradum Agent (0.9.2.3293)",
          checked = agentEnabled.value,
          enabled = true,
          onCheckedChange = { agentEnabled.value = it }
        )
        SettingCheckboxRow(
          label = "Gradum Git Analysis (1.1.0.2388)",
          checked = gitEnabled.value,
          enabled = true,
          onCheckedChange = { gitEnabled.value = it }
        )
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
    ) {
      OutlinedButton(
        enabled = !checkingUpdate,
        onClick = { onCheckForUpdates() }
      ) {
        Text(text = message("gradum.settings.update.now"))
      }
      if (checkingUpdate) CircularProgressIndicator(modifier = Modifier.size(16.dp))

      if (lastCheckedAt > 0L) {
        Text(
          color = JewelTheme.globalColors.text.info,
          text = message("gradum.settings.update.checked", scanCompletedAgo(lastCheckedAt))
        )
      }
    }
    SettingCheckboxRow(
      enabled = true,
      checked = autoOpenInEditor.value,
      label = message("gradum.settings.autoopen"),
      onCheckedChange = { autoOpenInEditor.value = it }
    )
    ApiProviderSettings()
    GroupHeader(
      text = "Appearance and Behavior"
    )
  }
}
