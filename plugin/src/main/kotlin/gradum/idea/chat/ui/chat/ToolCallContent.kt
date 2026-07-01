/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallContent.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import com.intellij.openapi.diagnostic.Logger
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
        private val log: Logger = Logger.getInstance(ToolCallContent::class.java)

        /**
         * Creates the appropriate [ToolCallContent] based on the tool alias and arguments.
         *
         * When arguments fail validation (missing or blank required fields), the
         * method falls back to [None] and writes a debug-level log line so the
         * LLM-emitted argument shape is visible in the IDE log without polluting
         * the UI. The previous implementation returned [None] silently, which
         * made "model sent the wrong field name" bugs hard to diagnose — the
         * user only saw an empty capsule and had no idea why.
         *
         * @param alias The tool alias (e.g., "Ran", "Edited").
         * @param arguments The arguments passed to the tool.
         * @param result The raw JSON result string from the server.
         * @return The corresponding [ToolCallContent] subclass, or [None] if the
         *   arguments did not match the expected shape for the given alias.
         */
        fun fromArguments(alias: String, arguments: Map<String, Any>, result: String = ""): ToolCallContent {
            return when (alias) {
                "Ran" -> {
                    val reason = arguments["reason"] as? String ?: ""
                    val command = arguments["command"] as? String ?: ""
                    if (reason.isBlank() && command.isBlank()) {
                        log.debug("Ran alias: both 'reason' and 'command' are missing/blank — falling back to None")
                        None
                    } else Ran(reason, command)
                }

                "Edited" -> {
                    val path = arguments["path"] as? String
                    if (path.isNullOrBlank()) {
                        log.debug("Edited alias: 'path' argument is missing/blank — falling back to None")
                        return None
                    }
                    val resultData = parseResult(result)
                    val linesAdded = (resultData["linesAdded"] as? Number)?.toInt() ?: 0
                    val linesRemoved = (resultData["linesRemoved"] as? Number)?.toInt() ?: 0
                    Edited(path, linesAdded, linesRemoved)
                }

                "Read" -> {
                    val path = arguments["path"] as? String
                    if (path.isNullOrBlank()) {
                        log.debug("Read alias: 'path' argument is missing/blank — falling back to None")
                        return None
                    }
                    Read(path)
                }

                "Explored" -> {
                    val projectRoot = arguments["project_root"] as? String
                    val depth: Int = when (val depthValue: Any? = arguments["depth"]) {
                        is Number -> depthValue.toInt()
                        is String -> depthValue.toIntOrNull() ?: 0
                        else -> 0
                    }
                    if (projectRoot.isNullOrBlank() || depth <= 0) {
                        log.debug("Explored alias: 'project_root'=$projectRoot, 'depth'=$depth — falling back to None")
                        return None
                    }
                    Explored(projectRoot, depth)
                }

                "Planned" -> {
                    @Suppress("UNCHECKED_CAST")
                    val tasks = arguments["tasks"] as? List<String>
                    if (tasks.isNullOrEmpty()) {
                        log.debug("Planned alias: 'tasks' is missing/empty — falling back to None")
                        return None
                    }
                    Planned(tasks)
                }

                "Completed" -> {
                    val task = arguments["task"] as? String
                    if (task.isNullOrBlank()) {
                        log.debug("Completed alias: 'task' argument is missing/blank — falling back to None")
                        return None
                    }
                    Completed(task)
                }

                else -> {
                    log.debug("Unknown tool alias '$alias' — no ToolCallContent mapping, falling back to None")
                    None
                }
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
                log.debug("Failed to parse tool result JSON, returning empty map", exception)
                emptyMap()
            }
        }
    }
}
