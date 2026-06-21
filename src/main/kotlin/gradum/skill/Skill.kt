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

    /**
     * How many recent results keep their [historyVolatileKeys] in conversation history.
     * Older results beyond this count will have those keys stripped to save context.
     * Default [Int.MAX_VALUE] keeps all results intact (no stripping).
     */
    open val historyKeepCount: Int = Int.MAX_VALUE

    /**
     * Keys to strip from history result when exceeding [historyKeepCount].
     * Only relevant when [historyKeepCount] is not [Int.MAX_VALUE].
     */
    open val historyVolatileKeys: List<String> = emptyList()

    private var prepareHistoryCallCount: Int = 0

    open fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
        prepareHistoryCallCount++
        if (historyKeepCount == Int.MAX_VALUE || historyVolatileKeys.isEmpty()) {
            return result
        }
        return if (prepareHistoryCallCount <= historyKeepCount) {
            result
        } else {
            result.filterKeys { it !in historyVolatileKeys }
        }
    }
}
