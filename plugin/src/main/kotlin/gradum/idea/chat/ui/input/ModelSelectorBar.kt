/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelSelectorBar.kt  2026-08-14 14:21:48 Changed by gwy
 */

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.LocalBadgeStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Cap on the model selector popup height. The local Ollama host on a
 * developer machine can easily surface 30+ entries (every pulled tag
 * counts as a row); without a cap the popup pushes the entire chat
 * area off-screen. 360dp comfortably fits ~6 rows and keeps the
 * selected row visible while scrolling.
 *
 * **Why no `verticalScroll` here.** Jewel `PopupMenu` already wraps
 * its `MenuContent` in an internal `verticalScroll`-backed Column
 * (visible in the disassembled `MenuKt.MenuContent` at
 * `ScrollKt.verticalScroll$default`). Stacking a second one on the
 * outside `Modifier` makes the inner modifier receive
 * `Constraints.Infinity` and throws
 * `IllegalStateException: Vertically scrollable component was
 * measured with an infinity maximum height constraints` the moment
 * the menu opens. The internal scroll container respects the
 * `heightIn(max=...)` cap, which is all we need.
 */
private val MODEL_MENU_MAX_HEIGHT = 360.dp

@Composable
fun ModelSelectorBar(
  modifier: Modifier = Modifier,
  selectedModel: ModelInfo? = null,
  isAutoSelected: Boolean = false,
  models: List<ModelInfo> = emptyList(),
  pinnedModels: List<ModelInfo> = emptyList(),
  thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
  onSelectAuto: () -> Unit = {},
  onTogglePin: (ModelInfo) -> Unit = {},
  onSelectModel: (ModelInfo?) -> Unit = {},
  onSelectThinkingLevel: (ThinkingLevel) -> Unit = {},
) {
  var showModelMenu by remember { mutableStateOf(false) }
  val dismiss: () -> Unit = { showModelMenu = false }

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box {
      SelectorButton(
        onClick = { showModelMenu = true },
        isButtonEnabled = models.isNotEmpty(),
        contentDescription = message("gradum.model.select"),
        text = resolveSelectorText(selectedModel, isAutoSelected)
      )
      if (showModelMenu) {
        PopupMenu(
          onDismissRequest = { dismiss(); true },
          horizontalAlignment = Alignment.Start,
          modifier = Modifier.heightIn(max = MODEL_MENU_MAX_HEIGHT)
        ) {
          buildMenu(
            models = models,
            pinnedModels = pinnedModels,
            selectedModel = selectedModel,
            isAutoSelected = isAutoSelected,
            onTogglePin = { onTogglePin(it); dismiss() },
            onSelectAuto = { onSelectAuto(); dismiss() },
            onSelectModel = { onSelectModel(it); dismiss() }
          )
        }
      }
    }
    Spacer(modifier = Modifier.width(GradumSpacing.xs))
    ThinkingLevelSelector(
      selectedLevel = thinkingLevel,
      onSelect = onSelectThinkingLevel,
    )
    Spacer(modifier = Modifier.weight(1f))
    ExternalLink(
      text = message("gradum.feedback"),
      onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
    )
  }
}


private fun MenuScope.buildMenu(
  models: List<ModelInfo>,
  pinnedModels: List<ModelInfo>,
  isAutoSelected: Boolean,
  selectedModel: ModelInfo?,
  onSelectAuto: () -> Unit,
  onTogglePin: (ModelInfo) -> Unit,
  onSelectModel: (ModelInfo) -> Unit
) {
  passiveItem {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = GradumSpacing.sm),
      horizontalArrangement = Arrangement.Center
    ) {
      Text(
        text = message("gradum.model"),
        fontWeight = FontWeight.SemiBold
      )
    }
  }

  selectableItem(selected = isAutoSelected, onClick = onSelectAuto) {
    AutoModelItem()
  }

  val unpinnedModels = models.filter { model ->
    pinnedModels.none { it.sameAs(model) }
  }

  if (pinnedModels.isNotEmpty()) {
    separator()
    passiveItem {
      Text(
        text = message("gradum.model.pinned"),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            vertical = GradumSpacing.sm,
            horizontal = GradumSpacing.md
          )
      )
    }
    pinnedModels.forEach { pinned ->
      selectableItem(
        selected = false,
        onClick = { onSelectModel(pinned) }
      ) {
        ModelItemRow(
          model = pinned,
          isPinned = true,
          onTogglePin = { onTogglePin(pinned) }
        )
      }
    }
  }

  if (unpinnedModels.isNotEmpty()) {
    if (pinnedModels.isNotEmpty()) separator()
    unpinnedModels.forEach { model ->
      selectableItem(
        selected = selectedModel?.name == model.name
          && selectedModel.serverName == model.serverName,
        onClick = { onSelectModel(model) }
      ) {
        ModelItemRow(
          model = model,
          isPinned = false,
          onTogglePin = { onTogglePin(model) }
        )
      }
    }
  }
}

