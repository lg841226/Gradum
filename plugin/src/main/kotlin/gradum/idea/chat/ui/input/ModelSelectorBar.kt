/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelSelectorBar.kt  2026-07-28 22:53:43 Changed by gwy
 */

package gradum.idea.chat.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@Composable
fun ModelSelectorBar(
  selectedModel: ModelInfo? = null,
  isAutoSelected: Boolean = false,
  models: List<ModelInfo> = emptyList(),
  pinnedModels: List<ModelInfo> = emptyList(),
  onSelectAuto: () -> Unit = {},
  onTogglePin: (ModelInfo) -> Unit = {},
  onSelectModel: (ModelInfo?) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var showModelMenu by remember { mutableStateOf(false) }
  val dismiss: () -> Unit = { showModelMenu = false }

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box {
      SelectorButton(
        text = resolveSelectorText(selectedModel, isAutoSelected),
        onClick = { showModelMenu = true },
        contentDescription = message("gradum.model.select")
      )
      if (showModelMenu) {
        PopupMenu(
          onDismissRequest = { dismiss(); true },
          horizontalAlignment = Alignment.Start
        ) {
          buildMenu(
            models = models,
            selectedModel = selectedModel,
            pinnedModels = pinnedModels,
            isAutoSelected = isAutoSelected,
            onSelectModel = { onSelectModel(it); dismiss() },
            onTogglePin = { onTogglePin(it); dismiss() },
            onSelectAuto = { onSelectAuto(); dismiss() }
          )
        }
      }
    }
    Spacer(modifier = Modifier.weight(1f))
    ExternalLink(
      text = message("gradum.feedback"),
      onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
    )
  }
}


private fun MenuScope.buildMenu(
  isAutoSelected: Boolean,
  selectedModel: ModelInfo?,
  models: List<ModelInfo>,
  pinnedModels: List<ModelInfo>,
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
        fontWeight = FontWeight.Bold
      )
    }
  }

  if (models.isEmpty()) {
    passiveItem {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            vertical = GradumSpacing.sm,
            horizontal = GradumSpacing.sml
          ),
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          text = message("gradum.model.none"),
          color = JewelTheme.globalColors.text.info
        )
      }
    }
    return
  }

  selectableItem(selected = isAutoSelected, onClick = onSelectAuto) {
    AutoModelItem()
  }

  val unpinnedModels = models.filter { model ->
    pinnedModels.none {
      it.name == model.name && it.serverName == model.serverName
    }
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
      .padding(horizontal = GradumSpacing.xs)
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
  val pinIcon: IconKey = if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin
  val pinTip: String = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin")
  val formatted: FormattedModelName = parseModelName(model.name)

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
      contentDescription = pinTip,
      onClick = onTogglePin
    )
  }
}

/**
 * "Ghost" pill that surfaces the parameter size next to the
 * display name. Background is the same blue as `text.info` at
 * ~12% alpha, text is the full-strength blue. The intent is a
 * subtle tint that doesn't compete with the family label but
 * is still scannable at a glance — heavier fills tend to make
 * the row look like it has two equal-weight titles.
 *
 * Renders nothing for cloud / size-less models because the
 * caller already gates on `parameterSize != null`.
 */
@Composable
private fun SizeBadge(size: String) {
  val infoColor: Color = JewelTheme.globalColors.text.info
  Box(
    modifier = Modifier
      .background(
        color = infoColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(GradumSpacing.sm)
      )
      .padding(horizontal = GradumSpacing.sml, vertical = 2.dp)
  ) {
    Text(
      text = size,
      color = infoColor,
      style = JewelTheme.typography.small,
      fontWeight = FontWeight.Medium
    )
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
  if (providerName.isNotBlank()) PROVIDER_ICON_MAP[providerName]?.let { return it }
  return GradumIcons.resolveModelIcon(model.name)
}

private val PROVIDER_ICON_MAP = mapOf(
  "qwen" to GradumIcons.ProviderAlibaba,
  "anthropic" to GradumIcons.ProviderAnthropic,
  "deepseek" to GradumIcons.ProviderDeepseek,
  "google" to GradumIcons.ProviderGoogle,
  "meta" to GradumIcons.ProviderMeta,
  "minimax" to GradumIcons.ProviderMinimax,
  "mistral" to GradumIcons.ProviderMistral,
  "openai" to GradumIcons.ProviderOpenai,
  "xai" to GradumIcons.ProviderXai,
  "xiaomi" to GradumIcons.ProviderXiaomi,
  "glm" to GradumIcons.ProviderZhipuai
)
