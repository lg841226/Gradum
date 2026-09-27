/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AskScope.kt  2026-09-25 12:09:41 Changed by gwy
 */

package gradum.skill

import gradum.agent.GradumEventType
import gradum.skill.dsl.AskBuilder
import java.util.*

/**
 * Entry point for agent-initiated user interactions, authored block-style:
 *
 * ```kotlin
 * val vote = context.scope.askInteraction {
 *     title   = l10n.key("gradum.ask.run_cmd.confirm")
 *     details = l10n.raw("rm -rf build/", source = Lang.EN)
 *     choices {
 *         item("once",   Choice.Meaning.ALLOW_ONCE)
 *         item("always", Choice.Meaning.ALLOW_ALWAYS)
 *         item("no",     Choice.Meaning.REJECT)
 *     }
 *     default = "no"
 * }
 * ```
 *
 * The call pushes an `ask_interaction` event to the plugin, parks the
 * executing thread on a [PendingQuestions] entry, and returns the user's
 * answer when the plugin POSTs it back via `POST /events/respond`.
 *
 * There is intentionally **no timeout** — a question may stay open
 * indefinitely until the user answers or dismisses the card (see
 * [PendingQuestions]). [Skill.execute] is non-suspend, so the wait is a
 * blocking run on the agent's IO thread rather than a coroutine suspension.
 *
 * @property pendingQuestions registry the answer is parked against.
 * @property emitEvent gateway that ships the card over the NDJSON stream.
 */
class AskScope(
  private val sessionId: String,
  private val pendingQuestions: PendingQuestions,
  private val emitEvent: ((eventType: String, eventData: Map<String, Any>) -> Unit)?,
) {

  /**
   * Asks the user a question and blocks until it is answered.
   *
   * [builder] must configure exactly one flavor: either a `choices` block
   * (→ [AskResult.Case]) or an `input` block (→ [AskResult.Text]). The card
   * is emitted before the wait so the plugin can resolve it immediately.
   */
  fun askInteraction(builder: AskBuilder.() -> Unit): AskResult {
    val request = AskBuilder().apply(builder)
    request.validate()

    val requestId: String = UUID.randomUUID().toString()
    emitEvent?.invoke(
      GradumEventType.ASK_INTERACTION.wireName,
      request.toWire(requestId = requestId, sessionId = sessionId),
    )
    return when (val result: AskResult = pendingQuestions.await(sessionId, requestId)) {
      is AskResult.Case -> AskResult.Case(
        id = result.id,
        meaning = request.meaningFor(result.id) ?: Choice.Meaning.REJECT
      )

      else -> result
    }
  }
}
