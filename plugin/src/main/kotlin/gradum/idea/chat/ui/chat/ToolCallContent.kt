/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallContent.kt  2026-06-28 11:20:00 Changed by gwy
 */

package gradum.idea.chat.ui.chat

/**
 * Sealed class representing tool-specific content to display in the tool call indicator.
 * Each tool type has its own subclass with relevant fields.
 */
sealed class ToolCallContent {
    /** No additional content to display. */
    data object None : ToolCallContent()

    /** Content for the "Ran" (run_cmd) tool. */
    data class Ran(val reason: String, val command: String) : ToolCallContent()

    /** Content for the "Edited" (edit_file) tool. */
    data class Edited(val path: String) : ToolCallContent()

    /** Content for the "Read" (read_file) tool. */
    data class Read(val path: String) : ToolCallContent()

    /** Content for the "Explored" (search) tool. */
    data class Explored(val keyword: String) : ToolCallContent()

    /** Content for the "Planned" (todo_add) tool. */
    data class Planned(val tasks: List<String>) : ToolCallContent()

    /** Content for the "Completed" (todo_complete) tool. */
    data class Completed(val task: String) : ToolCallContent()

    companion object {
        /**
         * Creates the appropriate [ToolCallContent] based on the tool alias and arguments.
         *
         * @param alias The tool alias (e.g., "Ran", "Edited").
         * @param arguments The arguments passed to the tool.
         * @return The corresponding [ToolCallContent] subclass.
         */
        fun fromArguments(alias: String, arguments: Map<String, Any>): ToolCallContent {
            return when (alias) {
                "Ran" -> {
                    val reason = arguments["reason"] as? String ?: ""
                    val command = arguments["command"] as? String ?: ""
                    if (reason.isNotBlank() || command.isNotBlank()) Ran(reason, command) else None
                }
                "Edited" -> {
                    val path = arguments["path"] as? String
                    if (!path.isNullOrBlank()) Edited(path) else None
                }
                "Read" -> {
                    val path = arguments["path"] as? String
                    if (!path.isNullOrBlank()) Read(path) else None
                }
                "Explored" -> {
                    val keyword = arguments["keyword"] as? String
                        ?: arguments["query"] as? String
                    if (!keyword.isNullOrBlank()) Explored(keyword) else None
                }
                "Planned" -> {
                    @Suppress("UNCHECKED_CAST")
                    val tasks = arguments["tasks"] as? List<String>
                    if (!tasks.isNullOrEmpty()) Planned(tasks) else None
                }
                "Completed" -> {
                    val task = arguments["task"] as? String
                    if (!task.isNullOrBlank()) Completed(task) else None
                }
                else -> None
            }
        }
    }
}
