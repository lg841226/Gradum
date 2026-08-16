/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ApiProviderSettings.kt  2026-08-16 01:17:28 Changed by gwy
 */

package gradum.idea.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import gradum.idea.provider.ProviderCoordinator
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderSettings
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.GroupHeader

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

  val ollamaUrlState = remember { TextFieldState(initialText = state.ollamaBaseUrl) }
  val ollamaKeyState = remember { TextFieldState(initialText = state.ollamaApiKey) }
  val lmStudioUrlState = remember { TextFieldState(initialText = state.lmStudioBaseUrl) }
  val lmStudioKeyState = remember { TextFieldState(initialText = state.lmStudioApiKey) }

  LaunchedEffect(Unit) { pushAllKindsToCoordinator(settings) }

  GroupHeader(
    text = message("gradum.settings.provider.section"),
    modifier = Modifier.fillMaxWidth(),
  )
  SettingCheckboxRow(
    enabled = true,
    checked = state.autoDetectEnabled,
    label = message("gradum.settings.provider.autodetect.enable"),
    onCheckedChange = { isChecked ->
      val transform: (ProviderSettings.State) -> Unit = { s -> s.autoDetectEnabled = isChecked }
      settings.update(transform, persistToDisk = false)
      pushAllKindsToCoordinator(settings)
    },
  )
  ApiProviderRow(
    kind = ProviderKind.OLLAMA,
    baseUrlState = ollamaUrlState,
    apiKeyState = ollamaKeyState,
    onUrlChange = { newUrl ->
      val transform: (ProviderSettings.State) -> Unit = { s -> s.setBaseUrl(ProviderKind.OLLAMA, newUrl) }
      settings.update(transform)
      pushKindToCoordinator(settings, ProviderKind.OLLAMA)
    },
    onApiKeyChange = { newKey ->
      val transform: (ProviderSettings.State) -> Unit = { s -> s.setApiKey(ProviderKind.OLLAMA, newKey) }
      settings.update(transform)
      pushKindToCoordinator(settings, ProviderKind.OLLAMA)
    },
    extraToggle = ExtraToggle(
      messageKey = "gradum.settings.provider.ollama.autofilter",
      checked = state.ollamaAutoFilter,
    ),
    onExtraToggleChange = { isChecked ->
      val transform: (ProviderSettings.State) -> Unit = { s -> s.ollamaAutoFilter = isChecked }
      settings.update(transform, persistToDisk = false)
    },
  )

  Spacer(Modifier.height(GradumSpacing.lg))

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
      val transform: (ProviderSettings.State) -> Unit = { s -> s.lmStudioAllowRemote = isChecked }
      settings.update(transform, persistToDisk = false)
    },
  )
}

private fun pushAllKindsToCoordinator(settings: ProviderSettings) {
  ProviderKind.entries.forEach { kind -> pushKindToCoordinator(settings, kind) }
}

private fun pushKindToCoordinator(
  settings: ProviderSettings,
  kind: ProviderKind,
) {
  val snapshot: ProviderSettings.State = settings.snapshot
  val (currentUrl, currentKey) = snapshot.configFor(kind)
  ProviderCoordinator.reconfigure(
    kind = kind,
    baseUrl = currentUrl,
    apiKey = currentKey,
    pollIntervalMs = snapshot.pollIntervalSeconds * 1000L,
    autoDetect = snapshot.autoDetectEnabled,
  )
}
