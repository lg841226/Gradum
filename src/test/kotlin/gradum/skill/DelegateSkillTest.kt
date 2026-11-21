package gradum.skill

import gradum.AgentConfiguration
import gradum.GradumConfig
import gradum.SkillResult
import gradum.mode.ModeRegistry
import gradum.skill.builtin.DelegateSkill
import kotlin.test.*

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
    assertTrue(skill.allows(ModeRegistry.resolve("agent")), "must allow agent mode")
    assertTrue(skill.allows(ModeRegistry.resolve("edit")), "must allow edit mode")
    assertTrue(skill.allows(ModeRegistry.resolve("read_only")), "must allow read_only mode")
  }

  @Test
  fun `manageOwnEventStream is true`() {
    assertTrue(skill.manageOwnEventStream, "delegate skill must manage its own event stream")
  }

  @Test
  fun `execute fails when task parameter is missing`() {
    val context = SkillContext(
      projectRoot = "/tmp",
      agentConfiguration = AgentConfiguration(),
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
      projectRoot = "/tmp",
      agentConfiguration = AgentConfiguration()
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
