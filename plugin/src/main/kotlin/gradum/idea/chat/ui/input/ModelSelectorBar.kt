/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelSelectorBar.kt  2026-06-30 17:58:28 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Bottom bar showing the current model name and a feedback link.
 */
// TODO: Consider bundling the 8 parameters (models, selectedModel, pinnedModels, isAutoSelected,
//       onRefresh, onSelectModel, onTogglePin, onSelectAuto) into a dedicated data class.
@Composable
fun ModelSelectorBar(
    models: List<ModelInfo> = emptyList(),
    selectedModel: ModelInfo? = null,
    pinnedModels: List<ModelInfo> = emptyList(),
    isAutoSelected: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (ModelInfo?) -> Unit = {},
    onTogglePin: (ModelInfo) -> Unit = {},
    onSelectAuto: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showModelMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = GradumSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            SelectorButton(
                text = when {
                    selectedModel != null -> formatModelName(selectedModel.name)
                    isAutoSelected -> message("gradum.model.auto")
                    else -> message("gradum.model.none")
                },
                contentDescription = message("gradum.model.select"),
                iconKey = selectedModel?.let { resolveProviderIcon(it) },
                onClick = { showModelMenu = true }
            )
            if (showModelMenu) {
                PopupMenu(
                    onDismissRequest = { showModelMenu = false; true },
                    horizontalAlignment = Alignment.Start
                ) {
                    passiveItem {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = GradumSpacing.xs),
                            horizontalArrangement = Arrangement.Center
                        ) { Text(text = message("gradum.model"), fontWeight = FontWeight.Bold) }
                    }
                    if (models.isEmpty()) {
                        passiveItem {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = message("gradum.model.none"),
                                    color = JewelTheme.globalColors.text.info
                                )
                            }
                        }
                        separator()
                        passiveItem { RefreshButtonItem(onRefresh) }
                    } else {
                        selectableItem(
                            selected = isAutoSelected,
                            onClick = {
                                onSelectAuto()
                                showModelMenu = false
                            }
                        ) { ModelAutoItemContent(enabled = true) }

                        if (pinnedModels.isNotEmpty()) {
                            separator()
                            passiveItem {
                                Text(
                                    text = message("gradum.model.pinned"),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs)
                                )
                            }
                            pinnedModels.forEach { pinned ->
                                selectableItem(
                                    selected = false,
                                    onClick = {
                                        onSelectModel(pinned)
                                        showModelMenu = false
                                    }
                                ) {
                                    ModelItemContent(
                                        model = pinned,
                                        isPinned = true,
                                        onTogglePin = {
                                            onTogglePin(pinned)
                                            showModelMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        separator()

                        models.filter { model ->
                            pinnedModels.none {
                                it.name == model.name && it.serverName == model.serverName
                            }
                        }.forEach { model ->
                            selectableItem(
                                selected = selectedModel?.name == model.name
                                    && selectedModel.serverName == model.serverName,
                                onClick = {
                                    onSelectModel(model)
                                    showModelMenu = false
                                }
                            ) {
                                ModelItemContent(
                                    model = model,
                                    isPinned = false,
                                    onTogglePin = {
                                        onTogglePin(model)
                                        showModelMenu = false
                                    }
                                )
                            }
                        }
                        separator()
                        passiveItem { RefreshButtonItem(onRefresh) }
                    }
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

@Composable
private fun ModelItemContent(
    model: ModelInfo,
    isPinned: Boolean,
    onTogglePin: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isCloud = model.name.contains("cloud")

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                resolveProviderIcon(model)?.let { iconKey ->
                    Icon(
                        key = iconKey,
                        contentDescription = null,
                        modifier = Modifier.size(GradumSpacing.lrl)
                    )
                    Spacer(modifier = Modifier.width(GradumSpacing.sm))
                }
                Text(text = formatModelName(model.name))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelCapabilityIcons(model)
                Spacer(modifier = Modifier.width(GradumSpacing.sm))
                if (isCloud) {
                    Icon(
                        key = GradumIcons.Cloud,
                        contentDescription = "Cloud"
                    )
                    Spacer(modifier = Modifier.width(GradumSpacing.sm))
                }
                if (model.serverName.isNotBlank()) {
                    Text(
                        text = model.serverName,
                        style = JewelTheme.typography.small,
                        color = JewelTheme.globalColors.text.info
                    )
                }
            }
        }

        IconTooltipButton(
            tooltip = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin"),
            iconKey = if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
            contentDescription = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin"),
            onClick = onTogglePin
        )
    }
}

@Composable
private fun RefreshButtonItem(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GradumSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = message("gradum.model.refresh"),
            color = JewelTheme.globalColors.text.info
        )

        Spacer(modifier = Modifier.width(GradumSpacing.md))

        IconButton(onClick = onRefresh) {
            Icon(
                key = AllIconsKeys.General.Refresh,
                contentDescription = message("gradum.refresh")
            )
        }
    }
}

@Composable
private fun ModelAutoItemContent(enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs)
    ) {
        Icon(
            key = GradumIcons.Auto,
            contentDescription = message("gradum.auto.model")
        )
        Spacer(modifier = Modifier.width(GradumSpacing.md))
        Text(
            text = message("gradum.model.auto"),
            color = if (enabled) JewelTheme.globalColors.text.normal
            else JewelTheme.globalColors.text.disabled
        )
    }
}

private fun resolveProviderIcon(model: ModelInfo): IconKey? {
    val provider = model.provider.lowercase().trim()
    if (provider.isNotBlank()) {
        PROVIDER_ICON_MAP[provider]?.let { return it }
    }
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

@Composable
private fun ModelCapabilityIcons(model: ModelInfo) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model.toolCall)
            Icon(
                key = GradumIcons.ModelTools,
                contentDescription = "Tools"
            )
        if (model.attachment)
            Icon(
                key = GradumIcons.ModelVision,
                contentDescription = "Vision"
            )
    }
}
