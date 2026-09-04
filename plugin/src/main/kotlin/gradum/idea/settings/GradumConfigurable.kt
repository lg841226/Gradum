/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumConfigurable.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderSettings
import gradum.idea.scanCompletedAgo
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.awt.Cursor
import javax.swing.JComponent
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val SETTINGS_PANEL_MAX_HEIGHT: Dp = 720.dp

/**
 * IntelliJ settings entry point for Gradum.
 *
 * Implements [Configurable.NoScroll] so the IDE does not wrap the
 * Compose root in its own scroll pane; the [SettingsPanel] composable
 * owns its own vertical scroll capped at [SETTINGS_PANEL_MAX_HEIGHT]
 * to keep the layout bounded even when a device pixel ratio is huge.
 */
class GradumConfigurable : Configurable, Configurable.NoScroll {

  private val appearance: AppearanceSettings = AppearanceSettings.getInstance()
  private var composePanel: JComponent? = null

  /**
   * Working copy edited by the settings panel. Committed to the
   * persisted service state on [apply] and discarded on [reset], so
   * changes take effect once the user confirms.
   */
  private val appearanceDraft: MutableState<AppearanceSettings.State> =
    mutableStateOf(value = appearance.snapshot)

  override fun getDisplayName(): String = "Gradum"

