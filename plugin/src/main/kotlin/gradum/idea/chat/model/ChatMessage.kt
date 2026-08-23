/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatMessage.kt  2026-08-07 16:04:17 Changed by gwy
 */

package gradum.idea.chat.model

import gradum.idea.editor.AttachedContext
import gradum.idea.utils.GradumBundle.message
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/** Token usage snapshot for a completed assistant turn. */
data class TokenUsage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  val totalTokens: Int = 0,
)

/** A single tool invocation within a message. */
data class ToolCallInfo(
  val toolName: String,
  val alias: String = toolName,
  val toolCallId: String = "",
  val success: Boolean = true,
  val result: String = "",
  val arguments: Map<String, Any> = emptyMap(),
  val errorMessage: String = "",
  val errorDetail: String = "",
  val pending: Boolean = false,
  val timeoutSeconds: Int = 0
)

/** A single event in an assistant message's timeline, rendered in order. */
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
    val errorDetail: String = "",
    val toolCallId: String = "",
    val pending: Boolean = false,
    val timeoutSeconds: Int = 0
  ) : RenderBlock()

  data class Response(val content: String) : RenderBlock()
  data class Error(val message: String, val code: String = "") : RenderBlock()
}

/**
 * A single message in the chat history. Assistant messages carry an
 * ordered [events] list plus pre-aggregated [renderBlocks] for stable
 * Compose keys.
 */
