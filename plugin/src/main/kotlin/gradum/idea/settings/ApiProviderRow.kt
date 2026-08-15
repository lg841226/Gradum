/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ApiProviderRow.kt  2026-08-15 20:49:58 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.provider.ProviderKind
import gradum.idea.provider.ProviderProbe
import gradum.idea.provider.ProviderStatus
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import kotlin.time.Duration.Companion.milliseconds

private val PROBE: ProviderProbe = ProviderProbe()

private val MASK_TRANSFORMATION: OutputTransformation = OutputTransformation {
  val inputLength: Int = length
  if (inputLength > 0) replace(0, inputLength, "•".repeat(inputLength))
}

private const val INPUT_WIDTH_DP = 400

private fun isValidBaseUrl(value: String): Boolean {
  val trimmed = value.trim()
  if (trimmed.isEmpty()) return false
  if (!trimmed.contains("://")) return false
  val scheme = trimmed.substringBefore("://")
  return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}

private fun urlPlaceholderKey(kind: ProviderKind): String = when (kind) {
  ProviderKind.OLLAMA -> "gradum.settings.provider.url.placeholder.ollama"
  ProviderKind.LM_STUDIO -> "gradum.settings.provider.url.placeholder.lmstudio"
}

private fun defaultUrl(kind: ProviderKind): String = when (kind) {
  ProviderKind.OLLAMA -> "http://localhost:11434"
  ProviderKind.LM_STUDIO -> "http://localhost:1234"
}

private fun urlCompletion(kind: ProviderKind, current: String): String {
  val trimmed = current.trim()
  if (trimmed.isEmpty()) return defaultUrl(kind)
  return if (trimmed.endsWith("localhost:")) defaultUrl(kind) else trimmed
}

@Composable
fun ApiProviderRow(
  kind: ProviderKind,
  isTesting: Boolean,
  pollIntervalMs: Long,
  status: ProviderStatus,
  apiKeyState: TextFieldState,
  baseUrlState: TextFieldState,
  onUrlChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onTestingChange: (Boolean) -> Unit,
  onStatusChange: (ProviderStatus) -> Unit,
  showApiKeyField: Boolean = true,
  extraToggle: ExtraToggle? = null,
  onExtraToggleChange: ((Boolean) -> Unit)? = null,
) {
  val probeScope = rememberCoroutineScope()
  var apiKeyVisible: Boolean by remember { mutableStateOf(false) }

  LaunchedEffect(baseUrlState.text) { onUrlChange(baseUrlState.text.toString()) }
  LaunchedEffect(apiKeyState.text) { onApiKeyChange(apiKeyState.text.toString()) }

  LaunchedEffect(pollIntervalMs, kind) {
    if (pollIntervalMs <= 0L) return@LaunchedEffect
    while (true) {
      runProbe(
        kind = kind,
        scope = probeScope,
        apiKeyState = apiKeyState,
        baseUrlState = baseUrlState,
        onStatusChange = onStatusChange,
        onTestingChange = onTestingChange
      )
      delay(pollIntervalMs.milliseconds)
    }
  }

  val triggerProbe: () -> Unit = {
    runProbe(
      kind = kind,
      scope = probeScope,
      apiKeyState = apiKeyState,
      baseUrlState = baseUrlState,
      onStatusChange = onStatusChange,
      onTestingChange = onTestingChange
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)) {
    Text(
      text = message(kind.displayKey),
      fontWeight = FontWeight.SemiBold
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = message("gradum.settings.provider.url"),
        modifier = Modifier.width(60.dp)
      )
      TextField(
        state = baseUrlState,
        modifier = Modifier
          .width(INPUT_WIDTH_DP.dp)
          .onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (keyEvent.key != Key.L || !keyEvent.isMetaPressed) return@onPreviewKeyEvent false
            val completion = urlCompletion(kind, baseUrlState.text.toString())
            if (completion == baseUrlState.text.toString()) return@onPreviewKeyEvent false
            baseUrlState.edit {
              replace(0, length, completion)
              placeCursorAfterCharAt(length)
            }
            true
          },
        textStyle = JewelTheme.typography.editorTextStyle,
        placeholder = { Text(message(urlPlaceholderKey(kind))) },
        outline = if (isValidBaseUrl(baseUrlState.text.toString())) Outline.None else Outline.Error
      )
    }
    if (showApiKeyField) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = message("gradum.settings.provider.apikey"),
          modifier = Modifier.width(60.dp)
        )
        TextField(
          state = apiKeyState,
          modifier = Modifier.width(INPUT_WIDTH_DP.dp),
          textStyle = JewelTheme.typography.editorTextStyle,
          outputTransformation = if (apiKeyVisible) null else MASK_TRANSFORMATION,
          trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                key = if (apiKeyVisible) AllIconsKeys.Actions.Show else AllIconsKeys.Actions.Unshare,
                contentDescription = message(
                  if (apiKeyVisible) "gradum.settings.provider.apikey.hide"
                  else "gradum.settings.provider.apikey.show"
                ),
                modifier = Modifier
                  .padding(GradumSpacing.sm)
                  .clickable { apiKeyVisible = !apiKeyVisible }
              )
              Icon(
                key = AllIconsKeys.General.Delete,
                contentDescription = message("gradum.settings.provider.apikey.clear"),
                modifier = Modifier
                  .padding(GradumSpacing.sm)
                  .clickable { apiKeyState.edit { replace(0, length, "") } }
              )
            }
          }
        )
      }
    }
    if (extraToggle != null && onExtraToggleChange != null) {
      SettingCheckboxRow(
        enabled = true,
        checked = extraToggle.checked,
        onCheckedChange = onExtraToggleChange,
        label = message(extraToggle.messageKey)
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedButton(
        enabled = !isTesting && isValidBaseUrl(baseUrlState.text.toString()),
        onClick = triggerProbe
      ) {
        Text(text = message("gradum.settings.provider.detect.now"))
      }
      if (isTesting) CircularProgressIndicator(
        modifier = Modifier.width(16.dp)
          .padding(horizontal = GradumSpacing.md)
      )
      Spacer(Modifier.width(GradumSpacing.lg))
      StatusBadge(status = status)
    }
  }
}

