/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatMessage.kt  2026-06-28 23:07:38 Changed by gwy
 */

package gradum.idea.chat.model

import gradum.idea.bundle.GradumBundle.message
import gradum.idea.editor.AttachedContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Represents a single tool invocation within a message.
 *
 * @property toolName The name of the tool/skill being called.
 * @property alias The formatted display name (e.g., "Ran" for "run_cmd").
 * @property toolCallId Unique identifier for this tool call.
 * @property success Whether the tool call completed successfully.
 * @property result The result returned by the tool, if any.
 */
data class ToolCallInfo(
    val toolName: String,
    val alias: String = toolName,
    val toolCallId: String = "",
    val success: Boolean = true,
    val result: String = "",
    val arguments: Map<String, Any> = emptyMap(),
    val errorMessage: String = "",
    val errorDetail: String = ""
)

/**
 * Represents a single event in an assistant message's timeline.
 * Events are rendered in order to preserve the conversation flow.
 */
sealed class ChatEvent {
    data class Thinking(val content: String) : ChatEvent()
    data class ToolCall(val info: ToolCallInfo) : ChatEvent()
    data class Response(val content: String) : ChatEvent()
    data class Error(
        val message: String,
        val code: String = "",
        val tool: String = ""
    ) : ChatEvent()
}

/**
 * Pre-aggregated render block for stable Compose keys.
 *
 * Consecutive events of the same type are coalesced into a single block
 * so that Compose can reuse composables without rebuilding the entire list.
 */
sealed class RenderBlock {
    data class Thinking(val content: String) : RenderBlock()
    data class ToolCall(
        val alias: String,
        val success: Boolean,
        val arguments: Map<String, Any> = emptyMap(),
        val result: String = "",
        val errorMessage: String = "",
        val errorDetail: String = ""
    ) : RenderBlock()
    data class Response(val content: String) : RenderBlock()
    data class Error(val message: String) : RenderBlock()
}

/**
 * Represents a single message in the chat history.
 *
 * @property role  Identifies the sender — "user" or "assistant".
 * @property content  The message text (used for user messages).
 * @property attachments  Files frozen onto the message at send time.
 * @property events  Ordered list of events for assistant messages
 *   (thinking, tool calls, responses, errors).
 * @property renderBlocks  Pre-aggregated render blocks, maintained incrementally.
 * @property hasResponse  Whether this message contains at least one Response event.
 */
