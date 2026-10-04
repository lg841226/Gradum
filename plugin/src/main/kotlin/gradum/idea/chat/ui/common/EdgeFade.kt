package gradum.idea.chat.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import gradum.idea.utils.GradumSpacing

/**
 * Softens the hard top/bottom cut of a height-capped, scrollable list.
 *
 * The content is drawn into an offscreen layer, then a vertical gradient is
 * punched out of it with [BlendMode.DstOut]: fully opaque at the very edge
 * (content ends at zero alpha) and ramping to transparent over [fadeLength],
 * so the cut reads as a gentle fade instead of a razor-sharp rectangle edge.
 * Only the layer's own pixels are affected, never the chat background behind
 * it, and no shadow/drop-shadow is involved.
 */
fun Modifier.verticalEdgeFade(fadeLength: Dp = GradumSpacing.xl): Modifier =
  this.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
      drawContent()
      if (size.height <= 0f) return@drawWithContent
      val fadeProgress: Float = (fadeLength.toPx() / size.height).coerceIn(0f, 0.5f)
      if (fadeProgress <= 0f) return@drawWithContent
      drawRect(
        brush = Brush.verticalGradient(
          0f to Color.Black,
          fadeProgress to Color.Transparent,
          1f - fadeProgress to Color.Transparent,
          1f to Color.Black
        ),
        blendMode = BlendMode.DstOut
      )
    }