private fun runProbe(
  kind: ProviderKind,
  apiKeyState: TextFieldState,
  baseUrlState: TextFieldState,
  onTestingChange: (Boolean) -> Unit,
  onStatusChange: (ProviderStatus) -> Unit,
  scope: kotlinx.coroutines.CoroutineScope
) {
  scope.launch {
    onTestingChange(true)
    onStatusChange(ProviderStatus.Testing)
    val result: ProviderStatus = PROBE.probe(
      kind = kind,
      apiKey = apiKeyState.text.toString(),
      baseUrl = baseUrlState.text.toString()
    )
    onStatusChange(result)
    onTestingChange(false)
  }
}

@Composable
private fun StatusBadge(status: ProviderStatus) {
  val (icon, text) = when (status) {

    is ProviderStatus.Ok -> Pair(
      AllIconsKeys.General.GreenCheckmark,
      message("gradum.settings.provider.status.ok", status.latencyMs)
    )

    is ProviderStatus.Unreachable -> Pair(
      AllIconsKeys.Vcs.Ignore_file,
      message("gradum.settings.provider.status.unreachable")
    )

    is ProviderStatus.AuthError -> Pair(
      AllIconsKeys.Vcs.Ignore_file,
      message("gradum.settings.provider.status.autherror")
    )

    is ProviderStatus.Failed -> Pair(
      AllIconsKeys.Vcs.Ignore_file,
      message("gradum.settings.provider.status.failed")
    )

    else -> return
  }
  val textColor = LocalContentColor.current

  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(key = icon, contentDescription = null)
    Spacer(Modifier.width(GradumSpacing.sm))
    Text(text = text, color = textColor)
  }
}

data class ExtraToggle(val messageKey: String, val checked: Boolean)
