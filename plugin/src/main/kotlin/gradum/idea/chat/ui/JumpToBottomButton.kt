/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-08-26 00:15:18 Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LocalMenuStyle
import org.jetbrains.jewel.ui.typography
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pill shape constant.
 */
private val PillShape = RoundedCornerShape(percent = 50)

/**
 * Animation timing (in milliseconds unless noted).
 */
private const val EXIT_DURATION_MS = 80
private const val ENTER_FADE_DURATION_MS = 80
private const val TEXT_REVEAL_DELAY_MS = 80L
private const val TEXT_REVEAL_DURATION_MS = 120
private const val MODE_SWAP_IN_DURATION_MS = 140
private const val MODE_SWAP_OUT_DURATION_MS = 80
private const val WIDTH_ANIMATION_DURATION_MS = 180

/**
 * Floating "Jump to latest" / "Jump to top" pill.
 *
 * Two-mode button toggled by the Option (Alt) key:
 *  - **Default**: down arrow + "Jump to latest"; click → [onClick]
 *  - **Alternative**: up arrow + "Jump to top"; click → [onJumpToTop]
 *
 * Mode is a sticky [mutableStateOf] flipped via [LocalWindowInfo] modifier stream on rising edge.
 *
 * ## Visibility
 *  - Default mode: visible when [isAtBottom] is `false` (user scrolled up).
 *  - Alternative mode: visible when [isAtTop] is `false` (user scrolled down).
 *
 * ## Animation
 *  - Enter: fade-in 80ms + slide-up with bounce (MediumBouncy / StiffnessMediumLow).
 *    Icon first, text fades in after [TEXT_REVEAL_DELAY_MS] (build-up effect).
 *  - Exit: fade-out + slide-down, 80ms linear.
 *  - Mode swap: label crossfades (140ms in / 80ms out), width animates via [animateContentSize].
 *
 * ## Visual stack
 *  1. Solid `panelBackground` fill (former frosted-blur was a self-blur, not a true backdrop blur —
 *     reverted because it only softened the fill silhouette without actually blurring chat content).
 *  2. 1 dp `borders.normal` outline at 40% alpha.
 *  3. Sharp foreground Row (icon + label, not blurred).
 */
@Composable
fun JumpToBottomButton(
  isAtTop: Boolean,
  isAtBottom: Boolean,
  onClick: () -> Unit,
  onJumpToTop: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var isAlternativeMode: Boolean by remember { mutableStateOf(value = false) }
  val isVisible: Boolean =
    if (isAlternativeMode) !isAtTop
    else !isAtBottom
  val textAlpha: Float = rememberTextRevealAlpha(isVisible)
  AltKeyModeEffect(isVisible) { isAlternativeMode = !isAlternativeMode }
  JumpToBottomPill(
    onClick = onClick,
    modifier = modifier,
    isVisible = isVisible,
    textAlpha = textAlpha,
    onJumpToTop = onJumpToTop,
    isAlternativeMode = isAlternativeMode
  )
}

/**
 * Subscribes to the global [LocalWindowInfo] modifier stream and
 * fires [onToggle] on every Option-key rising edge. The effect is
 * keyed by [isVisible] so a stale "Option held" doesn't silently
 * toggle the pill while it's hidden.
 */
@Composable
private fun AltKeyModeEffect(isVisible: Boolean, onToggle: () -> Unit) {
  val windowInfo: WindowInfo = LocalWindowInfo.current
  LaunchedEffect(key1 = isVisible) {
    if (!isVisible) return@LaunchedEffect
    var wasAltDown: Boolean = windowInfo.keyboardModifiers.isAltPressed
    snapshotFlow {
      windowInfo.keyboardModifiers.isAltPressed
    }.collect { isAltDown: Boolean ->
      if (isAltDown && !wasAltDown) onToggle()
      wasAltDown = isAltDown
    }
  }
}

/**
 * Icon-first text reveal: holds the label at alpha 0 for
 * [TEXT_REVEAL_DELAY_MS] when the pill becomes visible, then
 * animates to 1 over [TEXT_REVEAL_DURATION_MS] — the icon lands
 * and rises alone, then the text joins in.
 */
