/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SweepLightText.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Renders [text] with a "shimmer" overlay that sweeps a translucent linear
 * gradient horizontally across the glyphs, signaling that work is in
 * progress (e.g. the assistant bubble while a response is streaming).
 *
 * The animation drives a single `offset` from -1f to 2f over
 * [durationMillis] with linear easing and infinite restart. The offset is
 * mapped to the start/end x-coordinates of a 3-stop alpha gradient
 * (low → high → low), so the highlight travels left-to-right and
 * seamlessly re-enters from the left on the next cycle. The underlying
 * `Text` re-lays out its `Brush` on every frame.
 *
 * @param text  The string to render.
 * @param modifier  Compose modifier forwarded to the underlying `Text`.
 * @param durationMillis  Length of one full left-to-right sweep; default 1200ms.
 */
@Composable
fun SweepLightText(
  text: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  durationMillis: Int = 1200
) {
  if (!enabled) {
    Text(
      text = text,
      modifier = modifier,
      style = TextStyle(color = JewelTheme.globalColors.text.info)
    )
    return
  }

  val transition = rememberInfiniteTransition(label = "sweep_light")
  val offset: Float by transition.animateFloat(
    initialValue = -1f,
    targetValue = 2f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = durationMillis, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "sweep_offset"
  )

  Text(
    text = text,
    modifier = modifier,
    style = TextStyle(
      brush = Brush.linearGradient(
        colors = listOf(
          JewelTheme.globalColors.text.info.copy(alpha = 0.4f),
          JewelTheme.globalColors.text.normal.copy(alpha = 0.9f),
          JewelTheme.globalColors.text.info.copy(alpha = 0.4f)
        ),
        start = Offset(offset * 300f, 0f),
        end = Offset(offset * 300f + 300f, 0f)
      )
    )
  )
}
