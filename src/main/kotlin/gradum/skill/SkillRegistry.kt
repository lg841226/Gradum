package gradum.skill

import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = LoggerFactory.getLogger("SkillRegistry")

class SkillRegistry {

    private val registeredSkills: MutableMap<String, Skill> = mutableMapOf()

    init {
        discoverSkills()
    }

    fun getAllSkills(): Collection<Skill> {
        return registeredSkills.values
    }

    fun getSkill(skillName: String): Skill? {
        return registeredSkills[skillName]
    }

    fun getSchemas(): List<Map<String, Any>> {
        return registeredSkills.values.map { skill -> skill.getSchema() }
    }

    private fun registerSkill(skill: Skill): Unit {
        registeredSkills[skill.skillName] = skill
        logger.info("Registered skill: ${skill.skillName} (${skill.alias})")
    }

    private fun discoverSkills(): Unit {
        registerSkill(ReadFileSkill())
        registerSkill(EditFileSkill())
        registerSkill(SaveFileSkill())
        registerSkill(RunCommandSkill())
        registerSkill(SearchSkill())
        registerSkill(TodoSkill())
        registerSkill(CompletePlanSkill())
    }
}
