/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 */

package gradum.idea.chat.ui.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PermissionMode.isDebugMode], the single gate used
 * across the chat UI for debug-only behavior.
 */
class PermissionModeTest {

  @Test
  fun `isDebugMode is true only for the debug wire value`() {
    assertTrue(PermissionMode.isDebugMode(PermissionMode.DEBUG))
    assertFalse(PermissionMode.isDebugMode(PermissionMode.READONLY))
    assertFalse(PermissionMode.isDebugMode(PermissionMode.EDIT))
    assertFalse(PermissionMode.isDebugMode(PermissionMode.AGENT))
    assertFalse(PermissionMode.isDebugMode(""))
  }

  @Test
  fun `isDebugMode is case sensitive against the wire constant`() {
    assertFalse(PermissionMode.isDebugMode("Debug"))
    assertFalse(PermissionMode.isDebugMode("DEBUG"))
  }
}