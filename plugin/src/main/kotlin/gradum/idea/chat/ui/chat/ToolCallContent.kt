/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallContent.kt  2026-06-28 23:07:38 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import kotlinx.serialization.json.*

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
    data class Edited(val path: String, val linesAdded: Int = 0, val linesRemoved: Int = 0) : ToolCallContent()

    /** Content for the "Read" (read_file) tool. */
    data class Read(val path: String) : ToolCallContent()

    /** Content for the "Explored" (explore_project) tool. */
    data class Explored(val projectRoot: String, val depth: Int) : ToolCallContent()

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
        fun fromArguments(alias: String, arguments: Map<String, Any>, result: String = ""): ToolCallContent {
            return when (alias) {
                "Ran" -> {
                    val reason = arguments["reason"] as? String ?: ""
                    val command = arguments["command"] as? String ?: ""
                    if (reason.isNotBlank() || command.isNotBlank()) Ran(reason, command) else None
                }

                "Edited" -> {
                    val path = arguments["path"] as? String
                    val resultData = parseResult(result)
                    val linesAdded = (resultData["linesAdded"] as? Number)?.toInt() ?: 0
                    val linesRemoved = (resultData["linesRemoved"] as? Number)?.toInt() ?: 0
                    if (!path.isNullOrBlank()) Edited(path, linesAdded, linesRemoved) else None
                }

                "Read" -> {
                    val path = arguments["path"] as? String
                    if (!path.isNullOrBlank()) Read(path) else None
                }

                "Explored" -> {
                    val projectRoot = arguments["project_root"] as? String
                    val depth: Int = when (val depthValue: Any? = arguments["depth"]) {
                        is Number -> depthValue.toInt()
                        is String -> depthValue.toIntOrNull() ?: 0
                        else -> 0
                    }
                    if (!projectRoot.isNullOrBlank() && depth > 0) Explored(projectRoot, depth) else None
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

        private fun parseResult(result: String): Map<String, Any> {
            if (result.isBlank()) return emptyMap()

            return try {
                val json = Json.parseToJsonElement(result).jsonObject

                json.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> {
                            when {
                                value.isString -> value.content
                                value.booleanOrNull != null -> value.boolean
                                value.intOrNull != null -> value.int
                                value.longOrNull != null -> value.long
                                value.doubleOrNull != null -> value.double
                                else -> value.content
                            }
                        }

                        else -> value.toString()
                    }
                }
            } catch (exception: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(ToolCallContent::class.java)
                    .debug("Failed to parse tool result JSON", exception)
                emptyMap()
            }
        }
    }
}
