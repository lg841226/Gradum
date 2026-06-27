/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelSelectorBar.kt  2026-06-27 11:42:24 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Bottom bar showing the current model name and a feedback link.
 */
@Composable
fun ModelSelectorBar(
    models: List<ModelInfo> = emptyList(),
    selectedModel: ModelInfo? = null,
    pinnedModels: List<ModelInfo> = emptyList(),
    isAutoSelected: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (ModelInfo?) -> Unit = {},
    onTogglePin: (ModelInfo) -> Unit = {},
    onSelectAuto: () -> Unit = {}
) {
    var showModelMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
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
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center
                        ) { Text(message("gradum.model"), fontWeight = FontWeight.Bold) }
                    }
                    if (models.isEmpty()) {
                        passiveItem {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
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
                            selected = false,
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
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
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
                                selected = false,
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
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model.name.contains("cloud")) {
            Icon(
                key = GradumIcons.Cloud,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Text(text = formatModelName(model.name))

        Spacer(modifier = Modifier.weight(1f))

        if (model.serverName.isNotBlank()) {
            Text(
                text = model.serverName,
                color = JewelTheme.globalColors.text.info
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        IconTooltipButton(
            tooltip = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin"),
            iconKey = if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
            contentDescription = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin"),
            onClick = onTogglePin,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun RefreshButtonItem(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = message("gradum.model.refresh"),
            color = JewelTheme.globalColors.text.info
        )

        Spacer(Modifier.width(6.dp))

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
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            key = GradumIcons.Auto,
            contentDescription = message("gradum.auto.model")
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = message("gradum.model.auto"),
            color = if (enabled) JewelTheme.globalColors.text.normal
            else JewelTheme.globalColors.text.disabled
        )
    }
}