@Composable
private fun AutoModelItem() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxWidth()
      .padding(GradumSpacing.xs)
  ) {
    Icon(key = GradumIcons.Auto, contentDescription = message("gradum.auto.model"))
    Spacer(modifier = Modifier.width(GradumSpacing.md))
    Text(
      text = message("gradum.model.auto"),
      color = JewelTheme.globalColors.text.normal
    )
  }
}

@Composable
private fun ModelItemRow(model: ModelInfo, isPinned: Boolean, onTogglePin: () -> Unit) {
  val iconKey: IconKey? = resolveProviderIcon(model)
  val formatted: FormattedModelName = parseModelName(model.name)
  val pinTip: String = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin")
  val pinIcon: IconKey = if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = GradumSpacing.xs),
    verticalAlignment = Alignment.Top
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        iconKey?.let {
          Icon(
            key = it,
            contentDescription = null,
            modifier = Modifier.size(GradumSpacing.lrl)
          )
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
        }
        Text(
          text = formatted.displayName,
          color = JewelTheme.globalColors.text.normal
        )
        if (formatted.parameterSize != null) {
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
          SizeBadge(formatted.parameterSize)
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        CapabilityIcons(model)
        Spacer(modifier = Modifier.width(GradumSpacing.sm))
        if (model.name.contains("cloud")) {
          Icon(
            key = GradumIcons.Cloud,
            contentDescription = message("gradum.cloud")
          )
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
        }
        if (model.serverName.isNotBlank())
          Text(
            text = model.serverName,
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.info
          )
      }
    }
    IconTooltipButton(
      tooltip = pinTip,
      iconKey = pinIcon,
      onClick = onTogglePin,
      contentDescription = pinTip
    )
  }
}

@Composable
private fun SizeBadge(size: String) {
  Row {
    Badge(
      content = { Text(size) },
      style = LocalBadgeStyle.current.graySecondary
    )
    Spacer(modifier = Modifier.width(GradumSpacing.sm))
  }
}

@Composable
private fun CapabilityIcons(model: ModelInfo, alpha: Float = 1f) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (model.toolCall)
      Icon(
        key = GradumIcons.ModelTools,
        contentDescription = message("gradum.tools"),
        modifier = Modifier.alpha(alpha)
      )
    if (model.attachment)
      Icon(
        key = GradumIcons.ModelVision,
        contentDescription = message("gradum.vision"),
        modifier = Modifier.alpha(alpha)
      )
  }
}

private fun resolveSelectorText(selectedModel: ModelInfo?, isAutoSelected: Boolean): String = when {
  selectedModel != null && isAutoSelected -> message("gradum.model.auto.with", formatModelName(selectedModel.name))
  selectedModel != null -> formatModelName(selectedModel.name)
  isAutoSelected -> message("gradum.model.auto")
  else -> message("gradum.model.none")
}

private fun resolveProviderIcon(model: ModelInfo): IconKey? {
  val providerName = model.provider.lowercase().trim()
  if (providerName.isNotBlank()) {
    GradumIcons.resolveModelIcon(model.name)?.let { return it }
    return AllIconsKeys.Stub
  }
  return GradumIcons.resolveModelIcon(model.name)
}
