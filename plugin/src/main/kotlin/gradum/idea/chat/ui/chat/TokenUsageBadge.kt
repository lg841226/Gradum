/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TokenUsageBadge.kt  2026-07-03 19:37:58 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

private const val ANIMATION_MS: Int = 800

/**
 * Displays the total token count with a synchronized animation:
 * - Number counts up from 0 to [totalTokens]
 * - Text fades in from transparent to opaque
 *
 * Both animations run simultaneously for a smooth reveal effect.
 *
 * @param totalTokens Total tokens consumed for this assistant turn.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun TokenUsageBadge(totalTokens: Int, modifier: Modifier = Modifier) {
    val animatedProgress = remember { Animatable(0f) }

    LaunchedEffect(totalTokens) {
        if (totalTokens > 0) {
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ANIMATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    if (totalTokens > 0) {
        SelectionContainer {
            val currentCount = (animatedProgress.value * totalTokens).toInt()
            val displayText = formatTokenCount(currentCount)
            Text(
                maxLines = 1,
                text = displayText,
                modifier = modifier.alpha(animatedProgress.value),
                style = JewelTheme.typography.small
                    .copy(color = JewelTheme.globalColors.text.info)
            )
        }
    }
}

/**
 * Formats a token count into a compact human-readable string with unit.
 *
 * - ≥ 1 000     → `"1.2k tokens"`
 * - 1           → `"1 token"`
 * - Otherwise   → `"123 tokens"`
 */
private fun formatTokenCount(count: Int): String {
    val unitSuffix = if (count == 1) "token" else "tokens"

    val formattedNumber = if (count >= 1_000)
        String.format("%.1fk", count / 1_000.0)
    else
        count.toString()

    return "$formattedNumber $unitSuffix"
}
