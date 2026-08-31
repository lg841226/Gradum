/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SessionManager.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration
import gradum.Version

/**
 * Manages the lifecycle of an agent session.
 *
 * Tracks abort/complete state, emits `session_end` events, and provides
 * guardrail escalation helpers ([emitRevoked], [recordGuardrail]).
 */
class SessionManager(
  private val configuration: AgentConfiguration,
  private val emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit
) {

  var isAborted: Boolean = false
    private set
  var endReason: String? = null
    private set
  var startTimeMillis: Long = 0L
    private set

  /** Resets session state for a new execution. */
  fun reset() {
    isAborted = false
    endReason = null
    startTimeMillis = System.currentTimeMillis()
  }

  /**
   * Aborts the session with a descriptive [reason]. Emits a
   * `session_end` event with `aborted=true`.
   */
  fun abort(reason: String = "unknown", tokenUsage: Map<String, Any> = emptyMap()) {
    isAborted = true
    endReason = reason
    val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000

    emitEvent(
      "session_end", mapOf(
        "version" to Version.GRADUM_VERSION,
        "elapsedSeconds" to elapsedSeconds,
        "model" to configuration.modelName,
        "tokenUsage" to tokenUsage,
        "aborted" to true,
      )
    )
  }

  /** Emits a normal `session_end` event (no abort). */
  fun finish() {
    if (!isAborted) {
      val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000
      emitEvent(
        "session_end", mapOf(
          "version" to Version.GRADUM_VERSION,
          "elapsedSeconds" to elapsedSeconds,
          "model" to configuration.modelName,
        )
      )
    }
  }

  /** Emits a `mission_revoked` event. */
  fun emitRevoked(reason: String, details: Map<String, Any>) {
    emitEvent(
      "mission_revoked", mapOf(
        "reason" to reason,
        "details" to details,
      )
    )
  }

  /** Emits a `guardrail` event. */
  fun recordGuardrail(
    type: String,
    hitCount: Int,
    maxAllowed: Int,
    guardrailDetails: Map<String, Any>
  ) {
    emitEvent(
      "guardrail",
      mapOf(
        "type" to type,
        "hitCount" to hitCount,
        "maxAllowed" to maxAllowed,
      ) + guardrailDetails,
    )
  }
}
