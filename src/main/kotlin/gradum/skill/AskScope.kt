/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AskScope.kt  2026-09-25 12:09:41 Changed by gwy
 */

package gradum.skill

import gradum.agent.GradumEventType
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

/** Builder DSL for one askInteraction call. */
class AskBuilder {

  var default: String? = null
  var title: L10nText? = null
  var details: L10nText? = null

  private val choiceItems: LinkedHashMap<String, Choice.Meaning> = LinkedHashMap()
  private val choiceLabelKeys: MutableMap<String, String> = mutableMapOf()
  private var inputPlaceholder: L10nText? = null

  /** Resolves the semantic [Choice.Meaning] for a choice [id], or null when unknown. */
  fun meaningFor(id: String): Choice.Meaning? = choiceItems[id]

  fun choices(block: ChoicesScope.() -> Unit) {
    ChoicesScope(choiceItems, choiceLabelKeys).block()
  }

  fun input(block: InputScope.() -> Unit) {
    val inputScope = InputScope()
    inputScope.block()
    inputPlaceholder = inputScope.placeholder
  }

  internal fun validate() {
    require(choiceItems.isNotEmpty() || inputPlaceholder != null) {
      "ask_interaction must define choices { ... } or input { ... }, got neither"
    }
    require(!(choiceItems.isNotEmpty() && inputPlaceholder != null)) {
      "ask_interaction cannot mix choices { ... } and input { ... } in one call"
    }
    require(title != null) {
      "ask_interaction requires a title"
    }
  }

  internal fun toWire(requestId: String, sessionId: String): Map<String, Any> {
    val payload: MutableMap<String, Any> = linkedMapOf()
    payload["requestId"] = requestId
    payload["sessionId"] = sessionId
    payload["title"] = requireNotNull(title).toWire()
    payload["default"] = default ?: choiceItems.keys.firstOrNull().orEmpty()
    details?.let { payload["details"] = it.toWire() }

    if (choiceItems.isNotEmpty()) {
      payload["choices"] = choiceItems.map { (id: String, meaning: Choice.Meaning) ->
        buildMap {
          put("id", id)
          put("semantics", meaning.wire)
          choiceLabelKeys[id]?.let { put("labelKey", it) }
        }
      }
    } else {
      payload["input"] = mapOf("placeholder" to requireNotNull(inputPlaceholder).toWire())
    }
    return payload
  }

  /** The `l10n` factory resolved inside an ask builder block. */
  val l10n: L10n get() = L10n
}

/** DSL scope for defining choice options (id → stable semantic code). */
class ChoicesScope internal constructor(
  private val items: LinkedHashMap<String, Choice.Meaning>,
  private val labelKeys: MutableMap<String, String>,
) {
  /**
   * Adds a choice option with a stable [id] and a [semantics] code. An
   * optional [labelKey] lets the server pin a specific i18n bundle key
   * for this option's button label (e.g. a `write_file` authorization
   * card); when absent the plugin falls back to its own semantic-code
   * mapping.
   */
  fun item(id: String, semantics: Choice.Meaning, labelKey: String? = null) {
    require(id.isNotBlank()) { "choice id must not be blank" }
    require(id !in items) { "duplicate choice id '$id'" }
    items[id] = semantics
    labelKey?.let { labelKeys[id] = it }
  }
}

/** DSL scope for the free-text-input flavor of an ask. */
class InputScope {
  var placeholder: L10nText? = null
}