  override fun createComponent(): JComponent {
    val panel: JComponent = JewelComposePanel {
      SettingsPanel(
        parentComponent = composePanel,
        appearanceDraft = appearanceDraft
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
      it.enableStickySections = appearanceDraft.value.enableStickySections
      it.welcomeLayout = appearanceDraft.value.welcomeLayout
      it.autoCleanupSessions = appearanceDraft.value.autoCleanupSessions
      it.autoCleanupDays = appearanceDraft.value.autoCleanupDays
      it.messageLoadCount = appearanceDraft.value.messageLoadCount
      it.messageLoadEnabled = appearanceDraft.value.messageLoadEnabled
      it.agentEnabled = appearanceDraft.value.agentEnabled
      it.gitEnabled = appearanceDraft.value.gitEnabled
      it.rememberPermission = appearanceDraft.value.rememberPermission
      it.rememberContext = appearanceDraft.value.rememberContext
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
  var checkingUpdate: Boolean by remember { mutableStateOf(value = false) }
  var versionsCopied: Boolean by remember { mutableStateOf(value = false) }
  val autoOpenInEditor: MutableState<Boolean> = remember { mutableStateOf(value = false) }
  var lastCheckedAt: Long by remember { mutableStateOf(value = 0L) }

  val updateScope: CoroutineScope = rememberCoroutineScope()
  val copyScope: CoroutineScope = rememberCoroutineScope()
  val versionSummary: String = remember {
    buildString {
      appendLine(message("gradum.settings.devtools") + " 2026.0730.383-beta")
      appendLine(value = "Gradum Agent (0.9.2.3293)")
      append("Gradum Git Analysis (1.1.0.2388)")
    }
  }
  val onCopyVersions: () -> Unit = {
    copyToClipboard(
      scope = copyScope,
      text = versionSummary,
      onCopied = { versionsCopied = true },
      onReset = { versionsCopied = false }
    )
  }
  val onCheckForUpdates: () -> Unit = {
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
      .verticalScroll(state = rememberScrollState())
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
            key =
              if (versionsCopied) AllIconsKeys.Actions.Checked
              else AllIconsKeys.General.Copy,
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
          onClick = { showThirdPartyNoticesDialog() }
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
          enabled = true,
          label = "Gradum Agent (0.9.2.3293)",
          checked = appearanceDraft.value.agentEnabled,
          onCheckedChange = { checked: Boolean ->
            appearanceDraft.value = appearanceDraft.value.copy(agentEnabled = checked)
          }
        )
        SettingCheckboxRow(
          enabled = true,
          label = "Gradum Git Analysis (1.1.0.2388)",
          checked = appearanceDraft.value.gitEnabled,
          onCheckedChange = { checked: Boolean ->
            appearanceDraft.value = appearanceDraft.value.copy(gitEnabled = checked)
          }
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
          text = message("gradum.settings.update.checked", scanCompletedAgo(millis = lastCheckedAt))
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
  val snapshot: AppearanceSettings.State = draft.value
  val density: ParagraphDensity = snapshot.paragraphDensity

  val densityButtons: List<SegmentedControlButtonData> =
    ParagraphDensity.entries.map { d: ParagraphDensity ->
      SegmentedControlButtonData(
        selected = d == density,
        content = { _: SegmentedControlButtonState ->
          Text(text = message(key = "gradum.settings.appearance.density.${d.storageKey}"))
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
      primary = FontSizeEntry(
        minSp = MIN_PARAGRAPH_FONT_SIZE_SP,
        maxSp = MAX_PARAGRAPH_FONT_SIZE_SP,
        fontSizeSp = snapshot.paragraphFontSizeSp,
        onFontSizeChange = { fontSize: Float ->
          draft.value = draft.value.copy(paragraphFontSizeSp = fontSize)
        }
      ),
      secondary = FontSizeEntry(
        minSp = MIN_CODE_BLOCK_FONT_SIZE_SP,
        maxSp = MAX_CODE_BLOCK_FONT_SIZE_SP,
        fontSizeSp = snapshot.codeBlockFontSizeSp,
        onFontSizeChange = { fontSize: Float ->
          draft.value = draft.value.copy(codeBlockFontSizeSp = fontSize)
        },
        allowAuto = true
      )
    )

    Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
      SettingCheckboxRow(
        enabled = true,
        label = message("gradum.settings.appearance.timestamp"),
        checked = snapshot.showTimestamp,
        onCheckedChange = { checked: Boolean ->
          draft.value = draft.value.copy(showTimestamp = checked)
        }
      )
      SettingCheckboxRow(
        enabled = true,
        label = message("gradum.settings.appearance.collapsethinking"),
        checked = snapshot.collapseThinkingByDefault,
        onCheckedChange = { checked: Boolean ->
          draft.value = draft.value.copy(collapseThinkingByDefault = checked)
        }
      )
      SettingCheckboxRow(
        enabled = true,
        label = message(key = "gradum.settings.appearance.modelname"),
        checked = snapshot.showModelName,
        onCheckedChange = { checked: Boolean ->
          draft.value = draft.value.copy(showModelName = checked)
        }
      )
      SettingCheckboxRow(
        enabled = true,
        label = message("gradum.settings.appearance.autoscroll"),
        checked = snapshot.autoScrollToBottom,
        onCheckedChange = { checked: Boolean ->
          draft.value = draft.value.copy(autoScrollToBottom = checked)
        }
      )
      SettingCheckboxRow(
        enabled = true,
        label = message("gradum.settings.appearance.stickysections"),
        checked = snapshot.enableStickySections,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(enableStickySections = checked)
        }
      )
    }

    Text(
      color = LocalContentColor.current,
      text = message("gradum.settings.appearance.actions")
    )
    Spacer(Modifier.height(GradumSpacing.md))
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
        label = message("gradum.settings.appearance.action.likedislike"),
        iconKeys = listOf(GradumIcons.Like, GradumIcons.Dislike)
      )
    }
    Spacer(Modifier.height(GradumSpacing.md))
    WelcomeLayoutRow(
      layout = snapshot.welcomeLayout,
      onLayoutChange = { layout ->
        draft.value = draft.value.copy(welcomeLayout = layout)
      },
      rememberLabel = message("gradum.settings.appearance.remember.label"),
      rememberPermission = snapshot.rememberPermission,
      onRememberPermissionChange = { checked ->
        draft.value = draft.value.copy(rememberPermission = checked)
      },
      rememberContext = snapshot.rememberContext,
      onRememberContextChange = { checked ->
        draft.value = draft.value.copy(rememberContext = checked)
      }
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      val cleanupEnabled = snapshot.autoCleanupSessions
      Checkbox(
        checked = cleanupEnabled,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(
            autoCleanupSessions = checked,
            autoCleanupDays = snapshot.autoCleanupDays.coerceIn(MIN_AUTO_CLEANUP_DAYS, MAX_AUTO_CLEANUP_DAYS)
          )
        }
      )
      Text(
        text = message("gradum.settings.appearance.autocleanup"),
        modifier = Modifier.align(Alignment.CenterVertically)
      )
      AutoCleanupDaysField(
        enabled = cleanupEnabled,
        days = snapshot.autoCleanupDays,
        onDaysChange = { days ->
          draft.value = draft.value.copy(autoCleanupDays = days)
        }
      )
      Text(
        text = message("gradum.settings.appearance.autocleanup.days"),
        modifier = Modifier.align(Alignment.CenterVertically)
      )
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Checkbox(
        checked = snapshot.messageLoadEnabled,
        onCheckedChange = { checked ->
          draft.value = draft.value.copy(
            messageLoadEnabled = checked,
            messageLoadCount = snapshot
              .messageLoadCount
              .coerceIn(MIN_MESSAGE_LOAD_COUNT, MAX_MESSAGE_LOAD_COUNT)
          )
        }
      )
      Text(
        text = message("gradum.settings.appearance.messageload.before"),
        modifier = Modifier.align(Alignment.CenterVertically)
      )
      MessageLoadCountField(
        days = snapshot.messageLoadCount,
        enabled = snapshot.messageLoadEnabled,
        onDaysChange = { count ->
          draft.value = draft.value.copy(messageLoadCount = count)
        }
      )
      Text(
        text = message("gradum.settings.appearance.messageload.after"),
        modifier = Modifier.align(Alignment.CenterVertically)
      )
    }
    Text(
      text = message("gradum.settings.appearance.messageload.hint"),
      color = JewelTheme.globalColors.text.info,
      style = JewelTheme.typography.labelTextStyle
    )

    Spacer(Modifier.height(GradumSpacing.md))
    GroupHeader(text = message("gradum.settings.dangerzone"))
    OutlinedButton(
      onClick = {
        val defaults = AppearanceSettings.State()
        draft.value = defaults
        AppearanceSettings.getInstance().resetToDefaults()
        ProviderSettings.getInstance().resetToDefaults()
        ProviderConfigFile.clearAll()
      }
    ) {
      Text(
        text = message("gradum.settings.dangerzone.reset"),
        color = JewelTheme.globalColors.text.error
      )
    }
    Text(
      text = message("gradum.settings.dangerzone.reset.hint"),
      color = JewelTheme.globalColors.text.info,
      style = JewelTheme.typography.labelTextStyle
    )
    Spacer(Modifier.height(GradumSpacing.md))
  }
}

