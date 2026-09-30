package gradum.agent

import gradum.AgentConfiguration

private val SENTENCE_SPLIT_PATTERN: Regex = Regex(pattern = "(?<=[.!?])\\s+")

/**
 * Detects guardrail violations during an agent session.
 *
 * Responsibilities:
 * - Repeated/abnormal response detection (repetitive loops).
 * - Tool-runaway detection (identical call signatures).
 *
 * This class is purely about *detection*: escalation (mission revoked,
 * session abort) is handled by [SessionManager] and the main [Agent] loop.
 *
 * reset() clears all guardrail counters for a new execution.
 * isAbnormalResponse(responseText) returns true when the response is
 * abnormally repetitive (the same sentence repeated 3+ times).
 * trackRepeatedResponse(responseText) tracks repeated responses and returns
 * true once the tracker has reached maxRepeatedResponses.
 * repeatedResponseCount is the number of tracked repeated responses, and
 * repeatedResponses is the list of them.
 */
class GuardrailManager(private val configuration: AgentConfiguration) {

  private val repeatedResponseTracker: MutableList<String> = mutableListOf()

  fun reset() {
    repeatedResponseTracker.clear()
  }

  fun isAbnormalResponse(responseText: String?): Boolean {
    if (responseText.isNullOrBlank()) return false
    val trimmedText = responseText.trim()
    val sentenceList = trimmedText.split(regex = SENTENCE_SPLIT_PATTERN)
      .map { it.trim() }
      .filter { it.isNotBlank() && it.length > 3 }
    return sentenceList.size >= 3 && sentenceList.groupingBy {
      it.lowercase()
    }.eachCount().values.any { it >= 3 }
  }

  fun trackRepeatedResponse(responseText: String?): Boolean {
    val currentResponse: String = (responseText ?: "").trim()
    val isDuplicate: Boolean =
      repeatedResponseTracker.isNotEmpty() && currentResponse == repeatedResponseTracker.last()
    val isAbnormal: Boolean = isAbnormalResponse(responseText)
    if (isDuplicate || isAbnormal) {
      repeatedResponseTracker.add(currentResponse)
      return repeatedResponseTracker.size >= configuration.maxRepeatedResponses
    }
    repeatedResponseTracker.clear()
    return false
  }

  val repeatedResponseCount: Int get() = repeatedResponseTracker.size

  val repeatedResponses: List<String> get() = repeatedResponseTracker.toList()
}
