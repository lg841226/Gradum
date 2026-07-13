/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-13  Changed by gwy
 */

@file:OptIn(ExperimentalComposeUiApi::class)

package gradum.idea.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Background blur radius (in dp). At 20dp the chat behind the
 * pill becomes a soft frosted haze — the classic "liquid
 * glass" look. See [blur] for platform caveats (Skia and
 * Android 12+ get a real backdrop blur; older Android falls
 * back to a softer translucent colour).
 */
private val BackgroundBlurRadiusDp = 20.dp

/**
 * Pill-shaped "Jump to latest" / "Jump to top" button that floats
 * above the chat input. Mirrors the original (commit `c5430f5`)
 * layout — a single [Row] with `clip` + `background` +
 * `clickable`, with a layered backdrop blur for the frosted-glass
 * tint — but extended with a sticky two-mode toggle.
 *
 * ## Mode toggle
 *
 * The button has two visual states driven by a sticky
 * [mutableStateOf]:
 *
 *  - **default** (`false`): down arrow + "Jump to latest"; click
 *    invokes [onClick] (scroll to the bottom).
 *  - **alternative** (`true`): up arrow + "Jump to top"; click
 *    invokes [onJumpToTop] (scroll to offset 0).
 *
 * The state is flipped on every Option (Alt) key **rising edge**
 * detected via the global [LocalWindowInfo] modifier stream. The
 * host platform (IntelliJ / Skia) writes to
 * `WindowInfoImpl.GlobalKeyboardModifiers` on every AWT key event,
 * so `snapshotFlow { ... .isAltPressed }` fires the instant the
 * user presses the key — no pointer activity, no focus, no
 * pointerHover required. Earlier versions of this file used a
 * `pointerInput(Unit)` loop over `awaitPointerEvent`, which only
 * sees events while the mouse is moving; that's why "press
 * Option while hovering" was unreliable.
 *
 * Show / hide is a plain fade (no slide — the slide-up animation
 * felt laggy on top of the mode swap).
 *
 * ## Visual stack
 *
 *  1. Translucent `panelBackground` fill at rest, full alpha on
 *     hover. Carries the frosted tint.
 *  2. `Modifier.blur(20.dp)` on that background layer. Real
 *     backdrop blur on Skia and Android 12+; a soft translucent
 *     fall-back on older Android.
 *  3. The icon + label sit on top, **not blurred** — they stay
 *     sharp regardless of platform. Mode swap is instant (no
 *     Crossfade / AnimatedContent) so there's no animation
 *     overhead on every toggle; the previous Crossfade at 110ms
 *     is what was reading as "laggy" when Option was mashed
 *     twice in succession.
 */
@Composable
fun JumpToBottomButton(
  isVisible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onJumpToTop: () -> Unit = {},
) {
  // Sticky mode. Toggled on Option rising edges by the
  // LaunchedEffect below; read by the foreground label and the
  // click handler.
  var isAlternativeMode by remember { mutableStateOf(false) }

  val windowInfo = LocalWindowInfo.current

  // Option-key edge detector. Subscribes to the global modifier
  // stream from the host platform and flips the mode on every
  // rising edge. Restarted when [isVisible] flips so we don't
  // silently toggle while the pill is hidden.
  LaunchedEffect(isVisible) {
    if (!isVisible) return@LaunchedEffect
    var wasAltDown = false
    snapshotFlow { windowInfo.keyboardModifiers.isAltPressed }.collect { isAltDown ->
      if (isAltDown && !wasAltDown) {
        isAlternativeMode = !isAlternativeMode
      }
      wasAltDown = isAltDown
    }
  }

  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(animationSpec = tween(durationMillis = 150)),
    exit = fadeOut(animationSpec = tween(durationMillis = 150)),
    modifier = modifier
  ) {
    val interactionSource: MutableInteractionSource =
      remember { MutableInteractionSource() }
    val buttonText: String = message(
      if (isAlternativeMode) "gradum.jump.to.top" else "gradum.jump.to.latest"
    )
    Box {
      // Pill = a background blur layer underneath a sharp Row.
      // `matchParentSize` keeps the blur exactly under the Row's
      // footprint so we don't blur the gap between the Row and
      // its surrounding layout.
      Box(
        modifier = Modifier
          .matchParentSize()
          .clip(RoundedCornerShape(percent = 50))
          .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.92f))
          .blur(BackgroundBlurRadiusDp)
      )
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(percent = 50))
          .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
              if (isAlternativeMode) onJumpToTop() else onClick()
            }
          )
          .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
          .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Icon(
          key = if (isAlternativeMode) AllIconsKeys.General.ArrowUp else AllIconsKeys.General.ArrowDown,
          contentDescription = buttonText,
          modifier = Modifier.size(16.dp)
        )
        Text(text = buttonText, style = JewelTheme.typography.regular)
      }
    }
  }
}
