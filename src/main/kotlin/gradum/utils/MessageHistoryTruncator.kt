/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageHistoryTruncator.kt  2026-07-08 18:13:34 Changed by gwy
 */

package gradum.utils

/**
 * Returns the longest tail of [messages] that:
 *  1. starts at a turn boundary (a `user` message, an `assistant`
 *     message with or without `tool_calls`, or a leading run of
 *     orphan `tool` messages),
 *  2. contains at most [maxTurns] messages, and
 *  3. does not split any assistant-with-tool-calls turn (i.e. if
 *     an assistant message is included, all of its `tool` results
 *     must be included too).
 *
 * A "turn" is either a single `user` message, an `assistant` message,
 * or an `assistant` message immediately followed by one or more
 * `tool` result messages.
 *
 * If [messages] has fewer than or exactly [maxTurns] entries, it's
 * returned unchanged. If every turn is larger than [maxTurns] (a
 * pathological case — turns are normally 1-3 messages), the most
 * recent [maxTurns] messages are returned as a last-resort tail cut.
 * Callers should log a warning when they detect this case.
 */
fun takeLastTurns(messages: List<Map<String, Any>>, maxTurns: Int): List<Map<String, Any>> {
  if (messages.isEmpty()) return emptyList()
  if (maxTurns <= 0) return emptyList()
  if (messages.size <= maxTurns) return messages

  val turnsReversed: MutableList<List<Map<String, Any>>> = mutableListOf()
  var currentIndex: Int = messages.size
  while (currentIndex > 0) {
    val turnStartIndex: Int = findTurnStart(messages, currentIndex - 1)
    turnsReversed.add(messages.subList(turnStartIndex, currentIndex))
    currentIndex = turnStartIndex
  }

  val keptMessages: MutableList<Map<String, Any>> = mutableListOf()
  var usedSlots = 0
  var exhausted = false
  for (turnMessages: List<Map<String, Any>> in turnsReversed) {
    if (exhausted) break
    if (usedSlots + turnMessages.size > maxTurns) {
      exhausted = true
    } else {
      keptMessages.addAll(0, turnMessages)
      usedSlots += turnMessages.size
    }
  }
  if (keptMessages.size < maxTurns)
    return messages.subList(messages.size - maxTurns, messages.size)

  return keptMessages
}

/**
 * Given that [tailIndex] is the index of the *last* message of a
 * turn, return the index of the *first* message of that turn.
 *
 * Rules:
 *  - If `messages[tailIndex]` is a `user` or `assistant` message →
 *    return `tailIndex` (single-message turn).
 *  - If it's a `tool` message → walk back over the contiguous run
 *    of preceding `tool` messages, then include the `assistant`
 *    message that issued those `tool_calls` (it must exist; if not,
 *    the orphan tool messages form their own turn).
 */
private fun findTurnStart(messages: List<Map<String, Any>>, tailIndex: Int): Int {
  val lastRole: String = roleOf(messages[tailIndex])
  if (lastRole == "user") return tailIndex
  if (lastRole == "assistant") return tailIndex

  if (lastRole == "tool") {
    var toolRunStart: Int = tailIndex
    var foundBoundary = true

    while (foundBoundary) {
      val previousIndex: Int = toolRunStart - 1
      if (previousIndex < 0)
        foundBoundary = false
      else if (roleOf(messages[previousIndex]) == "tool")
        toolRunStart = previousIndex
      else foundBoundary = false
    }

    val assistantIndex: Int = toolRunStart - 1
    if (assistantIndex >= 0 && roleOf(messages[assistantIndex]) == "assistant")
      return assistantIndex

    return toolRunStart
  }
  return tailIndex
}

private fun roleOf(message: Map<String, Any>): String =
  message["role"] as? String ?: ""
