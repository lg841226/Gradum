/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-13  Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Animation timing. The enter is intentionally a touch slower than
 * the exit — the user gets to see the slide + fade as the button
 * rises from the input edge, but on dismissal we want it gone
 * immediately so it doesn't linger over the input as the user
 * starts typing.
 */
private const val ENTER_DURATION_MS = 140
private const val EXIT_DURATION_MS = 80

/**
 * Background blur radius (in dp). At 20dp the content behind the
 * button is recognisable in shape but the text is unreadable — the
 * classic "frosted glass" look. See [blur] for platform caveats.
 */
private val BackgroundBlurRadiusDp = 20.dp

/**
 * Minimum pill width. The two labels ("Jump to latest" and
 * "Jump to top") have different intrinsic widths, so without a
 * floor the pill would shrink / grow as the mode toggles. A
 * widthIn floor keeps the pill stable across swaps; the wider
 * label still wins naturally on top of that floor.
 */
private val MinPillWidth = 130.dp

/**
 * Floating "Jump to latest" / "Jump to top" pill.
 *
 * Sticky two-mode button toggled by the Option (Alt) key:
 *
 *  - **Default (mode = false)**: down arrow + "Jump to latest".
 *    Click invokes [onClick] (scroll to the bottom).
 *  - **Alternative (mode = true)**: up arrow + "Jump to top".
 *    Click invokes [onJumpToTop] (scroll to offset 0).
 *
 * The mode is a **sticky** [mutableStateOf] toggled by every
 * Option-key DOWN edge detected on a pointer event over the pill.
 * It does not require focus and it does not require Option to
 * stay held — once toggled, the mode persists until the next
 * Option-key DOWN edge flips it back. This is the
 * "press-Option-to-flip-the-mode" interaction; the on-screen
 * label always shows what a click will currently do.
 *
 * Visual stack (bottom → top):
 *  1. Translucent background fill (`borders.normal` at 60% alpha
 *     at rest, full alpha on hover) — carries the frosted tint.
 *  2. `Modifier.blur(20.dp)` on that background layer. On Skia
 *     (IntelliJ Platform) and Android 12+ the chat content
 *     behind the pill is sampled and blurred in place. On older
 *     Android we get a softer translucent colour instead, which
 *     still reads as "frosted" against the chat behind.
 *  3. The Row with the icon and label sits on top, **not
 *     blurred** — only the background carries the blur, so the
 *     icon and text stay sharp. The mode swap uses [Crossfade]
 *     (no size animation) so the pill width is stable across
 *     toggles; a [widthIn] floor absorbs any remaining
 *     micro-wiggle from label-length differences.
 *
 * Show / hide:
 *  - Enter: fade-in 0→1 + slide-up from one full height below
 *    the final position, 140ms with `FastOutSlowInEasing`. The
 *    "below" offset is the input box's top edge, so the button
 *    rises out of the input and parks above it.
 *  - Exit: mirror — fade-out 1→0 + slide-down to the same
 *    offset, 80ms, linear (no easing — we want it gone fast).
 */
