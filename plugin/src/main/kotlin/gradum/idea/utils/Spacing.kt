/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Spacing.kt  2026-09-24 13:13:15 Changed by gwy
 */

package gradum.idea.utils

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized spacing constants for the Gradum UI.
 *
 * Use these instead of inline magic numbers to ensure
 * consistent visual rhythm across all components.
 */
object GradumSpacing {
  val xs: Dp = 2.dp
  val sm: Dp = 4.dp
  val sml: Dp = 6.dp
  val md: Dp = 8.dp
  val lg: Dp = 12.dp
  val xl: Dp = 16.dp
  val lrl: Dp = 18.dp
  val ml: Dp = 20.dp
  val xxl: Dp = 24.dp

  /** Letter spacing for the home-screen welcome heading. */
  val welcomeTitleTracking = 0.5.sp
}
