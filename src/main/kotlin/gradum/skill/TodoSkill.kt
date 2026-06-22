/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TodoSkill.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess

/**
 * Maintains a shared in-memory list of pending tasks across the agent loop.
 *
 * A single instance is exposed via [getTodoManagerInstance] so the agent,
 * the TodoSkill, and the reminder prompts all see the same state.
 */
class TodoManager {

    private var taskList: List<String>? = null
    private var currentTaskIndex: Int = 0

    fun initializeTasks(tasks: List<String>): SkillResult {
        if (taskList != null) {
            return makeFailure(ErrorCode.ALREADY_INITIALIZED, "To-do list already initialized")
        }

        if (tasks.isEmpty()) {
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Task list cannot be empty")
        }

        taskList = tasks
        currentTaskIndex = 0

        return makeSuccess(
            mapOf(
                "totalTasks" to tasks.size,
                "currentTask" to tasks[0],
                "currentIndex" to 0,
            ),
        )
    }

    fun completeCurrentTask(): SkillResult {
        val tasks: List<String> = taskList ?: return makeFailure(ErrorCode.NOT_INITIALIZED, "To-do list not initialized")

        if (currentTaskIndex >= tasks.size) {
            return makeFailure(ErrorCode.ALL_COMPLETED, "All tasks already completed")
        }

        currentTaskIndex++
        val allDone: Boolean = currentTaskIndex >= tasks.size

        if (allDone) {
            return makeSuccess(
                mapOf(
                    "completed" to true,
                    "totalTasks" to tasks.size,
                    "message" to "All tasks completed",
                ),
            )
        }

        return makeSuccess(
            mapOf(
                "completed" to false,
                "totalTasks" to tasks.size,
                "currentTask" to tasks[currentTaskIndex],
                "currentIndex" to currentTaskIndex,
            ),
        )
    }

    fun getTaskReminder(): String? {
        val tasks: List<String> = taskList ?: return null
        if (currentTaskIndex >= tasks.size) return null

        val remaining: Int = tasks.size - currentTaskIndex
        return """
            Reminder: You still have $remaining task(s) remaining.
            Current task: ${tasks[currentTaskIndex]}.
            Complete them using finish_to_do_item, or ask the user for guidance.
        """.trimIndent()
    }
}

private val sharedTodoManager: TodoManager = TodoManager()

fun getTodoManagerInstance(): TodoManager {
    return sharedTodoManager
}

class TodoSkill : Skill() {

    override val skillName: String = "to_do"
    override val alias: String = "Planned"
    override val description: String = "Initialize a task list"

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "tasks" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "List of tasks to complete",
                        ),
                    ),
                    "required" to listOf("tasks"),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>): SkillResult {
        val rawTasks: List<String> = (arguments["tasks"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        if (rawTasks.isEmpty()) {
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Tasks list cannot be empty")
        }

        return sharedTodoManager.initializeTasks(rawTasks)
    }
}

/**
 * Marks the next task in [TodoManager] as completed and advances the cursor.
 *
 * The agent calls this between steps to keep its own plan in sync with the
 * reminder prompts injected by [TodoManager.getTaskReminder].
 */
class CompletePlanSkill : Skill() {

    override val skillName: String = "finish_to_do_item"
    override val alias: String = "Completed"
    override val description: String = "Mark a task as completed"

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "task" to mapOf("type" to "string", "description" to "Task that was completed"),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>): SkillResult {
        return sharedTodoManager.completeCurrentTask()
    }
}
