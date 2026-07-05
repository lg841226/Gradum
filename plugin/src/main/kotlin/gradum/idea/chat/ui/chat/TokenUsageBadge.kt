/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TokenUsageBadge.kt  2026-07-05 00:00:00 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*

/**
 * Returns an animated token count string that grows from 0 to [totalTokens].
 *
 * @param totalTokens Total tokens consumed for this assistant turn.
 * @return Formatted string like "1.2K tokens" or "123 tokens", or empty if totalTokens <= 0.
 */
@Composable
fun rememberAnimatedTokenCount(totalTokens: Int): String {
    val animatedProgress = remember { Animatable(0f) }

    LaunchedEffect(totalTokens) {
        if (totalTokens > 0) {
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 800,
                    easing = LinearEasing
                )
            )
        }
    }

    if (totalTokens <= 0) return ""

    val currentCount = (animatedProgress.value * totalTokens).toInt()
    return formatTokenCount(currentCount)
}

/**
 * Formats a token count into a compact human-readable string with unit.
 *
 * - ≥ 1 000     → `"1.2K tokens"`
 * - 1           → `"1 token"`
 * - Otherwise   → `"123 tokens"`
 */
fun formatTokenCount(count: Int): String {
    val unitSuffix = if (count == 1) "token" else "tokens"

    val formattedNumber = if (count >= 1_000)
        String.format("%.1fK", count / 1_000.0)
    else
        count.toString()

    return "$formattedNumber $unitSuffix"
}
