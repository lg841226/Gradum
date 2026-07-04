/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelSelectorBar.kt  2026-07-02 23:12:14 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import com.intellij.ide.BrowserUtil
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@Composable
fun ModelSelectorBar(
    models: List<ModelInfo> = emptyList(),
    selectedModel: ModelInfo? = null,
    pinnedModels: List<ModelInfo> = emptyList(),
    isAutoSelected: Boolean = false,
    onSelectModel: (ModelInfo?) -> Unit = {},
    onTogglePin: (ModelInfo) -> Unit = {},
    onSelectAuto: () -> Unit = {},
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
    models: List<ModelInfo>,
    selectedModel: ModelInfo?,
    pinnedModels: List<ModelInfo>,
    isAutoSelected: Boolean,
    onSelectModel: (ModelInfo) -> Unit,
    onTogglePin: (ModelInfo) -> Unit,
    onSelectAuto: () -> Unit
) {
    passiveItem {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = GradumSpacing.xs,
                    vertical = GradumSpacing.sm
                ),
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
                        horizontal = GradumSpacing.sm,
                        vertical = GradumSpacing.sm
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

    val unpinned = models.filter { model ->
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
                        horizontal = GradumSpacing.sm,
                        vertical = GradumSpacing.sm
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

    if (unpinned.isNotEmpty()) {
        if (pinnedModels.isNotEmpty())
            separator()

        unpinned.forEach { model ->
            selectableItem(
                selected = selectedModel?.name == model.name
                    && selectedModel.serverName == model.serverName,
                onClick = { onSelectModel(model) }
            ) {
                ModelItemRow(model = model, isPinned = false, onTogglePin = { onTogglePin(model) })
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
                    text = formatModelName(model.name),
                    color = JewelTheme.globalColors.text.normal
                )
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
    val provider = model.provider.lowercase().trim()
    if (provider.isNotBlank()) PROVIDER_ICON_MAP[provider]?.let { return it }
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
