/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DelegateSkillTest.kt  2026-08-26 11:20:58 Changed by gwy
 */

package gradum.skill

import gradum.*
import kotlin.test.*

/**
 * Pins [DelegateSkill] behavior that the agent orchestration and the
 * sub-agent lifecycle depend on:
 *
 *  1. Schema structure — the LLM must discover `task` and `title`.
 *  2. Tool-mode gates — the skill must be available in all modes.
 *  3. Validation — missing required parameters return structured errors.
 *  4. Context validation — missing [AgentConfiguration] or [emitEvent]
 *     in [SkillContext] return client errors, not NPEs.
 *  5. Event-stream ownership — the skill signals
 *     [manageOwnEventStream] so the agent loop does not emit
 *     duplicate `tool_call_start` / `tool_call` events.
 */
class DelegateSkillTest {

  private val skill: DelegateSkill = DelegateSkill()

  @Test
  fun `getSchema returns a function schema with task and title`() {
    val schema: Map<String, Any> = skill.getSchema()

    assertEquals(
      "function",
      schema["type"]
    )
    @Suppress("UNCHECKED_CAST")
    val function: Map<String, Any> = schema["function"] as? Map<String, Any>
      ?: error("schema missing 'function' key")

    assertEquals(
      "delegate_task",
      function["name"]
    )
    assertTrue(
      (function["description"] as? String)?.isNotBlank() ?: false,
      "description must not be blank"
    )

    @Suppress("UNCHECKED_CAST")
    val parameters: Map<String, Any> = function["parameters"] as? Map<String, Any>
      ?: error("schema missing 'function.parameters'")

    @Suppress("UNCHECKED_CAST")
    val properties: Map<String, Any> = parameters["properties"] as? Map<String, Any>
      ?: error("schema missing 'function.parameters.properties'")

    assertTrue(properties.containsKey("task"), "schema must have a 'task' property")
    assertTrue(properties.containsKey("title"), "schema must have a 'title' property")

    @Suppress("UNCHECKED_CAST")
    val required: List<String> = parameters["required"] as? List<String>
      ?: error("schema missing 'function.parameters.required'")

    assertTrue("task" in required, "task must be a required parameter")
    assertFalse("title" in required, "title must NOT be a required parameter")
  }

  @Test
  fun `allows agent edit and read-only tool modes`() {
    assertTrue(skill.allows(ToolMode.AGENT), "must allow AGENT mode")
    assertTrue(skill.allows(ToolMode.EDIT), "must allow EDIT mode")
    assertTrue(skill.allows(ToolMode.READ_ONLY), "must allow READ_ONLY mode")
  }

  @Test
  fun `manageOwnEventStream is true`() {
    assertTrue(skill.manageOwnEventStream, "delegate skill must manage its own event stream")
  }

  @Test
  fun `execute fails when task parameter is missing`() {
    val context = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = "/tmp",
      agentConfiguration = AgentConfiguration(provider = Provider.OLLAMA),
      emitEvent = { _, _ -> }
    )
    val result = skill.execute(arguments = emptyMap(), context)

    assertIs<SkillResult.Failure>(value = result)
    assertEquals(
      "INVALID_PARAMETER",
      result.code
    )
  }

  @Test
  fun `execute fails when agentConfiguration is not available`() {
    val context = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = "/tmp",
      emitEvent = { _, _ -> }
    )
    val longTask = "a".repeat(n = GradumConfig.MIN_TASK_LENGTH)
    val result = skill.execute(arguments = mapOf("task" to longTask), context)

    assertIs<SkillResult.Failure>(value = result)
    assertEquals(
      "CLIENT_ERROR",
      result.code
    )
    assertTrue(
      result.message.contains(other = "AgentConfiguration", ignoreCase = true),
      "error message should mention AgentConfiguration"
    )
  }

  @Test
  fun `execute fails when emitEvent is not available`() {
    val context = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = "/tmp",
      agentConfiguration = AgentConfiguration(provider = Provider.OLLAMA)
    )
    val longTask = "a".repeat(n = GradumConfig.MIN_TASK_LENGTH)
    val result = skill.execute(arguments = mapOf("task" to longTask), context)

    assertIs<SkillResult.Failure>(value = result)
    assertEquals(
      "CLIENT_ERROR",
      result.code
    )
    assertTrue(
      result.message.contains(other = "emitEvent", ignoreCase = true)
        || result.message.contains(other = "event", ignoreCase = true),
      "error message should mention emitEvent"
    )
  }
}
