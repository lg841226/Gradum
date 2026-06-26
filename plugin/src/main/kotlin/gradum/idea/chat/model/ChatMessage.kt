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
 * Represents a single message in the chat history.
 *
 * @property role  Identifies the sender — "user" or "assistant".
 * @property content  The message text.
 * @property attachments  Files frozen onto the message at send time and
 *   rendered under the user bubble by MessageAttachmentList. Empty for
 *   assistant messages and for user messages sent without attachments.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val attachments: List<AttachedContext> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val isUserMessage: Boolean get() = role == "user"
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
