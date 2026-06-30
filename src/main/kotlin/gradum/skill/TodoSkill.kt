/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TodoSkill.kt  2026-06-30 22:06:58 Changed by gwy
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

    fun resetTaskList() {
        taskList = null; currentTaskIndex = 0
    }

    fun initializeTasks(tasks: List<String>): SkillResult {
        if (taskList != null)
            return makeFailure(ErrorCode.ALREADY_INITIALIZED, "To-do list already initialized")

        if (tasks.isEmpty())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Task list cannot be empty")

        taskList = tasks
        currentTaskIndex = 0

        return makeSuccess(
            mapOf("totalTasks" to tasks.size, "currentTask" to tasks[0], "currentIndex" to 0)
        )
    }

    fun completeCurrentTask(): SkillResult {
        val tasks: List<String> =
            taskList ?: return makeFailure(ErrorCode.NOT_INITIALIZED, "To-do list not initialized")

        if (currentTaskIndex >= tasks.size)
            return makeFailure(ErrorCode.ALL_COMPLETED, "All tasks already completed")

        currentTaskIndex++
        val allDone: Boolean = currentTaskIndex >= tasks.size

        if (allDone) {
            return makeSuccess(
                mapOf(
                    "completed" to true,
                    "totalTasks" to tasks.size,
                    "message" to "All tasks completed"
                ),
            )
        }

        return makeSuccess(
            mapOf(
                "completed" to false,
                "totalTasks" to tasks.size,
                "currentTask" to tasks[currentTaskIndex],
                "currentIndex" to currentTaskIndex
            ),
        )
    }

    /**
     * Skips the current task without marking it as completed and advances the cursor.
     *
     * @return [SkillResult.Success] with skipped task info and next task;
     *         or [SkillResult.Failure] if not initialized or all tasks completed.
     */
    fun skipTask(): SkillResult {
        val tasks: List<String> =
            taskList ?: return makeFailure(ErrorCode.NOT_INITIALIZED, "To-do list not initialized")

        if (currentTaskIndex >= tasks.size)
            return makeFailure(ErrorCode.NOT_INITIALIZED, "All tasks already completed")

        val skippedTask: String = tasks[currentTaskIndex]
        currentTaskIndex++
        val allDone: Boolean = currentTaskIndex >= tasks.size

        return makeSuccess(
            mapOf(
                "skipped" to true,
                "skippedTask" to skippedTask,
                "completed" to allDone,
                "totalTasks" to tasks.size,
                "currentTask" to if (allDone) "" else tasks[currentTaskIndex],
                "currentIndex" to currentTaskIndex
            )
        )
    }

    fun getTaskReminder(): String? {
        val tasks: List<String> = taskList ?: return null
        if (currentTaskIndex >= tasks.size) return null

        val remaining: Int = tasks.size - currentTaskIndex
        return """
            You still have $remaining task(s) remaining.
            Current task: ${tasks[currentTaskIndex]}.
            Complete them using finish_to_do_item, or ask the user for guidance.
        """.trimIndent()
    }
}

private val sharedTodoManager: TodoManager = TodoManager()

fun getTodoManagerInstance(): TodoManager {
    return sharedTodoManager
}


/**
 * Initializes a shared task list for the agent to follow during a session.
 *
 * Once initialized, the agent progresses through tasks using [CompletePlanSkill].
 * Only one task list can be active per session; re-initialization fails with
 * [ErrorCode.ALREADY_INITIALIZED].
 */
class TodoSkill : Skill() {
    override val skillName: String = "to_do"
    override val alias: String = "Planned"
    override val description: String = "Initialize a task list"

    /**
     * Task planning is a strong-model skill; SINGLE_STEP deliberately
     * hides to_do / finish_to_do_item. The agent's mode gate enforces
     * the exclusion at runtime — even if the model hallucinates a to_do
     * call, the agent returns TOOL_NOT_PERMITTED before TodoSkill runs.
     */
    override val allowedToolModes: Set<gradum.ToolMode> = setOf(
        gradum.ToolMode.WRITE,
    )

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

    /**
     * Parses the task list from arguments and initializes [TodoManager].
     *
     * @param arguments Map containing `tasks` — a list of task descriptions.
     * @param context per-session state (unused here, but required by [Skill.execute]).
     * @return [SkillResult.Success] with totalTasks, currentTask, currentIndex;
     *         or [SkillResult.Failure] if tasks is empty or already initialized.
     */
    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val rawTasks: List<String> = (arguments["tasks"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        if (rawTasks.isEmpty())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Tasks list cannot be empty")

        return sharedTodoManager.initializeTasks(rawTasks)
    }
}

/**
 * Advances the agent through its task list by completing or skipping tasks.
 *
 * The agent calls this between steps to keep its plan in sync with
 * reminder prompts injected by [TodoManager.getTaskReminder].
 * Supports two actions:
 * - `complete` (default): marks current task done, advances cursor.
 * - `skip`: advances cursor without marking the task as completed.
 */
class CompletePlanSkill : Skill() {
    override val skillName: String = "finish_to_do_item"
    override val alias: String = "Completed"
    override val description: String = "Manage tasks: mark as completed or skip without completing tasks"

    /**
     * finish_to_do_item is the completion-tracking sibling of [TodoSkill]
     * — both are withheld in SINGLE_STEP (no task planning) and in
     * READ_ONLY (no project mutation at all). The mode gate rejects
     * every other mode with TOOL_NOT_PERMITTED.
     */
    override val allowedToolModes: Set<gradum.ToolMode> = setOf(
        gradum.ToolMode.WRITE,
    )

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
                        "action" to mapOf(
                            "type" to "string",
                            "description" to "'complete' (default) marks task done; 'skip' advances without completing",
                            "enum" to listOf("complete", "skip")
                        ),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        )
    }

    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val action: String = arguments["action"] as? String ?: "complete"

        return when (action) {
            "skip" -> sharedTodoManager.skipTask()
            else -> sharedTodoManager.completeCurrentTask()
        }
    }
}