data class ChatMessage(
    val role: String,
    val content: String = "",
    val attachments: List<AttachedContext> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val events: List<ChatEvent> = emptyList(),
    val renderBlocks: List<RenderBlock> = emptyList(),
    val hasResponse: Boolean = false,
    val modelName: String = "",
    val provider: String = "",
    val serverName: String = ""
) {
    val isUserMessage: Boolean get() = role == "user"

    /** Aggregated thinking content from all Thinking events. */
    val thinking: String
        get() = events.filterIsInstance<ChatEvent.Thinking>().joinToString("") { it.content }

    /** Aggregated response content from all Response events. */
    val responseContent: String
        get() = events.filterIsInstance<ChatEvent.Response>().joinToString("") { it.content }

    /** Full content for clipboard: merged by type, no per-chunk fragmentation. */
    val fullContent: String
        get() = buildString {
            val thinkingText = thinking
            if (thinkingText.isNotBlank()) append(thinkingText)

            val toolCallAliases = events
                .filterIsInstance<ChatEvent.ToolCall>().map { it.info.alias }

            if (toolCallAliases.isNotEmpty())
                if (isNotEmpty()) append("\n\n"); append(toolCallAliases.joinToString("\n"))

            val responseText = responseContent
            if (responseText.isNotBlank())
                if (isNotEmpty()) append("\n\n"); append(responseText)


            val errorMessages = events.filterIsInstance<ChatEvent.Error>().map { it.message }

            if (errorMessages.isNotEmpty())
                if (isNotEmpty()) append("\n\n"); append(errorMessages.joinToString("\n"))

        }

    /**
     * Appends a [ChatEvent] and incrementally updates [renderBlocks] in O(1).
     *
     * Consecutive events of the same type are merged into the last block
     * (string concatenation only), avoiding full-list re-iteration on every token.
     */
    fun appendEvent(event: ChatEvent): ChatMessage {
        val newEvents = events + event
        val newRenderBlocks = when (event) {
            is ChatEvent.Response -> {
                val last = renderBlocks.lastOrNull()
                if (last is RenderBlock.Response) {
                    renderBlocks.dropLast(1) + RenderBlock.Response(last.content + event.content)
                } else {
                    renderBlocks + RenderBlock.Response(event.content)
                }
            }
            is ChatEvent.Thinking -> {
                val last = renderBlocks.lastOrNull()
                if (last is RenderBlock.Thinking) {
                    renderBlocks.dropLast(1) + RenderBlock.Thinking(last.content + event.content)
                } else {
                    renderBlocks + RenderBlock.Thinking(event.content)
                }
            }
            is ChatEvent.ToolCall -> {
                renderBlocks + RenderBlock.ToolCall(
                    alias = event.info.alias,
                    success = event.info.success,
                    arguments = event.info.arguments,
                    result = event.info.result,
                    errorMessage = event.info.errorMessage,
                    errorDetail = event.info.errorDetail
                )
            }
            is ChatEvent.Error -> renderBlocks + RenderBlock.Error(event.message)
        }
        return copy(
            events = newEvents,
            renderBlocks = newRenderBlocks,
            hasResponse = hasResponse || event is ChatEvent.Response
        )
    }

    /**
     * Finds the last failed ToolCall and updates its error info in both
     * [events] and [renderBlocks] in O(1) (no full list re-iteration).
     *
     * @return The updated message, or `this` if no failed ToolCall was found.
     */
    fun updateLastError(friendlyMessage: String, errorDetail: String): ChatMessage {
        val lastFailedIdx = events.indexOfLast {
            it is ChatEvent.ToolCall && !it.info.success
        }
        if (lastFailedIdx < 0) return this

        val oldInfo = (events[lastFailedIdx] as ChatEvent.ToolCall).info
        val updatedEvent = ChatEvent.ToolCall(
            oldInfo.copy(errorMessage = friendlyMessage, errorDetail = errorDetail)
        )
        val newEvents = events.toMutableList().apply { set(lastFailedIdx, updatedEvent) }

        val lastFailedBlockIdx = renderBlocks.indexOfLast { it is RenderBlock.ToolCall && !it.success }
        val newRenderBlocks = if (lastFailedBlockIdx >= 0) {
            val old = renderBlocks[lastFailedBlockIdx] as RenderBlock.ToolCall
            renderBlocks.toMutableList().apply {
                set(lastFailedBlockIdx, old.copy(errorMessage = friendlyMessage, errorDetail = errorDetail))
            }
        } else {
            renderBlocks
        }

        return copy(events = newEvents, renderBlocks = newRenderBlocks)
    }
}

/**
 * Formats a timestamp into a human-readable relative string.
 *
 * - Today: "14:30"
 * - Yesterday: "Yesterday 14:30"
 * - This year: "Jun 15"
 * - Older: "15 days ago"
 */
fun formatTimestamp(timestamp: Long): String {
    val now: Calendar = Calendar.getInstance()
    val messageTime: Calendar = Calendar.getInstance().apply { timeInMillis = timestamp }

    val diffMillis: Long = now.timeInMillis - timestamp
    val diffDays: Long = TimeUnit.MILLISECONDS.toDays(diffMillis)

    return when {
        isSameDay(now, messageTime) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        isYesterday(now, messageTime) -> {
            val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            "${message("gradum.timestamp.yesterday")} $time"
        }

        now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        else -> "$diffDays ${message("gradum.timestamp.days.ago")}"
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, target: Calendar): Boolean {
    val yesterday: Calendar = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return isSameDay(yesterday, target)
}
