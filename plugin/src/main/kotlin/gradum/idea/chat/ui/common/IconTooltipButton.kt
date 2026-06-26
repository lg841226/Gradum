/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * IconTooltipButton.kt  2026-06-26 17:14:54 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Reusable icon button with tooltip, reducing repeated Tooltip+IconButton+Icon patterns.
 */
@Composable
fun IconTooltipButton(
    tooltip: String,
    iconKey: IconKey,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Tooltip(tooltip = { Text(text = tooltip) }) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(key = iconKey, contentDescription = contentDescription)
        }
    }
}
