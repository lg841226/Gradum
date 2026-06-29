/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillRegistry.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.skill

import gradum.ToolMode
import java.util.ServiceLoader

import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("SkillRegistry")

/**
 * Central registry for all available skills.
 *
 * Uses Java ServiceLoader to discover Skill implementations at runtime.
 * Built-in skills are listed in META-INF/services/gradum.skill.Skill.
 * External plugins can be added by placing JARs on the classpath with
 * the same service descriptor file.
 *
 * @see Skill
 * @see ServiceLoader
 */
object SkillRegistry {

    private val registeredSkills: MutableMap<String, Skill> = mutableMapOf()

    init { discoverSkills() }

    fun getAllSkills(): Collection<Skill> {
        return registeredSkills.values
    }

    /**
     * Retrieves a skill by its name.
     *
     * @param skillName the unique identifier of the skill
     * @return the Skill instance, or null if not found
     */
    fun getSkill(skillName: String): Skill? {
        return registeredSkills[skillName]
    }

    /**
     * Returns function schemas for all registered skills, optionally filtered
     * by a [ToolMode]. Used to build the tools definition sent to the LLM.
     *
     * @param toolMode When [ToolMode.WRITE] (default) every registered skill
     *   is returned. When [ToolMode.READ_ONLY] only inspection skills
     *   (`read_file`, `explore_project`, `run_cmd`) are returned so the LLM
     *   cannot mutate the project. When [ToolMode.SINGLE_STEP] every skill
     *   is returned except the task-tracking pair (`to_do` /
     *   `finish_to_do_item`) — for small local models that cannot plan
     *   reliably.
     * @return list of schema maps from each registered skill
     */
    fun getSchemas(toolMode: ToolMode = ToolMode.WRITE): List<Map<String, Any>> {
        val allSkills: Collection<Skill> = registeredSkills.values
        val filtered: Collection<Skill> = when (toolMode) {
            ToolMode.WRITE -> allSkills
            ToolMode.READ_ONLY -> allSkills.filter { skill: Skill -> skill.skillName in READ_ONLY_ALLOWED_SKILLS }
            ToolMode.SINGLE_STEP -> allSkills.filter { skill: Skill -> skill.skillName !in SINGLE_STEP_EXCLUDED_SKILLS }
        }
        return filtered.map { skill: Skill -> skill.getSchema() }
    }

    private val READ_ONLY_ALLOWED_SKILLS: Set<String> = setOf("read_file", "explore_project", "run_cmd")
    private val SINGLE_STEP_EXCLUDED_SKILLS: Set<String> = setOf("to_do", "finish_to_do_item")

    /**
     * Registers a single skill in the registry.
     *
     * @param skill the skill instance to register
     */
    private fun registerSkill(skill: Skill): Unit {
        registeredSkills[skill.skillName] = skill
        logger.info("Registered skill: ${skill.skillName} (${skill.alias})")
    }

    /**
     * Discovers and registers all available skills using ServiceLoader.
     *
     * Loads Skill implementations from:
     * 1. Built-in skills listed in META-INF/services/gradum.skill.Skill
     * 2. External plugin JARs on the classpath with the same service descriptor
     *
     * Each discovered skill must have a no-argument constructor.
     */
    private fun discoverSkills() {
        val skillLoader: ServiceLoader<Skill> = ServiceLoader.load(Skill::class.java)

        for (skill: Skill in skillLoader)
            registerSkill(skill)

        if (registeredSkills.isEmpty())
            logger.warn("No skills discovered via ServiceLoader")
        else
            logger.info("Discovered ${registeredSkills.size} skills via ServiceLoader")
    }
}
