/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Skill.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult

abstract class Skill {
    abstract val skillName: String
    abstract val description: String
    abstract val alias: String

    abstract fun execute(arguments: Map<String, Any>): SkillResult

    abstract fun getSchema(): Map<String, Any>
}