/**
 * Welcome layout picker: segmented control + preview on a separate line.
 */
@Composable
private fun WelcomeLayoutRow(
  layout: WelcomeLayout,
  rememberLabel: String? = null,
  rememberContext: Boolean = false,
  rememberPermission: Boolean = false,
  onLayoutChange: (WelcomeLayout) -> Unit,
  onRememberContextChange: ((Boolean) -> Unit)? = null,
  onRememberPermissionChange: ((Boolean) -> Unit)? = null
) {
  val standardLayouts = remember {
    listOf(
      WelcomeLayout.QS2_RC4,
      WelcomeLayout.QS3_RC3,
      WelcomeLayout.QS4_RC2,
      WelcomeLayout.QS0_RC6
    )
  }
  val isCustom = layout !in standardLayouts

  val buttons = standardLayouts.map { l ->
    SegmentedControlButtonData(
      selected = l == layout,
      content = { _ ->
        Text(text = message("gradum.settings.appearance.welcomelayout.${l.storageKey}"))
      }, onSelect = { onLayoutChange(l) }
    )
  } + if (isCustom) {
    listOf(
      SegmentedControlButtonData(
        selected = true,
        content = { _ ->
          Text(text = message("gradum.settings.appearance.welcomelayout.custom"))
        }, onSelect = {}
      )
    )
  } else {
    emptyList()
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
      ) {
        Text(
          color = LocalContentColor.current,
          text = message("gradum.settings.appearance.welcomelayout")
        )
        SegmentedControl(
          enabled = true,
          buttons = buttons,
          modifier = Modifier.weight(1f, fill = false)
        )
      }
      Text(
        text = message("gradum.settings.appearance.welcomelayout.hint"),
        color = JewelTheme.globalColors.text.info,
        style = JewelTheme.typography.labelTextStyle
      )
      if (rememberLabel != null) {
        Spacer(Modifier.height(GradumSpacing.sml))
        Text(
          text = rememberLabel,
          color = LocalContentColor.current
        )
        Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
          SettingCheckboxRow(
            label = message("gradum.settings.appearance.remember.permission"),
            checked = rememberPermission,
            enabled = true,
            onCheckedChange = { checked ->
              onRememberPermissionChange?.invoke(checked)
            }
          )
          SettingCheckboxRow(
            label = message("gradum.settings.appearance.remember.context"),
            checked = rememberContext,
            enabled = true,
            onCheckedChange = { checked ->
              onRememberContextChange?.invoke(checked)
            }
          )
        }
      }
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        text = message("gradum.settings.appearance.welcomelayout.preview"),
        color = JewelTheme.globalColors.text.info,
        style = JewelTheme.typography.labelTextStyle
      )
      Spacer(Modifier.height(GradumSpacing.sm))
      WelcomeLayoutPreview(layout = layout, onLayoutChange = onLayoutChange)
    }
  }
}

