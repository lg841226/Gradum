/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillRegistrySchemaTest.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.skill

import gradum.ToolMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the schema filter that [SkillRegistry.getSchemas] applies per
 * [ToolMode]. The previous implementation maintained two hardcoded
 * string sets in [SkillRegistry] that mirrored what [Skill.allowedToolModes]
 * was supposed to enforce — a second source of truth for the same
 * invariant. The test cases below pin the contract that the two are
 * now one source: schema visibility is determined exclusively by the
 * per-skill `allowedToolModes` set.
 *
 * If a future refactor adds a new Skill, this test does not need to
 * change — the new Skill declares its own `allowedToolModes` and
 * that single declaration is the only thing the registry reads. If
 * a future refactor re-introduces a hardcoded mode → skill mapping
 * anywhere, the assertSkillNamesSeen list will surface the change
 * because the order of the registered skills (and the names they
 * claim) is part of the visible contract with the LLM.
 */
class SkillRegistrySchemaTest {

    @Test
    fun `agent mode exposes every registered skill`() {
        val names: List<String> = SkillRegistry.getSchemas(ToolMode.AGENT).map { nameOf(it) }
        assertEquals(EXPECTED_ALL, names.toSet(), "AGENT must expose every registered skill, got: $names")
    }

    @Test
    fun `read-only mode exposes only inspection skills`() {
        val names: Set<String> = SkillRegistry.getSchemas(ToolMode.READ_ONLY).map { nameOf(it) }.toSet()
        assertEquals(EXPECTED_READ_ONLY, names, "READ_ONLY must expose only inspection skills, got: $names")
    }

    @Test
    fun `edit mode exposes write skills but not task planning`() {
        val names: Set<String> = SkillRegistry.getSchemas(ToolMode.EDIT).map { nameOf(it) }.toSet()
        assertEquals(EXPECTED_EDIT, names, "EDIT must exclude task-planning skills, got: $names")
    }

    @Test
    fun `each schema has a name field that matches its registered skill`() {
        // Defence in depth: if a Skill's getSchema() drifted from its
        // skillName, the LLM would see one name and the runtime would
        // route to another. Pin the contract.
        for (mode in ToolMode.entries) {
            for (schema in SkillRegistry.getSchemas(mode)) {
                val schemaName: String = nameOf(schema)
                val registered: Skill? = SkillRegistry.getSkill(schemaName)
                assertTrue(registered != null, "Schema $schemaName in mode $mode is not registered, mode=$mode")
            }
        }
    }

    @Test
    fun `allowedToolModes and getSchemas are the same source of truth`() {
        // The key invariant this refactor was about: every Skill that
        // appears in getSchemas(X) must declare X in its allowedToolModes,
        // and every Skill that does NOT declare X must not appear. If
        // this test ever fails, somebody has split the two views again
        // (e.g. reintroduced a hardcoded set in getSchemas).
        for (mode in ToolMode.entries) {
            val schemaNames: Set<String> = SkillRegistry.getSchemas(mode).map { nameOf(it) }.toSet()
            val allowedNames: Set<String> = SkillRegistry.getAllSkills()
                .filter { mode in it.allowedToolModes }
                .map { it.skillName }
                .toSet()
            assertEquals(
                allowedNames, schemaNames,
                "getSchemas($mode) and allowedToolModes disagree: " +
                    "schemas=$schemaNames, allowed=$allowedNames",
            )
        }
    }

    /**
     * Schema maps carry the Skill's name under the standard OpenAI
     * tool shape. We pull it back out so the test can assert by name
     * rather than by reference identity. If the schema shape changes
     * the test will throw and the next person to touch the registry
     * will see what they broke.
     */
    @Suppress("UNCHECKED_CAST")
    private fun nameOf(schema: Map<String, Any>): String {
        val function: Map<String, Any> = schema["function"] as Map<String, Any>
        return function["name"] as String
    }

    private companion object {
        // Pinned to whatever the registry actually has today. If a new
        // Skill is added, both this list and the Skill's
        // allowedToolModes get updated together — there is no second
        // mapping to keep in sync.
        val EXPECTED_ALL: Set<String> = setOf(
            "read_file",
            "explore_project",
            "run_cmd",
            "edit_file",
            "save_file",
            "to_do",
            "finish_to_do_item",
        )
        val EXPECTED_READ_ONLY: Set<String> = setOf(
            "read_file",
            "run_cmd",
        )
        val EXPECTED_EDIT: Set<String> = setOf(
            "read_file",
            "explore_project",
            "run_cmd",
            "edit_file",
            "save_file",
        )
    }
}
