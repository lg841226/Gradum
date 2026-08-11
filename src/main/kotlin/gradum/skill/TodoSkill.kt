/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TodoSkill.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess

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
   * Task planning is a strong-model skill; EDIT deliberately
   * hides to_do / finish_to_do_item. The agent's mode gate enforces
   * the exclusion at runtime — even if the model hallucinates a to_do
   * call, the agent returns TOOL_NOT_PERMITTED before TodoSkill runs.
   */
  override val allowedToolModes: Set<gradum.ToolMode> = setOf(gradum.ToolMode.AGENT)

  override fun getSchema(context: SkillContext?): Map<String, Any> {
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

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val rawTasks: List<String> = (arguments["tasks"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    if (rawTasks.isEmpty())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Tasks list cannot be empty.",
          fixHint = "Provide at least one task description in the 'tasks' parameter."
        )
      )

    return sharedTodoManager.initializeTasks(rawTasks)
  }
}

/**
 * Advances the agent through its task list by completing or skipping tasks.
 *
 * The agent calls this between steps to keep its plan in sync with
 * reminder prompts injected by [TodoManager.getTaskReminder].
 */
class CompletePlanSkill : Skill() {
  override val skillName: String = "finish_to_do_item"
  override val alias: String = "Completed"
  override val description: String = "Manage tasks: mark as completed or skip without completing tasks"

  /**
   * finish_to_do_item is the completion-tracking sibling of [TodoSkill]
   * — both are withheld in EDIT (no task planning) and in
   * READ_ONLY (no project mutation at all). The mode gate rejects
   * every other mode with TOOL_NOT_PERMITTED.
   */
  override val allowedToolModes: Set<gradum.ToolMode> = setOf(gradum.ToolMode.AGENT)

  override fun getSchema(context: SkillContext?): Map<String, Any> {
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

  fun initializeTasks(taskDescriptions: List<String>): SkillResult {
    if (taskList != null)
      return makeFailure(
        ErrorCode.ALREADY_INITIALIZED, buildXmlError(
          code = "ALREADY_INITIALIZED",
          message = "To-do list already initialized.",
          fixHint = "Complete or reset the current task list before initializing a new one."
        )
      )
    if (taskDescriptions.isEmpty())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Task list cannot be empty.",
          fixHint = "Provide at least one task description in the 'tasks' parameter."
        )
      )

    taskList = taskDescriptions; currentTaskIndex = 0

    return makeSuccess(
      mapOf("totalTasks" to taskDescriptions.size, "currentTask" to taskDescriptions[0], "currentIndex" to 0)
    )
  }

  fun completeCurrentTask(): SkillResult {
    val taskItems: List<String> =
      taskList ?: return makeFailure(
        ErrorCode.NOT_INITIALIZED, buildXmlError(
          code = "NOT_INITIALIZED",
          message = "To-do list not initialized.",
          fixHint = "Call to_do first to initialize the task list."
        )
      )

    if (currentTaskIndex >= taskItems.size)
      return makeFailure(
        ErrorCode.ALL_COMPLETED, buildXmlError(
          code = "ALL_COMPLETED",
          message = "All tasks already completed.",
          fixHint = "No more tasks to complete. Call to_do to start a new task list."
        )
      )

    currentTaskIndex++
    val allDone: Boolean = currentTaskIndex >= taskItems.size

    if (allDone) {
      return makeSuccess(
        mapOf(
          "completed" to true,
          "totalTasks" to taskItems.size,
          "message" to "All tasks completed"
        ),
      )
    }

    return makeSuccess(
      mapOf(
        "completed" to false,
        "totalTasks" to taskItems.size,
        "currentTask" to taskItems[currentTaskIndex],
        "currentIndex" to currentTaskIndex
      ),
    )
  }

  /**
   * Skips the current task without marking it as completed and advances the cursor.
   */
  fun skipTask(): SkillResult {
    val taskItems: List<String> =
      taskList ?: return makeFailure(
        ErrorCode.NOT_INITIALIZED, buildXmlError(
          code = "NOT_INITIALIZED",
          message = "To-do list not initialized.",
          fixHint = "Call to_do first to initialize the task list."
        )
      )

    if (currentTaskIndex >= taskItems.size)
      return makeFailure(
        ErrorCode.NOT_INITIALIZED, buildXmlError(
          code = "NOT_INITIALIZED",
          message = "All tasks already completed.",
          fixHint = "No more tasks to skip. Call to_do to start a new task list."
        )
      )

    val skippedTask: String = taskItems[currentTaskIndex]
    currentTaskIndex++

    val allDone: Boolean = currentTaskIndex >= taskItems.size

    return makeSuccess(
      mapOf(
        "skipped" to true,
        "skippedTask" to skippedTask,
        "completed" to allDone,
        "totalTasks" to taskItems.size,
        "currentTask" to if (allDone) "" else taskItems[currentTaskIndex],
        "currentIndex" to currentTaskIndex
      )
    )
  }

  fun getTaskReminder(): String? {
    val taskItems: List<String> = taskList ?: return null
    if (currentTaskIndex >= taskItems.size) return null

    val remainingCount: Int = taskItems.size - currentTaskIndex
    return """
            You still have $remainingCount task(s) remaining.
            Current task: ${taskItems[currentTaskIndex]}.
            Complete them using finish_to_do_item, or ask the user for guidance.
        """.trimIndent()
  }
}

private val sharedTodoManager: TodoManager = TodoManager()

fun getTodoManagerInstance(): TodoManager {
  return sharedTodoManager
}