@Composable
private fun WelcomeLayoutPreview(
  layout: WelcomeLayout,
  onLayoutChange: (WelcomeLayout) -> Unit
) {
  val qsCount: Int = layout.quickStartCount
  val rcCount: Int = layout.recentCount
  val qsColor: Color = JewelTheme.globalColors.text.info.copy(alpha = 0.25f)
  val rcColor: Color = JewelTheme.globalColors.text.info.copy(alpha = 0.15f)
  val featureIcons = listOf(
    GradumIcons.FeatChat, GradumIcons.FeatQuestion,
    GradumIcons.FeatCode, GradumIcons.FeatText
  )
  var totalHeight by remember { mutableStateOf(1f) }
  var isDragging by remember { mutableStateOf(false) }

  val dividerColor = if (isDragging)
    JewelTheme.globalColors.text.info
  else
    JewelTheme.globalColors.text.disabled

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min)
      .onGloballyPositioned { totalHeight = it.size.height.toFloat().coerceAtLeast(1f) }
      .pointerInput(Unit) {
        detectDragGestures(
          onDragStart = { isDragging = true },
          onDragEnd = { isDragging = false },
          onDrag = { change: PointerInputChange, _: Offset ->
            val maxQs = 5
            val minRc = 2
            val fraction: Float = change.position.y / totalHeight
            val targetQs: Int = (fraction * maxQs).roundToInt().coerceIn(0, maxQs)
            val targetRc: Int = 6 - targetQs
            if (targetRc >= minRc)
              onLayoutChange(welcomeLayoutFromQsCount(targetQs))
          }
        )
      },
    horizontalAlignment = Alignment.End
  ) {
    if (qsCount > 0) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(times = qsCount) { i: Int ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(150.dp)
          ) {
            Icon(
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              key = featureIcons[i % featureIcons.size]
            )
            Box(
              modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(shape = RoundedCornerShape(size = 4.dp))
                .background(qsColor)
            )
          }
        }
      }
    }

    if (qsCount > 0 && rcCount > 0)
      Spacer(Modifier.height(4.dp))

    Row(
      modifier = Modifier
        .width(160.dp)
        .height(24.dp)
        .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(dividerColor)
      )
      Box(
        modifier = Modifier
          .height(1.dp)
          .weight(1f)
          .clip(shape = RoundedCornerShape(size = 2.dp))
          .background(dividerColor)
      )
    }

    if (qsCount > 0 && rcCount > 0)
      Spacer(Modifier.height(4.dp))

    if (rcCount > 0) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(times = rcCount) {
          Row(
            modifier = Modifier.width(150.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              key = GradumIcons.Chat,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Box(
              modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(shape = RoundedCornerShape(size = 4.dp))
                .background(rcColor)
            )
          }
        }
      }
    }
  }
}

private fun welcomeLayoutFromQsCount(qsCount: Int): WelcomeLayout = when (qsCount) {
  0 -> WelcomeLayout.QS0_RC6
  1 -> WelcomeLayout.QS1_RC5
  2 -> WelcomeLayout.QS2_RC4
  3 -> WelcomeLayout.QS3_RC3
  4 -> WelcomeLayout.QS4_RC2
  5 -> WelcomeLayout.QS5_RC1
  else -> WelcomeLayout.QS4_RC2
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
    iconKeys.forEach { iconKey: IconKey ->
      Spacer(Modifier.width(GradumSpacing.sm))
      Icon(key = iconKey, contentDescription = label)
    }
  }
}

/** One font-size slider entry in a [FontSizeRow]. */
private data class FontSizeEntry(
  val minSp: Float,
  val maxSp: Float,
  val fontSizeSp: Float,
  val onFontSizeChange: (Float) -> Unit,
  val allowAuto: Boolean = false
)

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
  labelKey: String, primary: FontSizeEntry, secondary: FontSizeEntry
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs)
  ) {
    Text(
      text = message(key = "${labelKey}.before"),
      modifier = Modifier.align(Alignment.CenterVertically)
    )
    FontSizeField(
      minSp = primary.minSp,
      maxSp = primary.maxSp,
      fontSizeSp = primary.fontSizeSp,
      onFontSizeChange = primary.onFontSizeChange
    )
    Text(
      text = message("gradum.settings.appearance.sp"),
      modifier = Modifier.align(Alignment.CenterVertically)
    )
    Text(
      text = message(key = "${labelKey}.between"),
      modifier = Modifier.align(Alignment.CenterVertically)
    )
    FontSizeField(
      minSp = secondary.minSp,
      maxSp = secondary.maxSp,
      allowAuto = secondary.allowAuto,
      fontSizeSp = secondary.fontSizeSp,
      onFontSizeChange = secondary.onFontSizeChange,
      autoHint = if (secondary.allowAuto) formatFontSize(primary.fontSizeSp) else null
    )
    Text(
      text = "sp",
      modifier = Modifier.align(Alignment.CenterVertically)
    )
  }
}
