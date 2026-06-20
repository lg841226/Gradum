package gradum.skill

import gradum.SkillResult

abstract class Skill {
    abstract val skillName: String
    abstract val description: String
    abstract val alias: String

    abstract fun execute(arguments: Map<String, Any>): SkillResult

    abstract fun getSchema(): Map<String, Any>
}
