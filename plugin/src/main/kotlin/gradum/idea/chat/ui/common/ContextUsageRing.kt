package gradum.idea.chat.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme

// Shared token formatter: 1234 -> "1.2K", 1234567 -> "1.2M", below 1000 stays raw.
internal fun formatTokenCount(count: Int): String = when {
  count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
  count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
  else -> count.toString()
}

/**
 * A small circular ring that visualizes how much of the session context
 * window is currently occupied. The track is the full circle, the arc is
 * the used fraction; a single color is used for the arc (no severity
 * tiering yet). When [contextLimit] is not known the ring stays empty.
 */
@Composable
fun ContextUsageRing(
  usedTokens: Int,
  contextLimit: Int,
  ringSize: Dp = 16.dp,
  ringStrokeWidth: Dp = 2.dp,
  modifier: Modifier = Modifier
) {
  val usedFraction: Float =
    if (usedTokens <= 0 || contextLimit <= 0) 0f
    else (usedTokens.toFloat() / contextLimit.toFloat()).coerceIn(0f, 1f)

  val trackColor = JewelTheme.globalColors.borders.normal
  val arcColor = JewelTheme.globalColors.text.info

  Canvas(modifier = modifier.size(size = ringSize)) {
    val strokePx: Float = ringStrokeWidth.toPx()
    val arcTopLeft = Offset(x = strokePx / 2f, y = strokePx / 2f)
    val arcSize = Size(size.width - strokePx, size.height - strokePx)

    drawArc(
      size = arcSize,
      color = trackColor,
      startAngle = 0f,
      sweepAngle = 360f,
      useCenter = false,
      topLeft = arcTopLeft,
      style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx)
    )

    if (usedFraction > 0f) {
      drawArc(
        color = arcColor,
        startAngle = -90f,
        sweepAngle = 360f * usedFraction,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
          width = strokePx,
          cap = StrokeCap.Round
        )
      )
    }
  }
}
