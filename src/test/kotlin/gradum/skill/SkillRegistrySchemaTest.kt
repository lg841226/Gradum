package gradum.skill

import gradum.mode.AgentMode
import gradum.mode.ModeRegistry
import java.nio.file.Files
import kotlin.test.*

/**
 * Pins the schema filter that [SkillRegistry.getSchemas] applies per mode.
 * Modes are configuration now: tool visibility is determined exclusively by
 * the active mode's declarative `tools` allow/deny selectors, evaluated
 * through [Skill.allows] with an [AgentMode] resolved from [ModeRegistry].
 * There is no second source of truth (no hardcoded mode -> skill mapping in
 * the registry, no per-skill whitelist consulted at runtime).
 *
 * If a future refactor adds a new Skill, this test does not need to change:
 * the new Skill is simply matched against the mode selectors, and the
 * assertSkillNamesSeen list will surface the change because the order of the
 * registered skills (and the names they claim) is part of the visible
 * contract with the LLM.
 */
class SkillRegistrySchemaTest {

  private var previousConfigDir: String? = null

  @BeforeTest
  fun isolateConfigDir() {
    previousConfigDir = System.getProperty("gradum.server.configDir")
    System.setProperty(
      "gradum.server.configDir",
      Files.createTempDirectory("gradum-schema-config-").toAbsolutePath().toString()
    )
    ModeRegistry.reload()
  }

  @AfterTest
  fun restoreConfigDir() {
    if (previousConfigDir == null) System.clearProperty("gradum.server.configDir")
    else System.setProperty("gradum.server.configDir", previousConfigDir!!)
    ModeRegistry.reload()
  }

  @Test
  fun `agent mode exposes every registered skill`() {
    val names: List<String> = SkillRegistry.getSchemas().map { nameOf(it) }
    assertEquals(
      EXPECTED_ALL,
      names.toSet(),
      "agent must expose every registered skill, got: $names"
    )
  }

  @Test
  fun `read-only mode exposes only inspection skills`() {
    val names: Set<String> = SkillRegistry.getSchemas(toolMode = "read_only").map { nameOf(it) }.toSet()
    assertEquals(
      EXPECTED_READ_ONLY,
      names,
      "read_only must expose only inspection skills, got: $names"
    )
  }

  @Test
  fun `edit mode exposes write skills only, not task-planning`() {
    val names: Set<String> = SkillRegistry.getSchemas(toolMode = "edit").map { nameOf(it) }.toSet()
    assertEquals(
      EXPECTED_EDIT,
      names,
      "edit must expose write + read skills only, got: $names"
    )
    assertFalse("to_do" in names, "edit must not expose to_do, got: $names")
    assertFalse("finish_to_do_item" in names, "edit must not expose finish_to_do_item, got: $names")
  }

  @Test
  fun `yolo mode exposes every registered skill`() {
    val names: Set<String> = SkillRegistry.getSchemas(toolMode = "yolo").map { nameOf(it) }.toSet()
    assertEquals(
      EXPECTED_ALL,
      names,
      "yolo must expose every registered skill, got: $names"
    )
  }

  @Test
  fun `each schema has a name field that matches its registered skill`() {
    for (mode: AgentMode in ModeRegistry.list()) {
      for (schema in SkillRegistry.getSchemas(toolMode = mode.id)) {
        val schemaName: String = nameOf(schema)
        val registered: Skill? = SkillRegistry.getSkill(schemaName)
        assertTrue(registered != null, "Schema $schemaName in mode ${mode.id} is not registered, mode=${mode.id}")
      }
    }
  }

  @Test
  fun `mode selectors and getSchemas are the same source of truth`() {
    for (mode: AgentMode in ModeRegistry.list()) {
      val schemaNames: Set<String> =
        SkillRegistry.getSchemas(toolMode = mode.id).map { nameOf(it) }.toSet()
      val allowedNames: Set<String> = SkillRegistry.getAllSkills()
        .filter { skill -> skill.allows(mode) }
        .map { it.skillName }
        .toSet()
      assertEquals(
        allowedNames, schemaNames,
        "getSchemas(${mode.id}) and allows(${mode.id}) disagree: " +
          "schemas=$schemaNames, allowed=$allowedNames",
      )
    }
  }

  // Schema maps carry the Skill's name under the standard OpenAI
  // tool shape. We pull it back out so the test can assert by name
  // rather than by reference identity. If the schema shape changes
  // the test will throw and the next person to touch the registry
  // will see what they broke.
  @Suppress("UNCHECKED_CAST")
  private fun nameOf(schema: Map<String, Any>): String {
    val function: Map<String, Any> = schema["function"] as Map<String, Any>
    return function["name"] as String
  }

  private companion object {
    val EXPECTED_ALL: Set<String> = setOf(
      "read_file",
      "explore_project",
      "run_cmd",
      "write_file",
      "to_do",
      "finish_to_do_item",
      "delegate_task",
      "grep",
      "glob",
      "search_web",
    )
    val EXPECTED_READ_ONLY: Set<String> = setOf(
      "read_file",
      "explore_project",
      "run_cmd",
      "delegate_task",
      "grep",
      "glob",
      "search_web",
    )
    val EXPECTED_EDIT: Set<String> = setOf(
      "read_file",
      "explore_project",
      "run_cmd",
      "write_file",
      "delegate_task",
      "grep",
      "glob",
      "search_web",
    )
  }
}
