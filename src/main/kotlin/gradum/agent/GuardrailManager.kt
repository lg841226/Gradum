/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GuardrailManager.kt  2026-08-31 19:21:55 Changed by gwy
 */

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
 * This class is purely about *detection* — escalation (mission revoked,
 * session abort) is handled by [SessionManager] and the main [Agent] loop.
 */
class GuardrailManager(private val configuration: AgentConfiguration) {

  private val repeatedResponseTracker: MutableList<String> = mutableListOf()

  /** Resets all guardrail counters for a new execution. */
  fun reset() {
    repeatedResponseTracker.clear()
  }

  /**
   * Returns true when the response is abnormally repetitive
   * (same sentence repeated 3+ times).
   */
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

  /**
   * Tracks repeated responses. Returns true when the tracker has
   * reached maxRepeatedResponses.
   */
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

  /** Returns the number of tracked repeated responses. */
  val repeatedResponseCount: Int get() = repeatedResponseTracker.size

  /** Returns the list of tracked repeated responses. */
  val repeatedResponses: List<String> get() = repeatedResponseTracker.toList()
}
