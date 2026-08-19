/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TodoSkillTest.kt  2026-08-19 12:00:00 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shared [TodoManager] plan lifecycle that feeds the agent's
 * task reminder prompt and the chat panel's plan rendering:
 * initialization, advancement, and completion. Every mutation result
 * must carry the full `tasks` list back to the renderers so the
 * collapsible plan / completed rows can draw per-task states.
 */
class TodoSkillTest {

  @Test
  fun `initialization seeds the plan with the first task`() {
    val manager = TodoManager()
    val result = manager.initializeTasks(listOf("a", "b", "c"))

    assertTrue(result is SkillResult.Success)
    assertEquals(3, result.data["totalTasks"])
    assertEquals("a", result.data["currentTask"])
    assertEquals(0, result.data["currentIndex"])
    assertEquals(listOf("a", "b", "c"), result.data["tasks"])
  }

  @Test
  fun `completing advances the current task and keeps the full list`() {
    val manager = TodoManager()
    manager.initializeTasks(listOf("a", "b", "c"))

    val first = manager.completeCurrentTask()
    assertTrue(first is SkillResult.Success)
    assertEquals(false, first.data["completed"])
    assertEquals(1, first.data["currentIndex"])
    assertEquals("b", first.data["currentTask"])
    assertEquals(listOf("a", "b", "c"), first.data["tasks"])

    manager.completeCurrentTask()
    val last = manager.completeCurrentTask()
    assertTrue(last is SkillResult.Success)
    assertEquals(true, last.data["completed"])
    assertEquals(listOf("a", "b", "c"), last.data["tasks"])
  }

  @Test
  fun `skipping advances without completing and keeps the full list`() {
    val manager = TodoManager()
    manager.initializeTasks(listOf("a", "b", "c"))

    val result = manager.skipTask()
    assertTrue(result is SkillResult.Success)
    assertEquals(true, result.data["skipped"])
    assertEquals("a", result.data["skippedTask"])
    assertEquals(1, result.data["currentIndex"])
    assertEquals("b", result.data["currentTask"])
    assertEquals(listOf("a", "b", "c"), result.data["tasks"])
  }

  @Test
  fun `reminder disappears after the last task`() {
    val manager = TodoManager()
    manager.initializeTasks(listOf("a", "b"))
    manager.completeCurrentTask()
    assertEquals(true, manager.getTaskReminder() != null)
    manager.completeCurrentTask()
    assertEquals(null, manager.getTaskReminder())
  }

  @Test
  fun `reset clears the plan`() {
    val manager = TodoManager()
    manager.initializeTasks(listOf("a"))
    manager.resetTaskList()
    assertEquals(null, manager.getTaskReminder())
  }
}
