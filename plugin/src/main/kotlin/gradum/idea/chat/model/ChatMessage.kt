package gradum.idea.chat.model

import gradum.idea.editor.AttachedContext
import gradum.idea.utils.GradumBundle.message
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit

/** Token usage snapshot for a completed assistant turn. */
data class TokenUsage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  val totalTokens: Int = 0,
  /** Prompt tokens of the most recent single request, i.e. the live context window size. */
  val contextTokens: Int = 0,
)

/**
 * Stable semantic code for an ask choice option, echoed from the server's
 * `Choice.Meaning` wire codes. The card maps each code to a localized button
 * label at render time: the server never ships display copy.
 */
object AskChoiceMeaning {
  const val ALLOW_ONCE: String = "allow_once"
  const val ALLOW_ALWAYS: String = "allow_always"
  const val REJECT: String = "reject"
}

/** A single choice option in an ask card, identified by its stable semantic code (echoes the server `Choice` type). */
data class AskChoice(
  val semantics: String,
  val labelKey: String? = null
)

/** Prompt body of an ask card: either discrete choices or free-text input. */
sealed interface AskPrompt {
  data class Choices(val choices: List<AskChoice>) : AskPrompt
  data class Input(val placeholder: String) : AskPrompt
}

/**
 * One block of an assistant message, in timeline order. Consecutive blocks of
 * the same kind are coalesced (string concatenation only), so Compose can reuse
 * composables without rebuilding the whole list.
 */
sealed class RenderBlock {
  data class Thinking(val content: String) : RenderBlock()
  data class ToolCall(
    val alias: String,
    val success: Boolean,
    val result: String = "",
    val toolCallId: String = "",
    val errorDetail: String = "",
    val errorMessage: String = "",
    val timeoutSeconds: Int = 0,
    val pending: Boolean = false,
    val arguments: Map<String, Any> = emptyMap(),
  ) : RenderBlock()

  data class Response(val content: String) : RenderBlock()
  data class Error(val message: String, val code: String = "", val detail: String = "") : RenderBlock()

  /**
   * Self-contained ask card payload: the server-supplied question (title/
   * details already resolved to strings) plus the interaction handle needed to
   * POST the user's answer back to `/events/respond`.
   */
  data class AskInteraction(
    val requestId: String,
    val sessionId: String,
    val title: String,
    val details: String,
    val default: String,
    val prompt: AskPrompt
  ) : RenderBlock()
}

/**
 * A single message in the chat history. An assistant message is its ordered
 * [renderBlocks]; a user message carries [content] and [attachments] instead.
 */
