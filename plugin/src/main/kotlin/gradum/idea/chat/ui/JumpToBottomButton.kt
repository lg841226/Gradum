/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-13  Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalWindowInfo
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
 * Cross-fade duration for the in-place icon + label swap when the
 * user holds Option over the button. Short — we want the preview
 * to feel responsive to a key press, not theatrical.
 */
private const val LABEL_SWAP_DURATION_MS = 110

/**
 * Floating "Jump to latest" / "Jump to top" pill.
 *
 * Two-mode button driven by the Option (Alt) key:
 *
 *  - **Default (no Option)**: icon = down arrow, label = "Jump to
 *    latest". Click invokes [onClick] (scroll to the bottom of the
 *    conversation).
 *  - **Option held**: icon = up arrow, label = "Jump to top".
 *    Click invokes [onJumpToTop] (scroll to the first message).
 *
 * The Option state is read from [LocalWindowInfo] so the preview
 * flips the moment the user presses the key — it does not require
 * the cursor to be over the pill, nor for the pill to be focused.
 * The label is a live preview of what a click *would* do under the
 * current modifier state; the actual dispatch is decided per-press
 * by the same flag read at the DOWN event.
 *
 * Visual stack (bottom → top):
 *  1. Translucent background fill (`borders.normal` at 60% alpha
 *     at rest, full alpha on hover) — carries the frosted tint.
 *  2. `Modifier.blur(20.dp)` on that background layer. On Skia
 *     (IntelliJ Platform) and Android 12+ the chat content behind
 *     the pill is sampled and blurred in place. On older Android
 *     we get a softer translucent colour instead, which still
 *     reads as "frosted" against the chat behind.
 *  3. The Row with the icon and label sits on top, **not blurred**
 *     — only the background carries the blur, so the icon and
 *     text stay sharp. The Row swaps in place via [AnimatedContent]
 *     when Option is held / released.
 *
 * Show / hide:
 *  - Enter: fade-in 0→1 + slide-up from one full height below the
 *    final position, 140ms with `FastOutSlowInEasing`. The
 *    "below" offset is the input box's top edge, so the button
 *    rises out of the input and parks above it.
 *  - Exit: mirror — fade-out 1→0 + slide-down to the same offset,
 *    80ms, linear (no easing — we want it gone fast).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun JumpToBottomButton(
  isVisible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onJumpToTop: () -> Unit = {},
) {
  val windowInfo = LocalWindowInfo.current
  // `isAltPressed` is the JetBrains Compose fork's `expect` extension
  // property on `PointerKeyboardModifiers`; the `skikoMain` actual
  // reads the packed-bit int behind the value class and ANDs it
  // with `KeyboardModifierMasks.AltPressed`. Reading from
  // `LocalWindowInfo` (instead of a per-event sample) means the
  // preview flips the instant Option goes down — no need to wait
  // for the next pointer event.
  val isAltHeld = windowInfo.keyboardModifiers.isAltPressed
  val isAlternativeMode = isAltHeld

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
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(percent = 50))
          .hoverable(interactionSource = hoverInteractionSource)
          .pointerInput(isAltHeld, onClick, onJumpToTop) {
            awaitPointerEventScope {
              // Read Option state at the press DOWN event. The
              // dispatch (onClick vs onJumpToTop) follows the
              // mode the user is currently holding, NOT the
              // preview label — the preview is a hint, not a
              // gate. If the user clicks without Option the pill
              // always means "jump to bottom", regardless of the
              // momentary label flicker.
              val down = awaitPointerEvent(PointerEventPass.Main)
              val wasAltDown = down.keyboardModifiers.isAltPressed
              val downChange = down.changes.firstOrNull { it.pressed }
                ?: return@awaitPointerEventScope
              val up = awaitPointerEvent(PointerEventPass.Main)
              val upChange = up.changes.firstOrNull { !it.pressed && it.id == downChange.id }
              if (upChange != null) {
                if (wasAltDown) onJumpToTop() else onClick()
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

        // The row carries the foreground content (icon + label)
        // and is **not** blurred. `AnimatedContent` cross-fades
        // between the two modes so the swap is smooth rather
        // than popping. We key on `isAlternativeMode` so the
        // transition fires exactly once per Option press /
        // release.
        Row(
          modifier = Modifier
            .height(40.dp)
            .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.sml),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
          AnimatedContent(
            targetState = isAltHeld,
            transitionSpec = {
              (fadeIn(animationSpec = tween(LABEL_SWAP_DURATION_MS)) +
                slideInVertically(
                  animationSpec = tween(LABEL_SWAP_DURATION_MS, easing = FastOutSlowInEasing),
                  initialOffsetY = { fullHeight: Int -> fullHeight / 2 }
                )) togetherWith
                (fadeOut(animationSpec = tween(LABEL_SWAP_DURATION_MS)) +
                  slideOutVertically(
                    animationSpec = tween(LABEL_SWAP_DURATION_MS, easing = FastOutSlowInEasing),
                    targetOffsetY = { fullHeight: Int -> -fullHeight / 2 }
                  ))
            },
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
