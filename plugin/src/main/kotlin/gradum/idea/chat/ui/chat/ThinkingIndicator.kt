/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingIndicator.kt  2026-06-29 10:03:35 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
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
 * @param enterTransition Custom enter transition for the outer wrapper.
 * @param isTaskComplete When true, collapses the thinking content.
 */
@Composable
fun ThinkingIndicator(
    thinking: String,
    modifier: Modifier = Modifier,
    enterTransition: EnterTransition = fadeIn(tween(800)),
    isTaskComplete: Boolean = false
) {
    if (thinking.isBlank()) return

    var isExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(isTaskComplete) {
        if (isTaskComplete) {
            isExpanded = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
        ) {
            Icon(
                key = AllIconsKeys.Nodes.Related,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(GradumSpacing.xs))
            Text(
                text = message("gradum.thinking"),
                color = JewelTheme.globalColors.text.info,
                fontWeight = FontWeight.Medium
            )
            Icon(
                key = if (isExpanded) AllIconsKeys.General.ChevronDown
                else AllIconsKeys.General.ChevronRight,
                contentDescription = null
            )
        }

        if (isExpanded)
            Spacer(modifier = Modifier.height(10.dp))

        AnimatedVisibility(visible = isExpanded) {

            Text(
                text = thinking,
                color = JewelTheme.globalColors.text.info,
                style = JewelTheme.typography.editorTextStyle.copy(lineHeight = JewelTheme.typography.editorTextStyle.fontSize * 1.5f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