data class ChatMessage(
  val role: String,
  val timestamp: Long = System.currentTimeMillis(),
  val renderBlocks: List<RenderBlock> = emptyList(),
  val attachments: List<AttachedContext> = emptyList(),
  val content: String = "",
  val provider: String = "",
  val modelName: String = "",
  /**
   * Stable identifier allocated by the server and stored in the history under
   * the same `id`. Both ends share it so a "rewind" (withdraw) can locate the
   * exact server-side user message to truncate the context from.
   */
  val messageId: String = "",
  val serverName: String = "",
  val tokenUsage: TokenUsage? = null
) {
  val isUserMessage: Boolean get() = role == "user"

  /** Aggregated response content from all Response blocks. */
  val responseContent: String
    get() = renderBlocks.filterIsInstance<RenderBlock.Response>().joinToString("") { it.content }

  /** Full content for clipboard: includes thinking, tool calls, and response text. */
  val fullContent: String
    get() {
      val contentBuilder = StringBuilder()
      for (block in renderBlocks) {
        when (block) {
          is RenderBlock.Thinking -> {
            if (contentBuilder.isNotEmpty()) contentBuilder.append("\n\n")
            contentBuilder.append("<thinking>")
            contentBuilder.append(block.content)
            contentBuilder.append("</thinking>")
          }

          is RenderBlock.ToolCall -> {
            if (contentBuilder.isNotEmpty()) contentBuilder.append("\n\n")
            val argumentString: String = if (block.arguments.isNotEmpty()) {
              block.arguments.entries.joinToString(separator = ", ") {
                "${it.key}=${it.value}"
              }
            } else ""
            contentBuilder.append(
              "<tool_call>${block.alias}${
                if (argumentString.isNotBlank())
                  "($argumentString)" else ""
              }</tool_call>"
            )
          }

          is RenderBlock.Response -> {
            if (contentBuilder.isNotEmpty())
              contentBuilder.append("\n\n")
            contentBuilder.append(block.content)
          }

          is RenderBlock.Error -> {
            if (contentBuilder.isNotEmpty())
              contentBuilder.append("\n\n")
            contentBuilder.append("Error: ${block.message}")
          }

          is RenderBlock.AskInteraction -> {
            // An ask card is interactive UI awaiting a user decision, not
            // agent prose: it contributes nothing to the clipboard copy.
          }
        }
      }
      return contentBuilder.toString()
    }

  /**
   * Appends [block] and updates [renderBlocks] in O(1), without re-iterating
   * the list on every token.
   *
   * Consecutive string blocks of the same kind coalesce. A tool call whose
   * `toolCallId` already exists updates that row in place: a finished tool
   * replaces its pending placeholder (from `tool_call_start`) instead of
   * stacking a duplicate, and a re-sent `sub_agent:session_end` refreshes the
   * delegate capsule.
   */
  fun appendBlock(block: RenderBlock): ChatMessage {
    val newBlocks: List<RenderBlock> = when (block) {
      is RenderBlock.Response -> {
        val last: RenderBlock? = renderBlocks.lastOrNull()
        if (last is RenderBlock.Response)
          renderBlocks.dropLast(n = 1) + RenderBlock.Response(last.content + block.content)
        else
          renderBlocks + block
      }

      is RenderBlock.Thinking -> {
        val last: RenderBlock? = renderBlocks.lastOrNull()
        if (last is RenderBlock.Thinking)
          renderBlocks.dropLast(n = 1) + RenderBlock.Thinking(last.content + block.content)
        else
          renderBlocks + block
      }

      is RenderBlock.ToolCall -> updateToolCall(block)

      is RenderBlock.Error, is RenderBlock.AskInteraction -> renderBlocks + block
    }
    return copy(renderBlocks = newBlocks)
  }

  private fun updateToolCall(block: RenderBlock.ToolCall): List<RenderBlock> {
    if (block.toolCallId.isNotBlank()) {
      val existingIndex: Int = renderBlocks.indexOfLast {
        it is RenderBlock.ToolCall && it.toolCallId == block.toolCallId
      }
      if (existingIndex >= 0) {
        return renderBlocks.toMutableList().also { it[existingIndex] = block }
      }
    }
    return renderBlocks + block
  }

  /**
   * Finds the last failed ToolCall and updates its error info in O(1) (no full
   * list re-iteration).
   *
   * @return The updated message, or `this` if no failed ToolCall was found.
   */
  fun updateLastError(friendlyMessage: String, errorDetail: String): ChatMessage {
    val lastFailedIndex: Int = renderBlocks.indexOfLast {
      it is RenderBlock.ToolCall && !it.success
    }
    if (lastFailedIndex < 0) return this

    val failedBlock: RenderBlock.ToolCall = renderBlocks[lastFailedIndex] as RenderBlock.ToolCall
    return copy(
      renderBlocks = renderBlocks.toMutableList().also {
        it[lastFailedIndex] = failedBlock.copy(
          errorMessage = friendlyMessage, errorDetail = errorDetail
        )
      }
    )
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
    isSameDay(cal1 = now, cal2 = messageTime) ->
      SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    isYesterday(now, target = messageTime) -> {
      val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
      "${message("gradum.timestamp.yesterday")} $time"
    }

    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> DateTimeFormatter
      .ofPattern(message("gradum.timestamp.monthDay"), Locale.ROOT)
      .format(
        Instant.ofEpochMilli(timestamp)
          .atZone(ZoneId.systemDefault())
          .toLocalDate()
      )

    else -> "$diffDays ${message("gradum.timestamp.days.ago")}"
  }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
  return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
    cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, target: Calendar): Boolean {
  val yesterday: Calendar = (now.clone() as Calendar).apply {
    add(Calendar.DAY_OF_YEAR, -1)
  }
  return isSameDay(cal1 = yesterday, cal2 = target)
}
