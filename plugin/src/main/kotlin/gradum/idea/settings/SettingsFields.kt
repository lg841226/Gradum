/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SettingsFields.kt  2026-08-25 00:10:45 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

internal const val POLL_FIELD_WIDTH_DP = 70
internal const val MESSAGE_LOAD_FIELD_WIDTH_DP = 70

internal fun formatFontSize(value: Float): String =
  if (value % 1f == 0f) value.toInt().toString() else value.toString()

@Composable
internal fun FontSizeField(
  minSp: Float,
  maxSp: Float,
  fontSizeSp: Float,
  autoHint: String? = null,
  allowAuto: Boolean = false,
  onFontSizeChange: (Float) -> Unit
) {
  var isFocused: Boolean by remember { mutableStateOf(value = false) }
  var isInputValid: Boolean by remember { mutableStateOf(value = true) }
  var lastValidFontSize: Float by remember(key1 = fontSizeSp) { mutableStateOf(value = fontSizeSp) }

  val state: TextFieldState = remember(key1 = fontSizeSp) {
    val initialText: String =
      if (fontSizeSp > 0f) formatFontSize(fontSizeSp)
      else formatFontSize(14f)

    TextFieldState(initialText = initialText)
  }

  fun validate(): Boolean {
    val raw: String = state.text.toString().trim()
    return allowAuto && raw.isEmpty() || raw.toFloatOrNull()?.let {
      it in minSp..maxSp
    } == true
  }

  fun normalizeAndCommit() {
    val rawInput: String = state.text.toString().trim()
    val parsed: Float? = rawInput.toFloatOrNull()
    val clamped: Float = when {
      allowAuto && rawInput.isEmpty() -> CODE_BLOCK_FONT_SIZE_AUTO_SP
      parsed == null -> lastValidFontSize
      else -> parsed.coerceIn(minSp, maxSp)
    }
    val normalizedText: String = if (allowAuto && clamped <= 0f) "" else formatFontSize(clamped)
    if (state.text.toString() != normalizedText)
      state.edit { replace(start = 0, end = length, normalizedText) }

    lastValidFontSize = clamped
    isInputValid = true
    if (clamped != fontSizeSp) onFontSizeChange(clamped)
  }

  LaunchedEffect(key1 = state.text, key2 = isFocused) {
    if (isFocused) isInputValid = validate()
  }

  TextField(
    state = state,
    modifier = Modifier
      .width(POLL_FIELD_WIDTH_DP.dp)
      .onFocusChanged { focusState: FocusState ->
        if (isFocused && !focusState.isFocused) normalizeAndCommit()
        isFocused = focusState.isFocused
      },
    outline = if (!isInputValid) Outline.Error else Outline.None,
    placeholder = {
      val text: String =
        if (allowAuto && autoHint != null) autoHint
        else "${minSp.toInt()}~${maxSp.toInt()}"

      Text(text = text)
    }
  )
}

@Composable
internal fun MessageLoadCountField(
  enabled: Boolean, days: Int, onDaysChange: (Int) -> Unit
) {
  var isFocused: Boolean by remember { mutableStateOf(value = false) }
  var isInputValid: Boolean by remember { mutableStateOf(value = true) }
  var lastValidDays: Int by remember(key1 = days) { mutableStateOf(value = days) }

  val state: TextFieldState = remember(key1 = days) {
    TextFieldState(initialText = days.toString())
  }

  fun validate(): Boolean {
    val raw: String = state.text.toString().trim()
    return raw.toIntOrNull()?.let { it in MIN_MESSAGE_LOAD_COUNT..MAX_MESSAGE_LOAD_COUNT } == true
  }

  fun normalizeAndCommit() {
    val rawInput: String = state.text.toString().trim()
    val parsed: Int? = rawInput.toIntOrNull()
    val clamped: Int = when {
      parsed == null -> lastValidDays
      else -> parsed.coerceIn(MIN_MESSAGE_LOAD_COUNT, MAX_MESSAGE_LOAD_COUNT)
    }
    val normalizedText: String = clamped.toString()
    if (state.text.toString() != normalizedText)
      state.edit { replace(start = 0, end = length, normalizedText) }

    lastValidDays = clamped
    isInputValid = true
    if (clamped != days) onDaysChange(clamped)
  }

  LaunchedEffect(key1 = state.text, key2 = isFocused) {
    if (isFocused) isInputValid = validate()
  }

  TextField(
    state = state,
    enabled = enabled,
    modifier = Modifier
      .width(MESSAGE_LOAD_FIELD_WIDTH_DP.dp)
      .onFocusChanged { focusState: FocusState ->
        if (isFocused && !focusState.isFocused) normalizeAndCommit()
        isFocused = focusState.isFocused
      },
    outline = if (!isInputValid) Outline.Error else Outline.None,
    placeholder = {
      Text(text = "${MIN_MESSAGE_LOAD_COUNT}~${MAX_MESSAGE_LOAD_COUNT}")
    }
  )
}

@Composable
internal fun AutoCleanupDaysField(
  days: Int, enabled: Boolean, onDaysChange: (Int) -> Unit
) {
  var isFocused: Boolean by remember { mutableStateOf(value = false) }
  var isInputValid: Boolean by remember { mutableStateOf(value = true) }
  var lastValidDays: Int by remember(key1 = days) { mutableStateOf(value = days) }

  val state = remember(key1 = days) {
    TextFieldState(initialText = days.toString())
  }

  fun validate(): Boolean {
    val raw: String = state.text.toString().trim()
    return raw.toIntOrNull()?.let {
      it in MIN_AUTO_CLEANUP_DAYS..MAX_AUTO_CLEANUP_DAYS
    } == true
  }

  fun normalizeAndCommit() {
    val rawInput: String = state.text.toString().trim()
    val parsed: Int? = rawInput.toIntOrNull()
    val clamped: Int =
      when {
        parsed == null -> lastValidDays
        else -> parsed.coerceIn(MIN_AUTO_CLEANUP_DAYS, MAX_AUTO_CLEANUP_DAYS)
      }
    val normalizedText: String = clamped.toString()
    if (state.text.toString() != normalizedText)
      state.edit { replace(start = 0, end = length, normalizedText) }

    lastValidDays = clamped
    isInputValid = true
    if (clamped != days) onDaysChange(clamped)
  }

  LaunchedEffect(key1 = state.text, key2 = isFocused) {
    if (isFocused) isInputValid = validate()
  }

  TextField(
    state = state,
    enabled = enabled,
    modifier = Modifier
      .width(POLL_FIELD_WIDTH_DP.dp)
      .onFocusChanged { focusState: FocusState ->
        if (isFocused && !focusState.isFocused) normalizeAndCommit()
        isFocused = focusState.isFocused
      },
    outline = if (!isInputValid) Outline.Error else Outline.None,
    placeholder = {
      Text(text = "${MIN_AUTO_CLEANUP_DAYS}~${MAX_AUTO_CLEANUP_DAYS}")
    }
  )
}
