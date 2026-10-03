package gradum.idea.chat.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme

private val MinimapBarGap: Dp = 26.dp
private val MinimapBarHeight: Dp = 2.dp
private val MinimapBarCornerRadius: Dp = 3.dp
private val MinimapBarVisualInset: Dp = 2.dp
private const val MinimapBarInactiveWidthFraction: Float = 0.8f
private const val MinimapVisibleTicks: Int = 12
private const val MinimapBarTransitionMs: Int = 150

@Composable
fun ChatMinimap(
  scrollState: ScrollState,
  modifier: Modifier = Modifier,
  jumpPadding: Dp = 0.dp,
  onJumpToOffset: (Float) -> Unit,
  userMessageOffsets: List<Float>
) {
  val density = LocalDensity.current
  val hoverColor: Color = JewelTheme.globalColors.text.info
  val barColor: Color = JewelTheme.globalColors.borders.disabled
  val accentColor: Color = JewelTheme.globalColors.outlines.focused
  var railHeightPx: Float by remember { mutableFloatStateOf(value = 0f) }

  Box(
    modifier = modifier.onGloballyPositioned { coordinates ->
      railHeightPx = coordinates.size.height.toFloat()
    },
    contentAlignment = Alignment.Center
  ) {
    if (scrollState.maxValue <= 0 || userMessageOffsets.isEmpty() || railHeightPx <= 0f) {
      return@Box
    }

    val tickCount: Int = userMessageOffsets.size
    val activeThresholdPx: Float =
      scrollState.value.toFloat() + with(receiver = density) { jumpPadding.toPx() }
    val isAtBottom: Boolean = scrollState.value >= scrollState.maxValue
    val activeIndex: Int =
      if (isAtBottom) tickCount - 1
      else userMessageOffsets.indexOfLast { offsetPx: Float -> offsetPx <= activeThresholdPx }
        .coerceAtLeast(minimumValue = 0)
    val visibleCount: Int = minOf(tickCount, MinimapVisibleTicks)
    val windowStart: Int =
      if (tickCount <= MinimapVisibleTicks) 0
      else (activeIndex - MinimapVisibleTicks / 2 + 1)
        .coerceIn(0, tickCount - visibleCount)
    val gapDp =
      if (visibleCount <= 1) 0.dp
      else {
        val barHeightPx: Float = with(receiver = density) { MinimapBarHeight.toPx() }
        val sparePx: Float = railHeightPx - visibleCount * barHeightPx
        val gapPx: Float = (sparePx / visibleCount)
          .coerceIn(
            minimumValue = 0f,
            maximumValue = with(receiver = density) { MinimapBarGap.toPx() }
          )
        with(receiver = density) { gapPx.toDp() }
      }

    val visibleTicks: List<Float> =
      userMessageOffsets.subList(fromIndex = windowStart, toIndex = windowStart + visibleCount)

    Column {
      visibleTicks.forEachIndexed { offsetInWindow: Int, offsetPx: Float ->
        val index: Int = windowStart + offsetInWindow
        val isActive: Boolean = index == activeIndex
        val interactionSource: MutableInteractionSource =
          remember(key1 = index) { MutableInteractionSource() }
        val isHovered: Boolean by interactionSource.collectIsHoveredAsState()
        val barFraction: Float by animateFloatAsState(
          targetValue =
            if (isActive || isHovered) 1f
            else MinimapBarInactiveWidthFraction,
          animationSpec = tween(durationMillis = MinimapBarTransitionMs),
          label = "minimapBarFraction"
        )
        val targetBarColor: Color =
          when {
            isActive -> accentColor
            isHovered -> hoverColor
            else -> barColor
          }
        val animatedBarColor: Color by animateColorAsState(
          targetValue = targetBarColor,
          animationSpec = tween(durationMillis = MinimapBarTransitionMs),
          label = "minimapBarColor"
        )
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(height = MinimapBarHeight + gapDp)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onJumpToOffset(offsetPx) },
          contentAlignment = Alignment.Center
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(fraction = barFraction)
              .padding(horizontal = MinimapBarVisualInset)
              .height(height = MinimapBarHeight)
              .clip(shape = RoundedCornerShape(size = MinimapBarCornerRadius))
              .background(color = animatedBarColor)
          )
        }
      }
    }
  }
}