data class ChatMessage(
  val role: String,
  val content: String = "",
  val attachments: List<AttachedContext> = emptyList(),
  val timestamp: Long = System.currentTimeMillis(),
  val events: List<ChatEvent> = emptyList(),
  val renderBlocks: List<RenderBlock> = emptyList(),
  val modelName: String = "",
  val provider: String = "",
  val serverName: String = "",
  val tokenUsage: TokenUsage? = null
) {
  val isUserMessage: Boolean get() = role == "user"

  /** Aggregated response content from all Response events. */
  val responseContent: String
    get() = events.filterIsInstance<ChatEvent.Response>().joinToString("") { it.content }

  /** Full content for clipboard: includes thinking, tool calls, and response text. */
  val fullContent: String
    get() {
      if (events.isEmpty()) return responseContent

      val contentBuilder = StringBuilder()
      for (event in events) {
        when (event) {
          is ChatEvent.Thinking -> {
            if (contentBuilder.isNotEmpty()) contentBuilder.append("\n\n")
            contentBuilder.append("<thinking>")
            contentBuilder.append(event.content)
            contentBuilder.append("</thinking>")
          }

          is ChatEvent.ToolCall -> {
            if (contentBuilder.isNotEmpty()) contentBuilder.append("\n\n")
            val argumentString = if (event.info.arguments.isNotEmpty()) {
              event.info.arguments.entries.joinToString(", ") {
                "${it.key}=${it.value}"
              }
            } else ""
            contentBuilder.append(
              "<tool_call>${event.info.alias}${
                if (argumentString.isNotBlank())
                  "($argumentString)" else ""
              }</tool_call>"
            )
          }

          is ChatEvent.Response -> {
            if (contentBuilder.isNotEmpty())
              contentBuilder.append("\n\n")
            contentBuilder.append(event.content)
          }

          is ChatEvent.Error -> {
            if (contentBuilder.isNotEmpty())
              contentBuilder.append("\n\n")
            contentBuilder.append("Error: ${event.message}")
          }
        }
      }
      return contentBuilder.toString()
    }

  /**
   * Appends a [ChatEvent] and incrementally updates [renderBlocks] in O(1).
   *
   * Consecutive events of the same type are merged into the last block
   * (string concatenation only), avoiding full-list re-iteration on every token.
   *
   * A `ToolCall` event with a non-blank `toolCallId` first tries to
   * **replace** a pending tool call (from a `tool_call_start` event)
   * that carries the same id, so a finished tool updates its
   * in-place spinner row instead of stacking a duplicate. When no
   * pending match exists it appends normally.
   */
  fun appendEvent(event: ChatEvent): ChatMessage {
    val newEvents: List<ChatEvent> = when (event) {
      is ChatEvent.ToolCall -> appendToolCallEvent(event)
      is ChatEvent.Response -> {
        val last = events.lastOrNull()
        if (last is ChatEvent.Response) {
          events.dropLast(1) + ChatEvent.Response(last.content + event.content)
        } else {
          events + event
        }
      }
      is ChatEvent.Thinking -> {
        val last = events.lastOrNull()
        if (last is ChatEvent.Thinking) {
          events.dropLast(1) + ChatEvent.Thinking(last.content + event.content)
        } else {
          events + event
        }
      }
      else -> events + event
    }
    val newRenderBlocks = when (event) {
      is ChatEvent.Response -> {
        val last = renderBlocks.lastOrNull()
        if (last is RenderBlock.Response)
          renderBlocks.dropLast(1) + RenderBlock.Response(last.content + event.content)
        else
          renderBlocks + RenderBlock.Response(event.content)
      }

      is ChatEvent.Thinking -> {
        val last = renderBlocks.lastOrNull()
        if (last is RenderBlock.Thinking)
          renderBlocks.dropLast(1) + RenderBlock.Thinking(last.content + event.content)
        else
          renderBlocks + RenderBlock.Thinking(event.content)
      }

      is ChatEvent.ToolCall -> appendToolCall(event)

      is ChatEvent.Error -> renderBlocks + RenderBlock.Error(event.message, event.code)
    }
    return copy(
      events = newEvents,
      renderBlocks = newRenderBlocks,
    )
  }

  /**
   * Resolve the [events] list change for a [ChatEvent.ToolCall].
   *
   * A completed tool call (non-pending) with a non-blank `toolCallId`
   * replaces its pending placeholder entry (same id) so the event
   * timeline keeps a single row per tool invocation. Pending events
   * and unmatched events are appended normally.
   */
  private fun appendToolCallEvent(event: ChatEvent.ToolCall): List<ChatEvent> {
    if (event.info.toolCallId.isNotBlank()) {
      val existingIdx = events.indexOfLast {
        it is ChatEvent.ToolCall && it.info.toolCallId == event.info.toolCallId
      }
      if (existingIdx >= 0) {
        val updated = events.toMutableList()
        updated[existingIdx] = event
        return updated
      }
    }
    return events + event
  }

  /**
   * Resolve the [renderBlocks] change for a [ChatEvent.ToolCall].
   *
   * If a block with the same [RenderBlock.ToolCall.toolCallId] already
   * exists, it is replaced by the new block regardless of pending state.
   * This handles the delegate case where two `sub_agent:session_end`
   * events arrive (one forwarded from the inner agent, one from the
   * skill itself) — the second event carries the `conversation` payload
   * and must replace the first.
   *
   * When no matching [toolCallId] exists, the block is appended.
   */
  private fun appendToolCall(event: ChatEvent.ToolCall): List<RenderBlock> {
    val info: ToolCallInfo = event.info
    val newBlock = RenderBlock.ToolCall(
      alias = info.alias,
      result = info.result,
      success = info.success,
      arguments = info.arguments,
      errorDetail = info.errorDetail,
      errorMessage = info.errorMessage,
      toolCallId = info.toolCallId,
      pending = info.pending,
      timeoutSeconds = info.timeoutSeconds
    )

    if (info.toolCallId.isNotBlank()) {
      val existingIdx = renderBlocks.indexOfLast {
        it is RenderBlock.ToolCall && it.toolCallId == info.toolCallId
      }
      if (existingIdx >= 0) {
        val blocks = renderBlocks.toMutableList()
        blocks[existingIdx] = newBlock
        return blocks
      }
    }
    return renderBlocks + newBlock
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
      val existingBlock = renderBlocks[lastFailedBlockIdx] as RenderBlock.ToolCall
      renderBlocks.toMutableList().apply {
        set(lastFailedBlockIdx, existingBlock.copy(errorMessage = friendlyMessage, errorDetail = errorDetail))
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

    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> SimpleDateFormat(
      "MMM d",
      Locale.getDefault()
    ).format(Date(timestamp))

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
