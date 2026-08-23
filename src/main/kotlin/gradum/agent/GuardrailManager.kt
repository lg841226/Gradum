/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GuardrailManager.kt  2026-08-23 20:21:07 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration

private val SENTENCE_SPLIT_PATTERN: Regex = Regex("(?<=[.!?])\\s+")

/**
 * Detects guardrail violations during an agent session.
 *
 * Responsibilities:
 * - Red-line keyword detection (content safety keywords).
 * - Repeated/abnormal response detection (repetitive loops).
 * - Tool-runaway detection (identical call signatures).
 *
 * This class is purely about *detection* — escalation (mission revoked,
 * session abort) is handled by [SessionManager] and the main [Agent] loop.
 */
class GuardrailManager(private val configuration: AgentConfiguration) {

  private var redLineKeywords: List<String> = emptyList()
  private var redLineKeywordsLowercase: List<String> = emptyList()
  private val redLineHitKeywords: MutableList<String> = mutableListOf()
  private var redLineHitCounter: Int = 0
  private val repeatedResponseTracker: MutableList<String> = mutableListOf()

  /** Resets all guardrail counters for a new execution. */
  fun reset() {
    redLineHitCounter = 0
    redLineHitKeywords.clear()
    repeatedResponseTracker.clear()
  }

  /** Initializes the red-line keyword list. */
  fun initializeRedLineKeywords(keywords: List<String>) {
    redLineKeywords = keywords
    redLineKeywordsLowercase = keywords.map { it.lowercase() }
  }

  /** Returns the current red-line hit count. */
  val redLineHitCount: Int get() = redLineHitCounter

  /** Returns the matched red-line keywords. */
  val matchedRedLineKeywords: List<String> get() = redLineHitKeywords.toList()

  /**
   * Checks [text] against the red-line keyword list. Returns the
   * matched keywords (empty if none).
   */
  fun checkRedLineKeywords(text: String?): List<String> {
    if (text.isNullOrBlank() || redLineKeywords.isEmpty()) {
      return emptyList()
    }
    val lowercasedText: String = text.lowercase()
    return redLineKeywords.filterIndexed { index: Int, _: String ->
      lowercasedText.contains(redLineKeywordsLowercase[index])
    }
  }

  /** Records a red-line hit and returns the updated hit count. */
  fun recordRedLineHit(matched: List<String>): Int {
    redLineHitCounter++
    redLineHitKeywords.addAll(matched)
    return redLineHitCounter
  }

  /**
   * Returns true when the response is abnormally repetitive
   * (same sentence repeated 3+ times).
   */
  fun isAbnormalResponse(responseText: String?): Boolean {
    if (responseText.isNullOrBlank()) return false
    val trimmedText = responseText.trim()
    val sentenceList = trimmedText.split(SENTENCE_SPLIT_PATTERN)
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
