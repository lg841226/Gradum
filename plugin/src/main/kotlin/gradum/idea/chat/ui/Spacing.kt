/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Spacing.kt  2026-06-29 10:16:35 Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.ui.unit.dp

/**
 * Centralized spacing constants for the Gradum UI.
 *
 * Use these instead of inline magic numbers to ensure
 * consistent visual rhythm across all components.
 */
object GradumSpacing {
    /** Tiny — icon inner padding, tiny gaps. */
    val xs = 2.dp

    /** Small — element-to-element gaps, inline separators. */
    val sm = 4.dp

    /** Small-medium — compact row spacing, tight element gaps. */
    val sml = 6.dp

    /** Medium — block inner padding, row content spacing. */
    val md = 8.dp

    /** Large — block-to-block gaps, section separators. */
    val lg = 12.dp

    /** Extra large — page-level horizontal padding. */
    val xl = 16.dp

    /** Large-medium — between xl and xxl, e.g. popup internal spacing. */
    val lrl = 18.dp

    /** Medium-large — between xl and xxl, e.g. card inner padding. */
    val ml = 20.dp

    /** Extra, extra large — major section gaps. */
    val xxl = 24.dp

    /** Extra extra extra large — hero spacers. */
    val xxxl = 32.dp
}
