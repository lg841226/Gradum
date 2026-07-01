/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillRegistry.kt  2026-06-30 23:35:47 Changed by gwy
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

    init {
        discoverSkills()
    }

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
     * Returns function schemas for every skill that allows the given
     * [toolMode]. Used to build the tools definition sent to the LLM.
     *
     * The filter is `toolMode in skill.allowedToolModes` — the same
     * membership check [gradum.agent.Agent] uses at runtime to gate
     * tool calls. The two checks must agree: if a tool's schema is in
     * the LLM's tool list, the LLM can call it, so the runtime gate
     * must not reject it. If a tool is not in the LLM's tool list, the
     * runtime gate might still be hit (LLM-hallucinated calls), so the
     * runtime gate must not let it through.
     *
     * The previous implementation maintained a separate hardcoded set
     * per mode (`READ_ONLY_ALLOWED_SKILLS`, `SINGLE_STEP_EXCLUDED_SKILLS`).
     * It was a second source of truth for the same invariant — if a
     * skill's [Skill.allowedToolModes] drift away from those sets, the
     * LLM would see tools it cannot actually call, or vice versa. The
     * single-source-of-truth version is this filter plus the runtime
     * gate, both reading from the same property.
     *
     * @param toolMode Which mode the LLM is being started in. Defaults
     *   to [ToolMode.WRITE] (unrestricted).
     * @return list of schema maps from every registered skill that
     *   declares [toolMode] in its [Skill.allowedToolModes]
     */
    fun getSchemas(toolMode: ToolMode = ToolMode.WRITE): List<Map<String, Any>> {
        return registeredSkills.values
            .filter { skill: Skill -> toolMode in skill.allowedToolModes }
            .map { skill: Skill -> skill.getSchema() }
    }

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
