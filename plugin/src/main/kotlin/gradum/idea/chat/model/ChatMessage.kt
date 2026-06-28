/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatMessage.kt  2026-06-26 16:21:36 Changed by gwy
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
    val arguments: Map<String, Any> = emptyMap()
)

/**
 * Represents a single event in an assistant message's timeline.
 * Events are rendered in order to preserve the conversation flow.
 */
sealed class ChatEvent {
    data class Thinking(val content: String) : ChatEvent()
    data class ToolCall(val info: ToolCallInfo) : ChatEvent()
    data class Response(val content: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
}

/**
 * Represents a single message in the chat history.
 *
 * @property role  Identifies the sender — "user" or "assistant".
 * @property content  The message text (used for user messages).
 * @property attachments  Files frozen onto the message at send time.
 * @property events  Ordered list of events for assistant messages
 *   (thinking, tool calls, responses, errors).
 */
data class ChatMessage(
    val role: String,
    val content: String = "",
    val attachments: List<AttachedContext> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val events: List<ChatEvent> = emptyList()
) {
    val isUserMessage: Boolean get() = role == "user"

    /** Aggregated thinking content from all Thinking events. */
    val thinking: String
        get() = events.filterIsInstance<ChatEvent.Thinking>().joinToString("") { it.content }

    /** Aggregated response content from all Response events. */
    val responseContent: String
        get() = events.filterIsInstance<ChatEvent.Response>().joinToString("") { it.content }

    /** All tool call events. */
    val toolCalls: List<ToolCallInfo>
        get() = events.filterIsInstance<ChatEvent.ToolCall>().map { it.info }
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
