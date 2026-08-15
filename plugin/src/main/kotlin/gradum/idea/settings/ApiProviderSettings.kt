/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ApiProviderSettings.kt  2026-08-15 19:53:31 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderSettings
import gradum.idea.provider.ProviderStatus
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.GroupHeader

@Composable
internal fun ApiProviderSettings() {
  val settings = remember { ProviderSettings.getInstance() }
  val state = settings.snapshot

  val ollamaUrlState = remember { TextFieldState(initialText = state.ollamaBaseUrl) }
  val ollamaKeyState = remember { TextFieldState(initialText = state.ollamaApiKey) }
  val lmStudioUrlState = remember { TextFieldState(initialText = state.lmStudioBaseUrl) }
  val lmStudioKeyState = remember { TextFieldState(initialText = state.lmStudioApiKey) }

  var ollamaStatus: ProviderStatus by remember { mutableStateOf(ProviderStatus.Untested) }
  var ollamaTesting: Boolean by remember { mutableStateOf(false) }
  var lmStudioStatus: ProviderStatus by remember { mutableStateOf(ProviderStatus.Untested) }
  var lmStudioTesting: Boolean by remember { mutableStateOf(false) }

  val pollIntervalMs: Long = if (state.autoDetectEnabled) {
    state.pollIntervalSeconds * 1000L
  } else 0L

  GroupHeader(
    text = message("gradum.settings.provider.section"),
    modifier = Modifier.fillMaxWidth()
  )
  SettingCheckboxRow(
    enabled = true,
    checked = state.autoDetectEnabled,
    label = message("gradum.settings.provider.autodetect.enable"),
    onCheckedChange = { settings.update { s -> s.autoDetectEnabled = it } }
  )
  ApiProviderRow(
    kind = ProviderKind.OLLAMA,
    isTesting = ollamaTesting,
    pollIntervalMs = pollIntervalMs,
    status = ollamaStatus,
    apiKeyState = ollamaKeyState,
    baseUrlState = ollamaUrlState,
    onUrlChange = { settings.update { s -> s.ollamaBaseUrl = it } },
    onApiKeyChange = { settings.update { s -> s.ollamaApiKey = it } },
    onTestingChange = { ollamaTesting = it },
    onStatusChange = { ollamaStatus = it },
    extraToggle = ExtraToggle(
      messageKey = "gradum.settings.provider.ollama.autofilter",
      checked = state.ollamaAutoFilter
    ),
  ) { settings.update { s -> s.ollamaAutoFilter = it } }

  Spacer(Modifier.height(GradumSpacing.lg))

  ApiProviderRow(
    kind = ProviderKind.LM_STUDIO,
    isTesting = lmStudioTesting,
    pollIntervalMs = pollIntervalMs,
    status = lmStudioStatus,
    apiKeyState = lmStudioKeyState,
    baseUrlState = lmStudioUrlState,
    onUrlChange = { settings.update { s -> s.lmStudioBaseUrl = it } },
    onApiKeyChange = { settings.update { s -> s.lmStudioApiKey = it } },
    onTestingChange = { lmStudioTesting = it },
    onStatusChange = { lmStudioStatus = it },
    extraToggle = ExtraToggle(
      messageKey = "gradum.settings.provider.lmstudio.allowremote",
      checked = state.lmStudioAllowRemote
    ),
  ) { settings.update { s -> s.lmStudioAllowRemote = it } }
}
