package gradum.agent

import gradum.AgentConfiguration
import gradum.Version

/**
 * Manages the lifecycle of an agent session.
 *
 * Tracks abort/complete state, emits `session_end` events, and provides
 * guardrail escalation helpers ([emitRevoked], [recordGuardrail]).
 *
 * reset() resets session state for a new execution. abort(reason) aborts
 * the session with a descriptive reason and emits a `session_end` event with
 * `aborted=true`. finish() emits a normal `session_end` event (no abort).
 * emitRevoked(reason, details) emits a `mission_revoked` event, and
 * recordGuardrail(type, hitCount, maxAllowed, guardrailDetails) emits a
 * `guardrail` event.
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

  fun reset() {
    isAborted = false
    endReason = null
    startTimeMillis = System.currentTimeMillis()
  }

  fun abort(reason: String = "unknown", tokenUsage: Map<String, Any> = emptyMap()) {
    isAborted = true
    endReason = reason
    val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000

    emitEvent(
      GradumEventType.SESSION_END.wireName, mapOf(
        "aborted" to true,
        "tokenUsage" to tokenUsage,
        "elapsedSeconds" to elapsedSeconds,
        "model" to configuration.modelName,
        "version" to Version.GRADUM_VERSION
      )
    )
  }

  fun finish() {
    if (!isAborted) {
      val elapsedSeconds: Long = (System.currentTimeMillis() - startTimeMillis) / 1000
      emitEvent(
        GradumEventType.SESSION_END.wireName, mapOf(
          "version" to Version.GRADUM_VERSION,
          "elapsedSeconds" to elapsedSeconds,
          "model" to configuration.modelName
        )
      )
    }
  }

  fun emitRevoked(reason: String, details: Map<String, Any>) {
    emitEvent(
      GradumEventType.MISSION_REVOKED.wireName, mapOf(
        "reason" to reason,
        "details" to details
      )
    )
  }

  fun recordGuardrail(
    type: String,
    hitCount: Int,
    maxAllowed: Int,
    guardrailDetails: Map<String, Any>
  ) {
    emitEvent(
      GradumEventType.GUARDRAIL.wireName,
      mapOf(
        "type" to type,
        "hitCount" to hitCount,
        "maxAllowed" to maxAllowed,
      ) + guardrailDetails
    )
  }
}
