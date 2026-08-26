/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * TodoSkill.kt  2026-08-26 11:00:57 Changed by gwy
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
   * Available in AGENT mode only. Task planning drives the agent's
   * step-by-step execution; EDIT and READ_ONLY exclude it because the
   * to_do / finish_to_do_item pair implies an active agent workflow.
   */
  override val allowedToolModes: Set<gradum.ToolMode> = setOf(
    gradum.ToolMode.AGENT
  )

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    stringArray(
      name = "tasks",
      description = "List of tasks to complete",
      required = true,
    )
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val rawTasks: List<String> = (arguments["tasks"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    if (rawTasks.isEmpty())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Tasks list cannot be empty.",
          fixHint = "Provide at least one task description in the 'tasks' parameter."
        )
      )

    return sharedTodoManager.initializeTasks(taskDescriptions = rawTasks)
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
   * Available in AGENT mode only, matching [TodoSkill]. EDIT and
   * READ_ONLY exclude it since finishing tasks implies the active
   * agent workflow that owns the task list.
   */
  override val allowedToolModes: Set<gradum.ToolMode> = setOf(
    gradum.ToolMode.AGENT
  )

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "task",
      description = "Task that was completed"
    )
    integer(
      name = "count",
      description = "Number of tasks to complete/skip at once (default 1)",
    )
    string(
      name = "action",
      description = "'complete' (default) marks tasks done; 'skip' advances without completing",
      enumValues = listOf("complete", "skip"),
    )
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val action: String = arguments["action"] as? String ?: "complete"
    val count: Int = (arguments["count"] as? Number)?.toInt() ?: 1

    return when (action) {
      "skip" -> sharedTodoManager.skipTask(count)
      else -> sharedTodoManager.completeCurrentTask(count)
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
    taskList = null
    currentTaskIndex = 0
  }

  fun initializeTasks(taskDescriptions: List<String>): SkillResult {
    if (taskDescriptions.isEmpty())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Task list cannot be empty.",
          fixHint = "Provide at least one task description in the 'tasks' parameter."
        )
      )

    taskList = taskDescriptions; currentTaskIndex = 0

    return makeSuccess {
      integer("currentIndex", 0)
      stringList("tasks", taskDescriptions)
      integer("totalTasks", taskDescriptions.size)
      string("currentTask", taskDescriptions[0])
    }
  }

  fun completeCurrentTask(count: Int = 1): SkillResult {
    val taskItems: List<String> =
      taskList ?: return makeFailure(
        code = ErrorCode.NOT_INITIALIZED,
        message = buildXmlError(
          code = "NOT_INITIALIZED",
          message = "To-do list not initialized.",
          fixHint = "Call to_do first to initialize the task list."
        )
      )

    currentTaskIndex += count
    val allDone: Boolean = currentTaskIndex >= taskItems.size

    if (allDone) {
      return makeSuccess {
        boolean("completed", true)
        integer("totalTasks", taskItems.size)
        stringList("tasks", taskItems)
        string("message", "All tasks completed")
      }
    }

    return makeSuccess {
      boolean("completed", false)
      integer("totalTasks", taskItems.size)
      stringList("tasks", taskItems)
      string("currentTask", taskItems[currentTaskIndex])
      integer("currentIndex", currentTaskIndex)
    }
  }

  /**
   * Skips the current task without marking it as completed and advances the cursor.
   */
  fun skipTask(count: Int = 1): SkillResult {
    val taskItems: List<String> =
      taskList ?: return makeFailure(
        code = ErrorCode.NOT_INITIALIZED,
        message = buildXmlError(
          code = "NOT_INITIALIZED",
          message = "To-do list not initialized.",
          fixHint = "Call to_do first to initialize the task list."
        )
      )

    val skippedTask: String =
      if (currentTaskIndex < taskItems.size) taskItems[currentTaskIndex]
      else ""
    currentTaskIndex += count

    val allDone: Boolean = currentTaskIndex >= taskItems.size

    return makeSuccess {
      boolean("skipped", true)
      stringList("tasks", taskItems)
      boolean("completed", allDone)
      string("skippedTask", skippedTask)
      integer("totalTasks", taskItems.size)
      integer("currentIndex", currentTaskIndex)
      string("currentTask", if (allDone) "" else taskItems[currentTaskIndex])
    }
  }

  fun getTaskReminder(): String? {
    val taskItems: List<String> = taskList ?: return null
    val remainingCount: Int = taskItems.size - currentTaskIndex

    if (currentTaskIndex >= taskItems.size) return null

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
