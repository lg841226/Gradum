/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-13  Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
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
 * Floating "Jump to latest" pill.
 *
 * Layered structure (from bottom to top in the visual stack):
 *  1. A translucent background fill (`borders.normal` at 60% alpha
 *     at rest, full alpha on hover). This is what carries the
 *     frosted-glass tint.
 *  2. `Modifier.blur(20.dp)` applied to that background. On Skia
 *     (the IntelliJ Platform's renderer) and Android API 31+ the
 *     blur is a real backdrop effect — chat content beneath the
 *     button is sampled and blurred. On older Android we get a
 *     softer translucent colour instead, which still reads as
 *     "frosted" against the chat behind.
 *  3. The Row with the icon and "Jump to latest" label sits on top,
 *     **not blurred** — only the background layer carries the
 *     blur, so the icon and text stay sharp.
 *
 * Interaction:
 * - Plain click: jump to bottom (`onClick`).
 * - Option-click (Alt-click on Windows/Linux, Option-click on
 *   macOS): open the action popup with three shortcuts
 *   (`onJumpToTop`, `onCopyLast`, `onCollapseMiddle`). The Option
 *   state is read at the **down** event by ANDing
 *   `keyboardModifiers` with `KeyboardModifierMasks.AltPressed`,
 *   so the modifier must be held when the press starts —
 *   releasing it before the press begins falls back to the plain
 *   click.
 *
 * Animation:
 * - Enter: fade-in 0→1 + slide-up from one full height below the
 *   final position, 140ms with `FastOutSlowInEasing`. The
 *   "below" offset is the input box's top edge, so the button
 *   rises out of the input and parks above it.
 * - Exit: mirror — fade-out 1→0 + slide-down to the same offset,
 *   80ms, linear (no easing — we want it gone fast).
 */
@Composable
fun JumpToBottomButton(
  isVisible: Boolean,
  onClick: () -> Unit,
  onJumpToTop: () -> Unit = {},
  onCopyLast: () -> Unit = {},
  onCollapseMiddle: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var showOptionMenu by remember { mutableStateOf(false) }
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
      // The popup lives in the same parent as the button so its
      // floating position is anchored to the button. The Column
      // stacks menu-above-button; when the menu is hidden the
      // button sits at the bottom centre on its own.
      Column(
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        if (showOptionMenu) {
          PopupMenu(
            onDismissRequest = {
              showOptionMenu = false
              true
            },
            horizontalAlignment = Alignment.Start
          ) {
            selectableItem(
              selected = false,
              onClick = {
                showOptionMenu = false
                onJumpToTop()
              }
            ) { Text(message("gradum.jump.to.top")) }
            selectableItem(
              selected = false,
              onClick = {
                showOptionMenu = false
                onCopyLast()
              }
            ) { Text(message("gradum.copy.last")) }
            selectableItem(
              selected = false,
              onClick = {
                showOptionMenu = false
                onCollapseMiddle()
              }
            ) { Text(message("gradum.collapse.middle")) }
          }
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .hoverable(interactionSource = hoverInteractionSource)
            .pointerInput(onClick, onJumpToTop, onCopyLast, onCollapseMiddle) {
              awaitPointerEventScope {
                // Wait for the first DOWN event with the main pass,
                // capture the Option/Alt state at the press moment,
                // and then wait for the matching UP. A press that
                // never releases (e.g. user drags off the button) is
                // ignored via `consume()` not being called.
                val down = awaitPointerEvent(PointerEventPass.Main)
                // `isAltPressed` is the JetBrains Compose fork's
                // `expect` extension property on
                // `PointerKeyboardModifiers`; the `skikoMain`
                // actual implementation reads the packed-bit
                // int behind the value class and ANDs it with
                // `KeyboardModifierMasks.AltPressed`. Reading it
                // at the press DOWN event is the only point that
                // matters — the user must have Option held when
                // the press begins; releasing it before the press
                // falls back to the plain click.
                val isOptionHeld = down.keyboardModifiers.isAltPressed
                val downChange = down.changes.firstOrNull { it.pressed }
                  ?: return@awaitPointerEventScope
                val up = awaitPointerEvent(PointerEventPass.Main)
                val upChange = up.changes.firstOrNull { !it.pressed && it.id == downChange.id }
                if (upChange != null) {
                  if (isOptionHeld) showOptionMenu = true
                  else onClick()
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

          Row(
            modifier = Modifier
              .height(40.dp)
              .padding(horizontal = GradumSpacing.lg, vertical = GradumSpacing.sml),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
          ) {
            Icon(
              key = AllIconsKeys.General.ArrowDown,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = message("gradum.jump.to.latest"),
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
