package gradum.skill.dsl

import gradum.skill.Choice
import gradum.skill.L10n
import gradum.skill.L10nText

/**
 * Builder DSL for one [gradum.skill.AskScope.askInteraction] call.
 *
 * meaningFor(choice) resolves the semantic [Choice.Meaning] for a posted
 * semantic code, or null when this card did not declare it. l10n is the
 * `l10n` factory resolved inside an ask builder block.
 */
class AskBuilder {

  /**
   * Choice to pre-select on a choices-flavor card, expressed as the stable
   * semantic [Choice.Meaning]: a typo cannot compile, and [validate]
   * rejects a meaning the card does not offer. The wire carries its
   * semantic code (e.g. `reject`), which is also the token the card
   * pre-selects and the user answers with — there is no second id to keep
   * in sync.
   */
  var default: Choice.Meaning? = null

  /**
   * Initial text for an input-flavor card. Both flavors serialize to the
   * wire `default` field but mean different things (semantic code vs. free
   * text), so they are separate properties here.
   */
  var prefill: String? = null

  var title: L10nText? = null
  var details: L10nText? = null

  private val choiceItems: LinkedHashMap<Choice.Meaning, String?> = LinkedHashMap()
  private var inputPlaceholder: L10nText? = null

  fun meaningFor(choice: String): Choice.Meaning? =
    choiceItems.keys.firstOrNull { it.wire == choice }

  fun choices(block: ChoicesScope.() -> Unit) {
    ChoicesScope(choiceItems).block()
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
    require(default == null || choiceItems.isNotEmpty()) {
      "ask_interaction default (choice pre-select) requires choices { ... }"
    }
    default?.let { wanted: Choice.Meaning ->
      require(wanted in choiceItems) {
        "ask_interaction default ${wanted.wire} matches no declared choice, " +
          "declared semantics = ${choiceItems.keys.map { it.wire }}"
      }
    }
    require(prefill == null || inputPlaceholder != null) {
      "ask_interaction prefill requires input { ... }"
    }
  }

  internal fun toWire(requestId: String, sessionId: String): Map<String, Any> {
    val payload: MutableMap<String, Any> = linkedMapOf()
    payload["requestId"] = requestId
    payload["sessionId"] = sessionId
    payload["title"] = requireNotNull(title).toWire()
    payload["default"] =
      if (choiceItems.isNotEmpty()) {
        (default ?: choiceItems.keys.first()).wire
      } else {
        prefill.orEmpty()
      }
    details?.let { payload["details"] = it.toWire() }

    if (choiceItems.isNotEmpty()) {
      payload["choices"] = choiceItems.map { (meaning: Choice.Meaning, labelKey: String?) ->
        buildMap {
          put("semantics", meaning.wire)
          labelKey?.let { put("labelKey", it) }
        }
      }
    } else {
      payload["input"] = mapOf("placeholder" to requireNotNull(inputPlaceholder).toWire())
    }
    return payload
  }

  val l10n: L10n get() = L10n
}

/**
 * DSL scope for defining choice options, keyed by their stable semantic
 * code: that code is the wire's only identity for a choice (the answer
 * token, the pre-select key), so nothing here is hand-written twice.
 *
 * item(semantics, labelKey) adds one option. An optional labelKey lets the
 * server pin a specific i18n bundle key for this option's button label
 * (e.g. a `write_file` authorization card); when absent the plugin falls
 * back to its own semantic-code mapping. Duplicate semantics are rejected.
 */
class ChoicesScope internal constructor(
  private val items: LinkedHashMap<Choice.Meaning, String?>,
) {
  fun item(semantics: Choice.Meaning, labelKey: String? = null) {
    require(semantics !in items) { "duplicate choice ${semantics.wire}" }
    items[semantics] = labelKey
  }
}

/** DSL scope for the free-text-input flavor of an ask. */
class InputScope {
  var placeholder: L10nText? = null
}
