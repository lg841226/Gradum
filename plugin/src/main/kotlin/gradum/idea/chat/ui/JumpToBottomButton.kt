/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-13 Changed by gwy
 */

@file:OptIn(ExperimentalComposeUiApi::class)

package gradum.idea.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.icons.GradumIcons
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * Floating "Jump to latest" / "Jump to top" pill.
 *
 * Two-mode button toggled by the Option (Alt) key:
 *  - **Default**: down arrow + "Jump to latest"; click → [onClick]
 *  - **Alternative**: up arrow + "Jump to top"; click → [onJumpToTop]
 *
 * The mode is a sticky [mutableStateOf] flipped on Option rising
 * edges via the global [LocalWindowInfo] modifier stream — the
 * host platform writes the modifier state on every AWT key event,
 * so the toggle fires the instant the user presses the key (no
 * pointer activity, no focus required).
 *
 * ## Visibility
 *
 * The pill is visible when its current action is meaningful:
 *  - **Default mode**: visible when [isAtBottom] is `false`
 *    (the user has scrolled up).
 *  - **Alternative mode**: visible when [isAtTop] is `false`
 *    (the user has scrolled down away from the top).
 *
 * Scrolling to the top in alternative mode hides the pill
 * (they're already where they want to be); scrolling back
 * down re-shows it.
 *
 * ## Animation
 *
 * - **Enter**: fade-in 0→1 (80ms) + slide-up from one full
 *   height below (the input box's top edge) with a subtle
 *   bounce via `Spring.DampingRatioMediumBouncy` and
 *   `StiffnessMediumLow`. The icon is visible from the start
 *   of the rise; the text label waits [TEXT_REVEAL_DELAY_MS]
 *   and fades in — a "icon first, text after" build-up so the
 *   eye registers the shape before the words.
 * - **Exit**: fade-out + slide-down to the same offset, 80ms
 *   linear. Fast on purpose so the pill is gone before the
 *   user starts typing.
 * - **Mode swap**: the label crossfades (140ms in / 80ms out)
 *   and the pill width smoothly animates to fit the new label
 *   via [androidx.compose.animation.animateContentSize].
 *
 * ## Visual stack
 *
 *  1. Solid `panelBackground` fill — the pill now stands out
 *     from the chat rather than blending into it. (Previously
 *     a 0.3-alpha translucent fill with a self-blur for a
 *     "frosted" look; reverted because Compose's `Modifier.blur`
 *     is a self-blur on the composable's own content, not a
 *     true backdrop blur, so the effect didn't actually blur
 *     the chat behind the pill — it just softened the fill
 *     silhouette, which is hard to read against busy chat
 *     content.)
 *  2. 1 dp `borders.normal` outline at 40% alpha — a clean
 *     pill-shaped edge that separates the pill from the chat
 *     background without competing with the message bubbles.
 *  3. Sharp foreground Row with the icon + label, **not
 *     blurred** — the label and icon stay readable.
 */
@Composable
fun JumpToBottomButton(
  isAtBottom: Boolean,
  isAtTop: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onJumpToTop: () -> Unit = {},
) {
  var isAlternativeMode by remember { mutableStateOf(false) }
  // The pill is visible when the user could benefit from its
  // current action. The rule flips on mode: in alternative
  // mode (jump to top), being at the top is the "done" state
  // so the pill hides there.
  val isVisible: Boolean = if (isAlternativeMode) !isAtTop else !isAtBottom
  val textAlpha: Float = rememberTextRevealAlpha(isVisible)
  AltKeyModeEffect(isVisible) { isAlternativeMode = !isAlternativeMode }
  JumpToBottomPill(
    isVisible = isVisible,
    isAlternativeMode = isAlternativeMode,
    onClick = onClick,
    onJumpToTop = onJumpToTop,
    textAlpha = textAlpha,
    modifier = modifier,
  )
}

/**
 * Subscribes to the global [LocalWindowInfo] modifier stream and
 * fires [onToggle] on every Option-key rising edge. The effect is
 * keyed by [isVisible] so a stale "Option held" doesn't silently
 * toggle the pill while it's hidden.
 */
@Composable
private fun AltKeyModeEffect(
  isVisible: Boolean,
  onToggle: () -> Unit,
) {
  val windowInfo = LocalWindowInfo.current
  LaunchedEffect(isVisible) {
    if (!isVisible) return@LaunchedEffect
    var wasAltDown: Boolean = windowInfo.keyboardModifiers.isAltPressed
    snapshotFlow { windowInfo.keyboardModifiers.isAltPressed }.collect { isAltDown ->
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
  var textVisible: Boolean by remember { mutableStateOf(false) }
  LaunchedEffect(isVisible) {
    if (isVisible) {
      textVisible = false
      delay(TEXT_REVEAL_DELAY_MS)
      textVisible = true
    } else {
      textVisible = false
    }
  }
  return animateFloatAsState(
    targetValue = if (textVisible) 1f else 0f,
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
  isVisible: Boolean,
  isAlternativeMode: Boolean,
  onClick: () -> Unit,
  onJumpToTop: () -> Unit,
  textAlpha: Float,
  modifier: Modifier,
) {
  AnimatedVisibility(
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
      ),
    modifier = modifier,
  ) {
    Box {
      PillBackground(Modifier.matchParentSize())
      PillForeground(
        isAlternativeMode = isAlternativeMode,
        textAlpha = textAlpha,
        onClick = onClick,
        onJumpToTop = onJumpToTop,
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
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(percent = 50))
      .background(JewelTheme.globalColors.panelBackground)
      .border(
        width = PillBorderWidth,
        color = JewelTheme.globalColors.borders.normal.copy(alpha = 0.4f),
        shape = RoundedCornerShape(percent = 50)
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
  isAlternativeMode: Boolean,
  textAlpha: Float,
  onClick: () -> Unit,
  onJumpToTop: () -> Unit,
) {
  val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(percent = 50))
      .animateContentSize(animationSpec = tween(durationMillis = WIDTH_ANIMATION_DURATION_MS))
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = { if (isAlternativeMode) onJumpToTop() else onClick() }
      )
      .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Icon(
      key = if (isAlternativeMode) GradumIcons.ScrollUp else GradumIcons.ScrollDown,
      contentDescription = null,
      modifier = Modifier.size(16.dp)
    )
    // The label crossfades on mode swap (140ms in / 80ms out).
    // The outer `graphicsLayer { alpha = textAlpha }` gates the
    // whole label behind the icon-first reveal — the
    // AnimatedContent is always there, but until [textAlpha]
    // rises the alpha is 0, so the user sees the icon land
    // first, then the text fades in.
    AnimatedContent(
      targetState = isAlternativeMode,
      transitionSpec = {
        fadeIn(animationSpec = tween(durationMillis = MODE_SWAP_IN_DURATION_MS)) togetherWith
          fadeOut(animationSpec = tween(durationMillis = MODE_SWAP_OUT_DURATION_MS))
      },
      label = "JumpToBottomLabel",
      modifier = Modifier.graphicsLayer { alpha = textAlpha }
    ) { alternative ->
      Text(
        text = message(
          if (alternative) "gradum.jump.to.top" else "gradum.jump.to.latest"
        ),
        style = JewelTheme.typography.regular
      )
    }
  }
}

/**
 * Outline width (in dp) around the pill. 1 dp is enough to
 * read as a deliberate edge against the chat background
 * without competing with the message bubbles' own borders.
 */
private val PillBorderWidth = 1.dp

/**
 * Animation timing (in milliseconds unless noted).
 */
private const val ENTER_FADE_DURATION_MS = 80
private const val EXIT_DURATION_MS = 80
private const val TEXT_REVEAL_DELAY_MS = 80L
private const val TEXT_REVEAL_DURATION_MS = 120
private const val MODE_SWAP_IN_DURATION_MS = 140
private const val MODE_SWAP_OUT_DURATION_MS = 80
private const val WIDTH_ANIMATION_DURATION_MS = 180
