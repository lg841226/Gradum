/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Spacing.kt  2026-07-29 18:32:58 Changed by gwy
 */

package gradum.idea.utils

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized spacing constants for the Gradum UI.
 *
 * Use these instead of inline magic numbers to ensure
 * consistent visual rhythm across all components.
 */
object GradumSpacing {
  val xs = 2.dp
  val sm = 4.dp
  val sml = 6.dp
  val md = 8.dp
  val lg = 12.dp
  val xl = 16.dp
  val lrl = 18.dp
  val ml = 20.dp
  val xxl = 24.dp

  /** Letter spacing for the home-screen welcome heading. */
  val welcomeTitleTracking = 0.5.sp
}
