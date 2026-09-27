package gradum.skill.dsl

import gradum.skill.Choice
import gradum.skill.L10n
import gradum.skill.L10nText

/** Builder DSL for one [gradum.skill.AskScope.askInteraction] call. */
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
