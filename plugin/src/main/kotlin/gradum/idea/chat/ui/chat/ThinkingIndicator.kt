/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingIndicator.kt  2026-06-27 17:25:15 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Collapsible thinking indicator for assistant messages.
 *
 * Shows "思考" label when collapsed, full thinking content when expanded.
 * Uses animated visibility for expand/collapse transitions.
 *
 * @param thinking The accumulated thinking content from the LLM.
 */
@Composable
fun ThinkingIndicator(
    thinking: String,
    modifier: Modifier = Modifier
) {
    if (thinking.isBlank()) return

    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                key = if (isExpanded) AllIconsKeys.General.ChevronDown
                else AllIconsKeys.General.ChevronRight,
                contentDescription = null
            )
            Text(
                text = message("gradum.thinking"),
                color = JewelTheme.globalColors.text.normal,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(10.dp))
        AnimatedVisibility(visible = isExpanded) {
            Text(
                text = thinking,
                color = JewelTheme.globalColors.text.info,
                style = JewelTheme.typography.editorTextStyle,
                modifier = Modifier
                    .fillMaxWidth()
            )
        }
    }
}
