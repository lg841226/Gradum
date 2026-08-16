/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ApiProviderRow.kt  2026-08-16 10:38:41 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.provider.ProviderCoordinator
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderStatus
import gradum.idea.provider.isValidBaseUrl
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Duration.Companion.milliseconds

private val MASK_TRANSFORMATION: OutputTransformation = OutputTransformation {
  if (length > 0) replace(0, length, "•".repeat(length))
}

private const val LABEL_WIDTH_DP = 68
private const val URL_FIELD_WIDTH_DP = 400
private const val INPUT_DEBOUNCE_MS: Long = 500

/**
 * Settings row for a single model provider.
 *
 * Renders the URL field, API key field, optional extra toggle, a manual
 * probe button, and a status badge. All connection-state work is
 * delegated to [ProviderCoordinator] — this composable only reads the
 * latest [ProviderStatus] / `isTesting` flag and forwards user edits
 * through [onUrlChange] / [onApiKeyChange], both debounced by
 * [INPUT_DEBOUNCE_MS] so the coordinator is not reconfigured on every
 * keystroke.
 *
 * @param kind which provider this row edits.
 * @param baseUrlState Compose state backing the URL text field.
 * @param apiKeyState Compose state backing the API key text field.
 * @param onUrlChange invoked [INPUT_DEBOUNCE_MS] after the URL stops changing.
 * @param onApiKeyChange invoked [INPUT_DEBOUNCE_MS] after the API key stops changing.
 * @param extraToggle optional extra checkbox rendered below the API key field.
 * @param onExtraToggleChange invoked when [extraToggle] is toggled.
 */
@Composable
fun ApiProviderRow(
  kind: ProviderKind,
  baseUrlState: TextFieldState,
  apiKeyState: TextFieldState,
  onUrlChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  extraToggle: ExtraToggle? = null,
  onExtraToggleChange: ((Boolean) -> Unit)? = null,
) {
  val status: ProviderStatus by ProviderCoordinator.statusFlow(kind).collectAsState()
  val isTesting: Boolean by ProviderCoordinator.isTestingFlow(kind).collectAsState()
  val isUrlValid: Boolean = isValidBaseUrl(baseUrlState.text.toString())

  Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)) {
    Text(text = message(kind.displayKey), fontWeight = FontWeight.SemiBold)
    UrlField(kind = kind, state = baseUrlState, isUrlValid = isUrlValid, onUrlChange = onUrlChange)
    ApiKeyField(
      state = apiKeyState,
      onApiKeyChange = onApiKeyChange,
    )
    if (extraToggle != null && onExtraToggleChange != null) {
      SettingCheckboxRow(
        enabled = true,
        checked = extraToggle.checked,
        onCheckedChange = onExtraToggleChange,
        label = message(extraToggle.messageKey),
      )
    }
    ActionRow(
      isTesting = isTesting,
      isActionEnabled = isUrlValid,
      status = status,
      onProbeNow = { ProviderCoordinator.probeNow(kind) },
    )
  }
}

@Composable
private fun UrlField(
  kind: ProviderKind,
  state: TextFieldState,
  isUrlValid: Boolean,
  onUrlChange: (String) -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = message("gradum.settings.provider.url"),
      modifier = Modifier.width(LABEL_WIDTH_DP.dp),
    )
    TextField(
      state = state,
      modifier = Modifier.width(URL_FIELD_WIDTH_DP.dp),
      textStyle = JewelTheme.editorTextStyle,
      placeholder = { Text(message(urlPlaceholderKey(kind))) },
      outline = if (isUrlValid) Outline.None else Outline.Error,
    )
  }
  LaunchedEffect(state.text) {
    delay(INPUT_DEBOUNCE_MS.milliseconds)
    onUrlChange(state.text.toString())
  }
}

@Composable
private fun ApiKeyField(
  state: TextFieldState,
  onApiKeyChange: (String) -> Unit,
) {
  var isKeyVisible: Boolean by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = message("gradum.settings.provider.apikey"),
      modifier = Modifier.width(LABEL_WIDTH_DP.dp),
    )
    TextField(
      state = state,
      modifier = Modifier.width(URL_FIELD_WIDTH_DP.dp),
      textStyle = JewelTheme.editorTextStyle,
      outputTransformation = if (isKeyVisible) null else MASK_TRANSFORMATION,
      trailingIcon = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
          Tooltip(
            tooltip = {
              Text(
                text = message(
                  if (isKeyVisible) "gradum.settings.provider.apikey.hide"
                  else "gradum.settings.provider.apikey.show"
                )
              )
            }
          ) {
            Icon(
              key = if (isKeyVisible) AllIconsKeys.Actions.Show
              else AllIconsKeys.Actions.Unshare,
              contentDescription = message(
                if (isKeyVisible) "gradum.settings.provider.apikey.hide"
                else "gradum.settings.provider.apikey.show"
              ),
              modifier = Modifier.clickable { isKeyVisible = !isKeyVisible },
            )
          }
          Tooltip(tooltip = { Text(text = message("gradum.settings.provider.apikey.clear")) }) {
            Icon(
              key = AllIconsKeys.General.Delete,
              contentDescription = message("gradum.settings.provider.apikey.clear"),
              modifier = Modifier.clickable { state.edit { replace(0, length, "") } },
            )
          }
        }
      },
    )
  }
  LaunchedEffect(state.text) {
    delay(INPUT_DEBOUNCE_MS.milliseconds)
    onApiKeyChange(state.text.toString())
  }
}

@Composable
private fun ActionRow(
  isTesting: Boolean,
  isActionEnabled: Boolean,
  status: ProviderStatus,
  onProbeNow: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedButton(enabled = !isTesting && isActionEnabled, onClick = onProbeNow) {
      Text(text = message("gradum.settings.provider.detect.now"))
    }
    if (isTesting) CircularProgressIndicator(
      modifier = Modifier
        .width(16.dp)
        .padding(horizontal = GradumSpacing.md),
    )
    Spacer(Modifier.width(GradumSpacing.lg))
    StatusBadge(status = status)
  }
}

/** Renders an icon + localized text pair; hides itself for transient states. */
@Composable
private fun StatusBadge(status: ProviderStatus) {
  val (icon, text) = when (status) {
    is ProviderStatus.Ok -> AllIconsKeys.General.GreenCheckmark to message("gradum.settings.provider.status.ok", status.latencyMs)
    is ProviderStatus.Unreachable -> AllIconsKeys.Vcs.Ignore_file to message("gradum.settings.provider.status.unreachable")
    is ProviderStatus.AuthError -> AllIconsKeys.Vcs.Ignore_file to message("gradum.settings.provider.status.autherror")
    is ProviderStatus.Failed -> AllIconsKeys.Vcs.Ignore_file to message("gradum.settings.provider.status.failed")
    ProviderStatus.Untested, ProviderStatus.Testing -> return
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(key = icon, contentDescription = null)
    Spacer(Modifier.width(GradumSpacing.sm))
    Text(text = text)
  }
}

private fun urlPlaceholderKey(kind: ProviderKind): String = when (kind) {
  ProviderKind.OLLAMA -> "gradum.settings.provider.url.placeholder.ollama"
  ProviderKind.LM_STUDIO -> "gradum.settings.provider.url.placeholder.lmstudio"
}

/** Extra checkbox descriptor rendered below the API key field. */
data class ExtraToggle(val messageKey: String, val checked: Boolean)
