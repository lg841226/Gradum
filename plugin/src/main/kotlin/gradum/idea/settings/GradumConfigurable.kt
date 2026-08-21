/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumConfigurable.kt  2026-08-20 17:57:52 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.state.ToggleableState
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
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import javax.swing.JComponent
import kotlin.time.Duration.Companion.milliseconds

private val SETTINGS_PANEL_MAX_HEIGHT = 720.dp
private const val POLL_FIELD_WIDTH_DP = 56

/**
 * IntelliJ settings entry point for Gradum.
 *
 * Implements [Configurable.NoScroll] so the IDE does not wrap the
 * Compose root in its own scroll pane; the [SettingsPanel] composable
 * owns its own vertical scroll capped at [SETTINGS_PANEL_MAX_HEIGHT]
 * to keep the layout bounded even when a device pixel ratio is huge.
 */
class GradumConfigurable : Configurable, Configurable.NoScroll {

  private val appearance = AppearanceSettings.getInstance()
  private var composePanel: JComponent? = null

  /**
   * Working copy edited by the settings panel. Committed to the
   * persisted service state on [apply] and discarded on [reset], so
   * changes take effect once the user confirms.
   */
  private val appearanceDraft: MutableState<AppearanceSettings.State> =
    mutableStateOf(appearance.snapshot)

  override fun getDisplayName(): String = "Gradum"

  override fun createComponent(): JComponent {
    val panel = JewelComposePanel {
      SettingsPanel(
        appearanceDraft = appearanceDraft,
        parentComponent = composePanel,
      )
    }
    composePanel = panel
    return panel
  }

  override fun isModified(): Boolean = appearanceDraft.value != appearance.snapshot

  override fun apply() {
    appearance.update {
      it.paragraphDensity = appearanceDraft.value.paragraphDensity
      it.paragraphFontSizeSp = appearanceDraft.value.paragraphFontSizeSp
      it.showTimestamp = appearanceDraft.value.showTimestamp
      it.collapseThinkingByDefault = appearanceDraft.value.collapseThinkingByDefault
      it.showModelName = appearanceDraft.value.showModelName
      it.autoScrollToBottom = appearanceDraft.value.autoScrollToBottom
      it.codeBlockFontSizeSp = appearanceDraft.value.codeBlockFontSizeSp
      it.showCopyAction = appearanceDraft.value.showCopyAction
      it.showRetryAction = appearanceDraft.value.showRetryAction
      it.showLikeDislikeAction = appearanceDraft.value.showLikeDislikeAction
    }
  }

  override fun reset() {
    appearanceDraft.value = appearance.snapshot
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsPanel(
  appearanceDraft: MutableState<AppearanceSettings.State>,
  parentComponent: JComponent?,
) {
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
        modifier = Modifier
          .size(28.dp)
          .clickable { parentComponent?.let { showWhatsNewDialog() } }
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
      text = message("gradum.settings.appearance")
    )
    AppearanceSection(appearanceDraft)
  }
}

@Composable
private fun AppearanceSection(draft: MutableState<AppearanceSettings.State>) {
  val snapshot = draft.value
  val density = snapshot.paragraphDensity

  val densityButtons = ParagraphDensity.entries.map { d ->
    SegmentedControlButtonData(
      selected = d == density,
      content = { _ ->
        Text(text = message("gradum.settings.appearance.density.${d.storageKey}"))
      },
      onSelect = {
        draft.value = draft.value.copy(paragraphDensity = d)
      },
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Text(
        color = LocalContentColor.current,
        text = message("gradum.settings.appearance.density")
      )
      SegmentedControl(
        enabled = true,
        buttons = densityButtons,
        modifier = Modifier.weight(1f, fill = false)
      )
    }

    FontSizeRow(
      labelKey = "gradum.settings.appearance.fontsize",
      fontSizeSp = snapshot.paragraphFontSizeSp,
      onFontSizeChange = { fontSize ->
        draft.value = draft.value.copy(paragraphFontSizeSp = fontSize)
      },
      minSp = MIN_PARAGRAPH_FONT_SIZE_SP,
      maxSp = MAX_PARAGRAPH_FONT_SIZE_SP,
      secondaryFontSizeSp = snapshot.codeBlockFontSizeSp,
      onSecondaryFontSizeChange = { fontSize ->
        draft.value = draft.value.copy(codeBlockFontSizeSp = fontSize)
      },
      secondaryMinSp = MIN_CODE_BLOCK_FONT_SIZE_SP,
      secondaryMaxSp = MAX_CODE_BLOCK_FONT_SIZE_SP,
      secondaryAllowAuto = true
    )

    Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
      SettingCheckboxRow(
        label = message("gradum.settings.appearance.timestamp"),
        checked = snapshot.showTimestamp,
        enabled = true,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(showTimestamp = checked)
        }
      )
      SettingCheckboxRow(
        label = message("gradum.settings.appearance.collapsethinking"),
        checked = snapshot.collapseThinkingByDefault,
        enabled = true,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(collapseThinkingByDefault = checked)
        }
      )
      SettingCheckboxRow(
        label = message("gradum.settings.appearance.modelname"),
        checked = snapshot.showModelName,
        enabled = true,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(showModelName = checked)
        }
      )
      SettingCheckboxRow(
        label = message("gradum.settings.appearance.autoscroll"),
        checked = snapshot.autoScrollToBottom,
        enabled = true,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(autoScrollToBottom = checked)
        }
      )
    }

    Text(
      color = LocalContentColor.current,
      text = message("gradum.settings.appearance.actions")
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically
    ) {
      ActionOptionRow(
        checked = snapshot.showCopyAction,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(showCopyAction = checked)
        },
        label = message("gradum.copy.tooltip"),
        iconKeys = listOf(AllIconsKeys.General.Copy)
      )
      Spacer(Modifier.width(GradumSpacing.lg))
      ActionOptionRow(
        checked = snapshot.showRetryAction,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(showRetryAction = checked)
        },
        label = message("gradum.reset.tooltip"),
        iconKeys = listOf(AllIconsKeys.Actions.Refresh)
      )
      Spacer(Modifier.width(GradumSpacing.lg))
      ActionOptionRow(
        checked = snapshot.showLikeDislikeAction,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(showLikeDislikeAction = checked)
        },
        label = "Like & Dislike",
        iconKeys = listOf(GradumIcons.Like, GradumIcons.Dislike)
      )
    }
  }
}

