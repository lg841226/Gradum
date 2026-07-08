/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallContent.kt  2026-07-05 16:53:27 Changed by gwy
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

    /**
     * Content for the "Edited" (edit_file) tool.
     *
     * [originalContent] and [modifiedContent] are the file snapshots
     * captured by the server's `EditFileSkill` immediately before and
     * after the edit. They are sent on the wire in the `tool_call`
     * event so the chat UI can offer a "View Diff" action via the
     * platform's native diff viewer, but they are stripped from the
     * LLM conversation history by `EditFileSkill.prepareHistoryResult`
     * server-side. Both are nullable because older server builds do
     * not include the field — the UI hides the "View Diff" button
     * when either is missing.
     */
    data class Edited(
        val filePath: String,
        val linesAdded: Int = 0,
        val linesRemoved: Int = 0,
        val originalContent: String? = null,
        val modifiedContent: String? = null,
    ) : ToolCallContent()

    /** Content for the "Read" (read_file) tool. */
    data class Read(val filePath: String) : ToolCallContent()

    /**
     * Content for the "Saved" (save_file) tool.
     *
     * The wire payload from [gradum.skill.SaveFileSkill] always
     * includes a `bytesWritten` field (the file's length on disk
     * after to write completes, see `SaveFileSkill.execute`), so
     * we surface the human-readable size on the tool call capsule
     * next to the file name. Older server builds that don't emit
     * `bytesWritten` (or any non-numeric value) fall through with
     * [sizeBytes] = 0 and the UI just shows the file name.
     */
    data class Saved(
        val filePath: String,
        val sizeBytes: Long = 0L,
    ) : ToolCallContent()

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
                    val revokeReason = arguments["reason"] as? String ?: ""
                    val commandText = arguments["command"] as? String ?: ""
                    if (revokeReason.isBlank() && commandText.isBlank()) {
                        log.debug("Ran alias: both 'reason' and 'command' are missing/blank — falling back to None")
                        None
                    } else Ran(revokeReason, commandText)
                }

                "Edited" -> {
                    val filePath = arguments["path"] as? String
                    if (filePath.isNullOrBlank()) {
                        log.debug("Edited alias: 'path' argument is missing/blank — falling back to None")
                        return None
                    }
                    val resultData = parseResult(result)
                    val linesAdded = (resultData["linesAdded"] as? Number)?.toInt() ?: 0
                    val linesRemoved = (resultData["linesRemoved"] as? Number)?.toInt() ?: 0
                    val originalContent: String? = resultData["originalContent"] as? String
                    val modifiedContent: String? = resultData["modifiedContent"] as? String
                    Edited(filePath, linesAdded, linesRemoved, originalContent, modifiedContent)
                }

                "Read" -> {
                    val filePath = arguments["path"] as? String
                    if (filePath.isNullOrBlank()) {
                        log.debug("Read alias: 'path' argument is missing/blank — falling back to None")
                        None
                    } else Read(filePath)
                }

                "Saved" -> {
                    val filePath = arguments["path"] as? String
                    if (filePath.isNullOrBlank()) {
                        log.debug("Saved alias: 'path' argument is missing/blank — falling back to None")
                        return None
                    }
                    val resultData = parseResult(result)
                    val sizeBytes: Long = (resultData["bytesWritten"] as? Number)?.toLong() ?: 0L
                    Saved(filePath, sizeBytes)
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
                    val taskList = arguments["tasks"] as? List<String>
                    if (taskList.isNullOrEmpty()) {
                        log.debug("Planned alias: 'tasks' is missing/empty — falling back to None")
                        return None
                    }
                    Planned(taskList)
                }

                "Completed" -> {
                    val taskText = arguments["task"] as? String
                    if (taskText.isNullOrBlank()) {
                        log.debug("Completed alias: 'task' argument is missing/blank — falling back to None")
                        return None
                    }
                    Completed(taskText)
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
                val jsonElement = Json.parseToJsonElement(result).jsonObject

                jsonElement.mapValues { (_, value) ->
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