@Composable
fun JumpToBottomButton(
  isVisible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onJumpToTop: () -> Unit = {},
) {
  // Sticky mode. Toggled by Option-key DOWN edges in the
  // pointerInput block below. Read both by the foreground
  // label and by the click handler — any state read returns
  // the current value at composition / dispatch time, not a
  // snapshot from when the block was entered.
  var isAlternativeMode by remember { mutableStateOf(false) }

  // Stash the call-site callbacks in `rememberUpdatedState` so
  // the click pointerInput can be keyed on `Unit` and never
  // restart on a state read. The call site usually passes
  // inline lambdas (e.g. `{ scrollState.animateScrollTo(...) }`)
  // which are *not* referentially stable across recompositions;
  // without `rememberUpdatedState`, every parent recomposition
  // would cancel the in-flight click handler. With it, the
  // coroutine captures the latest callback via `currentXxx()`
  // while the `pointerInput(Unit)` block keeps running.
  val currentOnClick by rememberUpdatedState(onClick)
  val currentOnJumpToTop by rememberUpdatedState(onJumpToTop)

  val hoverInteractionSource = remember { MutableInteractionSource() }
  val isHovered by hoverInteractionSource.collectIsHoveredAsState()

  val borderColor = JewelTheme.globalColors.borders.normal
  val textColor = JewelTheme.globalColors.text.normal
  val restBackgroundAlpha = 0.6f
  val hoverBackgroundAlpha = 1.0f
  val backgroundAlpha = if (isHovered) hoverBackgroundAlpha else restBackgroundAlpha

  AnimatedVisibility(
    visible = isVisible,
    modifier = modifier,
    enter = fadeIn(animationSpec = tween(ENTER_DURATION_MS, easing = FastOutSlowInEasing)) +
      slideInVertically(
        animationSpec = tween(ENTER_DURATION_MS, easing = FastOutSlowInEasing),
        initialOffsetY = { fullHeight: Int -> fullHeight }
      ),
    exit = fadeOut(animationSpec = tween(EXIT_DURATION_MS)) +
      slideOutVertically(
        animationSpec = tween(EXIT_DURATION_MS),
        targetOffsetY = { fullHeight: Int -> fullHeight }
      )
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = GradumSpacing.lg),
      contentAlignment = Alignment.BottomCenter
    ) {
      // First pointerInput: Option-key edge detector. Loops
      // over every pointer event (hover, move, press, release)
      // and toggles [isAlternativeMode] on the rising edge of
      // the Alt bit. Keyed on `Unit` so it never restarts —
      // restarting this block would also drop the in-flight
      // Option edge if the user pressed the key mid-event.
      // We also need the foreground click handler to read the
      // current mode, so a separate, non-restarting block is
      // the only safe shape.
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(percent = 50))
          .hoverable(interactionSource = hoverInteractionSource)
          .pointerInput(Unit) {
            awaitPointerEventScope {
              var wasAltDown = false
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val isAltDown = event.keyboardModifiers.isAltPressed
                if (isAltDown && !wasAltDown) {
                  isAlternativeMode = !isAlternativeMode
                }
                wasAltDown = isAltDown
              }
            }
          }
          // Second pointerInput: click handler. Uses
          // `awaitFirstDown` + `waitForUpOrCancellation` which
          // are the right primitive for "press-then-release
          // with no leak" — earlier versions hand-rolled
          // this with raw `awaitPointerEvent` and dropped
          // the press when a MOVE event arrived between DOWN
          // and UP, which is exactly why clicks stopped
          // working. Keyed on `Unit` (callbacks are captured
          // via `rememberUpdatedState` so the block never
          // restarts on a parent recomposition).
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation()
                if (up != null && up.id == down.id) {
                  if (isAlternativeMode) currentOnJumpToTop() else currentOnClick()
                }
              }
            }
          }
      ) {
        // Background fill + backdrop blur. `matchParentSize()`
        // keeps this box sized to the pill so the blur covers
        // the same area the foreground content occupies.
        Box(
          modifier = Modifier
            .matchParentSize()
            .background(borderColor.copy(alpha = backgroundAlpha))
            .blur(BackgroundBlurRadiusDp)
        )

        // Foreground: icon + label. `widthIn` keeps the pill
        // width stable when the labels differ in length;
        // `Crossfade` softens the mode swap without animating
        // size (which would cause the pill to grow / shrink
        // every toggle).
        Row(
          modifier = Modifier
            .widthIn(min = MinPillWidth)
            .height(40.dp)
            .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.sml),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
          Crossfade(
            targetState = isAlternativeMode,
            animationSpec = tween(durationMillis = 110),
            label = "JumpToBottomMode"
          ) { alternative ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
            ) {
              Icon(
                key = if (alternative) AllIconsKeys.General.ArrowUp else AllIconsKeys.General.ArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = message(
                  if (alternative) "gradum.jump.to.top" else "gradum.jump.to.latest"
                ),
                style = JewelTheme.typography.regular.copy(
                  color = textColor,
                  fontWeight = FontWeight.Medium
                )
              )
            }
          }
        }
      }
    }
  }
}
