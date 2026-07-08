/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JumpToBottomButton.kt  2026-07-07 23:55:00 Changed by gwy
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Badge
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.typography

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
 * [unreadCount] is rendered as a numeric badge anchored to the
 * top-right of the button. Counts above 99 collapse to "99+" so the
 * badge can't stretch the layout for runaway chat backlogs. A
 * `unreadCount` of `0` hides the badge entirely.
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
    unreadCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 150)),
        modifier = modifier
    ) {
        val interactionSource: MutableInteractionSource =
            remember { MutableInteractionSource() }
        val buttonText: String = message("gradum.jump.to.latest")
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(JewelTheme.globalColors.panelBackground)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick
                    )
                    .pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // TODO(gwy): placeholder icon — replace with the user-chosen
                //  icon once selected. Currently AllIconsKeys.General.ChevronDown
                //  is used as a stand-in that conveys the right direction.
                Icon(
                    key = AllIconsKeys.General.ChevronDown,
                    contentDescription = buttonText,
                    modifier = Modifier.size(16.dp)
                )
                Text(text = buttonText, style = JewelTheme.typography.regular)
            }
            if (unreadCount > 0) {
                val displayCount: String = if (unreadCount > 99) "99+" else unreadCount.toString()
                Badge(
                    style = JewelTheme.badgeStyle.blue,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Nudge the badge so it visually breaks out of the
                        // button's rounded silhouette on the top-right corner,
                        // matching the standard "notification dot" pattern
                        // used in messaging apps.
                        .offset(x = 6.dp, y = (-4).dp)
                ) {
                    Text(text = displayCount)
                }
            }
        }
    }
}
