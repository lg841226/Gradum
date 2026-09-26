/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MaterializedMcpToolsTest.kt  2026-09-26 Changed by gwy
 */
package gradum.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the round-based retention of [MaterializedMcpTools]: tools stay
 * exposed for the configured number of rounds, older tools are trimmed, and
 * re-exposing a tool refreshes its round so it does not age out early.
 */
class MaterializedMcpToolsTest {

  @Test
  fun `tools are retained across the configured number of rounds`() {
    val registry = MaterializedMcpTools(maxRounds = 3)
    registry.newRound()
    registry.add("tool_a")
    registry.newRound()
    registry.add("tool_b")

    assertTrue("tool_a" in registry)
    assertTrue("tool_b" in registry)
    assertEquals(setOf("tool_a", "tool_b"), registry.names)
  }

  @Test
  fun `older tools are trimmed after more than the retention window passes`() {
    val registry = MaterializedMcpTools(maxRounds = 3)
    registry.newRound()
    registry.add("early_tool")

    // Two more rounds fill the window; the early tool is still retained.
    registry.newRound()
    registry.add("mid_tool")
    registry.newRound()
    registry.add("late_tool")
    assertTrue("early_tool" in registry)

    // A fourth round pushes the early tool (round 1) past the last 3 rounds.
    registry.newRound()
    registry.add("newest_tool")
    assertFalse("early_tool" in registry)
    assertEquals(setOf("mid_tool", "late_tool", "newest_tool"), registry.names)
  }

  @Test
  fun `re-exposing a tool refreshes its round so it stays alive`() {
    val registry = MaterializedMcpTools(maxRounds = 2)
    registry.newRound()
    registry.add("stable_tool")
    registry.add("forgotten_tool")

    registry.newRound()
    registry.add("stable_tool") // refreshed: moves to round 2, forgotten_tool does not
    registry.newRound()
    registry.add("newest_tool")

    // Round 1 is trimmed, but stable_tool was refreshed into round 2 and survives;
    // forgotten_tool (never refreshed) ages out.
    assertTrue("stable_tool" in registry)
    assertFalse("forgotten_tool" in registry)
    assertEquals(setOf("stable_tool", "newest_tool"), registry.names)
  }

  @Test
  fun `registry starts empty`() {
    val registry = MaterializedMcpTools()
    assertTrue(registry.isEmpty)
    assertTrue(registry.names.isEmpty())
  }
}