@Composable
private fun rememberTextRevealAlpha(isVisible: Boolean): Float {
  var textVisible: Boolean by remember { mutableStateOf(value = false) }

  LaunchedEffect(key1 = isVisible) {
    if (isVisible) {
      textVisible = false
      delay(duration = TEXT_REVEAL_DELAY_MS.milliseconds)
      textVisible = true
    } else {
      textVisible = false
    }
  }
  return animateFloatAsState(
    targetValue =
      if (textVisible) 1f
      else 0f,
    animationSpec = tween(durationMillis = TEXT_REVEAL_DURATION_MS)
  ).value
}

/**
 * The actual pill: translucent background, sharp foreground
 * icon + crossfading label. Width animates between modes via
 * [Modifier.animateContentSize].
 */
@Composable
private fun JumpToBottomPill(
  textAlpha: Float,
  isVisible: Boolean,
  modifier: Modifier,
  onClick: () -> Unit,
  onJumpToTop: () -> Unit,
  isAlternativeMode: Boolean
) {
  AnimatedVisibility(
    modifier = modifier,
    visible = isVisible,
    enter = fadeIn(animationSpec = tween(durationMillis = ENTER_FADE_DURATION_MS)) +
      slideInVertically(
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMediumLow
        ),
        initialOffsetY = { fullHeight: Int -> fullHeight }
      ),
    exit = fadeOut(animationSpec = tween(durationMillis = EXIT_DURATION_MS)) +
      slideOutVertically(
        animationSpec = tween(durationMillis = EXIT_DURATION_MS),
        targetOffsetY = { fullHeight: Int -> fullHeight }
      )
  ) {
    Box {
      PillBackground(Modifier.matchParentSize())
      PillForeground(
        onClick = onClick,
        textAlpha = textAlpha,
        onJumpToTop = onJumpToTop,
        isAlternativeMode = isAlternativeMode
      )
    }
  }
}

/**
 * Solid `panelBackground` fill with a 1 dp `borders.normal`
 * outline. The solid fill makes the pill stand out from busy
 * chat content; the outline provides a clean pill-shaped edge
 * without competing with the message bubbles. [modifier] should
 * carry the size (typically `Modifier.matchParentSize()` from
 * the outer [Box]).
 */
@Composable
private fun PillBackground(modifier: Modifier = Modifier) {
  val menuColors = LocalMenuStyle.current.colors
  val menuMetrics = LocalMenuStyle.current.metrics
  Box(
    modifier = modifier
      .clip(PillShape)
      .background(color = menuColors.background)
      .border(
        shape = PillShape,
        color = menuColors.border,
        width = menuMetrics.borderWidth
      )
  )
}

/**
 * Icon + label, **not blurred**, so the icon and text stay
 * sharp. The width animates between "Jump to latest" and
 * "Jump to top" via [Modifier.animateContentSize].
 */
@Composable
private fun PillForeground(
  textAlpha: Float,
  onClick: () -> Unit,
  onJumpToTop: () -> Unit,
  isAlternativeMode: Boolean
) {
  val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
  Row(
    modifier = Modifier
      .clip(PillShape)
      .animateContentSize(animationSpec = tween(durationMillis = WIDTH_ANIMATION_DURATION_MS))
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = {
          if (isAlternativeMode) onJumpToTop()
          else onClick()
        }
      )
      .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
  ) {
    Icon(
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      key =
        if (isAlternativeMode) GradumIcons.ScrollUp
        else GradumIcons.ScrollDown
    )
    AnimatedContent(
      targetState = isAlternativeMode,
      transitionSpec = {
        fadeIn(animationSpec = tween(durationMillis = MODE_SWAP_IN_DURATION_MS)) togetherWith
          fadeOut(animationSpec = tween(durationMillis = MODE_SWAP_OUT_DURATION_MS))
      },
      label = "JumpToBottomLabel",
      modifier = Modifier.graphicsLayer { alpha = textAlpha }
    ) { alternative: Boolean ->
      Text(
        style = JewelTheme.typography.regular,
        text = message(
          key =
            if (alternative) "gradum.jump.to.top"
            else "gradum.jump.to.bottom"
        )
      )
    }
  }
}