/**
 * One toolbar-option row: a checkbox, its descriptive label, and the icon
 * the toolbar actually shows, so the user can map the toggle to the button.
 */
@Composable
private fun ActionOptionRow(
  label: String, checked: Boolean,
  iconKeys: List<IconKey>, onCheckedChange: (Boolean) -> Unit
) {
  val state = if (checked) ToggleableState.Indeterminate else ToggleableState.Off
  Row(verticalAlignment = Alignment.CenterVertically) {
    TriStateCheckbox(
      state = state,
      onClick = { onCheckedChange(!checked) }
    )
    Spacer(Modifier.width(GradumSpacing.sm))
    Text(text = label)
    iconKeys.forEach { iconKey ->
      Spacer(Modifier.width(GradumSpacing.sm))
      Icon(key = iconKey, contentDescription = label)
    }
  }
}

/**
 * Number fields for the assistant body and code-block font sizes, laid out
 * as a single flowing sentence with the number fields inlined
 * ("Body font size is [14] sp, code blocks [14] sp").
 * Each field mirrors the auto-detect poll-interval row: valid input is
 * clamped to its range on focus loss, blank / non-numeric input reverts
 * to the last valid value, and invalid text shows a red error outline
 * while focused.
 *
 * The code-block field supports an empty value meaning "auto"
 * ([CODE_BLOCK_FONT_SIZE_AUTO_SP]), i.e. follow the body font size.
 */
@Composable
private fun FontSizeRow(
  minSp: Float,
  maxSp: Float,
  labelKey: String,
  fontSizeSp: Float,
  secondaryMinSp: Float,
  secondaryMaxSp: Float,
  secondaryFontSizeSp: Float,
  onFontSizeChange: (Float) -> Unit,
  onSecondaryFontSizeChange: (Float) -> Unit,
  secondaryAllowAuto: Boolean = false
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs)
  ) {
    Text(
      text = message("${labelKey}.before"),
      modifier = Modifier.align(Alignment.CenterVertically)
    )
    FontSizeField(
      minSp = minSp,
      maxSp = maxSp,
      fontSizeSp = fontSizeSp,
      onFontSizeChange = onFontSizeChange
    )
    Text(
      text = message("gradum.settings.appearance.sp"),
      modifier = Modifier.align(Alignment.CenterVertically)
    )
    Text(
      text = message("${labelKey}.between"),
      modifier = Modifier.align(Alignment.CenterVertically)
    )
    FontSizeField(
      minSp = secondaryMinSp,
      maxSp = secondaryMaxSp,
      allowAuto = secondaryAllowAuto,
      fontSizeSp = secondaryFontSizeSp,
      onFontSizeChange = onSecondaryFontSizeChange,
      autoHint = if (secondaryAllowAuto) formatFontSize(fontSizeSp) else null
    )
    Text(
      text = "sp",
      modifier = Modifier.align(Alignment.CenterVertically)
    )
  }
}

@Composable
private fun FontSizeField(
  minSp: Float,
  maxSp: Float,
  fontSizeSp: Float,
  autoHint: String? = null,
  allowAuto: Boolean = false,
  onFontSizeChange: (Float) -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  var isInputValid by remember { mutableStateOf(true) }
  var lastValidFontSize by remember(fontSizeSp) { mutableStateOf(fontSizeSp) }

  val state = remember(fontSizeSp) {
    val initialText =
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
      state.edit { replace(0, length, normalizedText) }

    lastValidFontSize = clamped
    isInputValid = true
    if (clamped != fontSizeSp) onFontSizeChange(clamped)
  }

  LaunchedEffect(state.text, isFocused) {
    if (isFocused) isInputValid = validate()
  }

  TextField(
    state = state,
    modifier = Modifier
      .width(POLL_FIELD_WIDTH_DP.dp)
      .onFocusChanged { focusState ->
        if (isFocused && !focusState.isFocused) normalizeAndCommit()
        isFocused = focusState.isFocused
      },
    outline = if (!isInputValid) Outline.Error else Outline.None,
    placeholder = {
      val text: String = if (allowAuto && autoHint != null)
        autoHint else "${minSp.toInt()}~${maxSp.toInt()}"

      Text(text = text)
    }
  )
}

private fun formatFontSize(value: Float): String =
  if (value % 1f == 0f) value.toInt().toString() else value.toString()
