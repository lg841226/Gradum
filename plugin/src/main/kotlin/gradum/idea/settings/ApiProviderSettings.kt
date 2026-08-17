/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ApiProviderSettings.kt  2026-08-16 19:48:31 Changed by gwy
 */

package gradum.idea.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderCoordinator
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderSettings
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

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

  GroupHeader(
    modifier = Modifier.fillMaxWidth(),
    text = message("gradum.settings.provider.section")
  )
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
  Text(
    color = JewelTheme.globalColors.text.info,
    text = message("gradum.settings.provider.autodetect.scope")
  )
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

  Spacer(Modifier.height(GradumSpacing.md))

  ApiProviderRow(
    kind = ProviderKind.LM_STUDIO,
    baseUrlState = lmStudioUrlState,
    apiKeyState = lmStudioKeyState,
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
}

/**
 * One-line auto-detect row: checkbox + label + poll-interval field.
 *
 * Renders as `Automatically check connection status when opening Gradum, poll every [n] s`.
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
}

private fun pushAllKindsToCoordinator(settings: ProviderSettings) {
  ProviderKind.entries.forEach { kind -> pushKindToCoordinator(settings, kind) }
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
