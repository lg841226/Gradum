/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentConfigurationTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the wire format that the plugin sends for [ToolMode]. The plugin
 * emits `"read_only"` / `"edit"` / `"agent"` (lowercase)
 * — see [gradum.idea.chat.ui.input.PermissionMode] — and the server's
 * [ToolMode.fromStringOrDefault] must translate those to the actual
 * enum constants. If this translation ever regresses to be case-sensitive
 * or to reject lowercase values, the read-only mode is silently treated
 * as agent and the security gate in `Agent.executeSingleTool` becomes a
 * no-op. The default fallback (unknown -> AGENT) is also pinned because
 * it is the silent-success path that previously masked the bug.
 */
class AgentConfigurationTest {

  @Test
  fun `read_only wire value resolves to READ_ONLY`() {
    assertEquals(
      ToolMode.READ_ONLY,
      ToolMode.fromStringOrDefault(rawValue = "read_only")
    )
  }

  @Test
  fun `edit wire value resolves to EDIT`() {
    assertEquals(
      ToolMode.EDIT,
      ToolMode.fromStringOrDefault(rawValue = "edit")
    )
  }

  @Test
  fun `agent wire value resolves to AGENT`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "agent")
    )
  }

  @Test
  fun `uppercase wire value also resolves (case insensitive)`() {
    assertEquals(
      ToolMode.READ_ONLY,
      ToolMode.fromStringOrDefault(rawValue = "READ_ONLY")
    )
    assertEquals(
      ToolMode.EDIT,
      ToolMode.fromStringOrDefault(rawValue = "EDIT")
    )
  }

  @Test
  fun `old wire values still resolve via aliases`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "write")
    )
    assertEquals(
      ToolMode.EDIT,
      ToolMode.fromStringOrDefault(rawValue = "single_step")
    )
  }

  @Test
  fun `unknown wire value falls back to AGENT`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "garbage")
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "Read-only Permissions")
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "只读权限")
    )
  }

  @Test
  fun `null and blank wire value falls back to AGENT`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = null)
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "")
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "   ")
    )
  }
}
