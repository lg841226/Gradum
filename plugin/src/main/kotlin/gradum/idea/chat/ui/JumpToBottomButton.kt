/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-08 11:26:31 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

const val DURATION_MILLIS: Int = 150

/**
 * Pill-shaped "Jump to latest" button that floats above the chat
 * input. Mirrors the visual language of the code block's
 * [gradum.idea.chat.ui.GradumCodeBlockRenderer.CodeBlockToolbar]
 * copy button (icon + text + rounded background) so the chat panel
 * has a consistent "floating action" pattern.
 *
 * Visibility is controlled by [isVisible]: when `false` the button
 * fades out (150 ms), when `true` it fades in (150 ms). The caller
 * derives [isVisible] from whether the message list is scrollable
 * and whether the user is currently within the "at bottom" tolerance
 * of the end of the list.
 *
 * [enabled] is forwarded to the underlying click handler. The
 * caller sets this to `false` while an animated scroll-to-bottom
 * is in flight, so a second click can't stack another animation on
 * top of the first one (which would race the fade-out and cause the
 * button to flash).
 */
@Composable
fun JumpToBottomButton(
    isVisible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(DURATION_MILLIS)),
        exit = fadeOut(animationSpec = tween(DURATION_MILLIS))
    ) {
        val buttonText: String = message("gradum.jump.to.latest")
        val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                // Shadow goes first so it's drawn behind the pill
                // outline. `clip = false` leaves the subsequent
                // `.clip(...)` to handle content shaping; the
                // shadow itself follows the same shape outline.
                .shadow(
                    elevation = 2.dp,
                    shape = RoundedCornerShape(percent = 50),
                    clip = false
                )
                .clip(RoundedCornerShape(percent = 50))
                // 92% opacity on the panel background lets the chat
                // surface bleed through a touch — the button reads as
                // a translucent floating element rather than a solid
                // block in both light and dark themes. Icon and text
                // stay fully opaque above the clip so contrast against
                // the chat content is unaffected.
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.92f))
                .clickable(
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                    interactionSource = interactionSource
                )
                .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
                .padding(horizontal = 14.dp, vertical = GradumSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
            Icon(
                key = GradumIcons.ScrollDown,
                contentDescription = buttonText
            )
            Text(
                text = buttonText,
                style = JewelTheme.typography.regular
            )
        }
    }
}
