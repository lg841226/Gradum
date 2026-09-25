/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InteractionTypes.kt  2026-09-25 19:58:46 Changed by gwy
 */

package gradum.skill

/**
 * Shared types for the agent-initiated user interaction channel.
 *
 * A skill asks the user a question at runtime; the plugin renders an ask
 * card and POSTs the user's answer back to `POST /events/respond`, which
 * un-blocks the suspended skill. The server never ships an ask card with
 * human-readable copy — it only sends logical keys and stable semantic
 * codes, leaving translation to the consuming IDE's i18n bundle.
 */

/** Source language of a dynamically generated text (the server only sends the tag, never localized copy). */
enum class Lang(val wire: String) {
  EN("en");

  companion object {
    fun fromWire(value: String?): Lang =
      entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: EN
  }
}

/** A text value the server wants displayed to the user, abstracted for i18n. */
sealed interface L10nText {

  /** Stable machine tag that the plugin dispatches on: `key` vs `raw`. */
  val wireKind: String

  /** Serialize to the wire payload ([JsonUtil.encodeMap]-compatible). */
  fun toWire(): Map<String, Any>

  /**
   * Reference to an i18n bundle entry (`xxx.xxx.xxx=copy`). The server
   * emits [bundleKey] + interpolation [args]; the plugin resolves the copy.
   */
  data class Key(
    val bundleKey: String,
    val args: List<String> = emptyList(),
  ) : L10nText {
    override val wireKind: String get() = "key"
    override fun toWire(): Map<String, Any> = mapOf(
      "kind" to wireKind,
      "key" to bundleKey,
      "args" to args,
    )
  }

  /**
   * Model-generated (or server-assembled) text with an explicit
   * [sourceLang] tag so a translating IDE knows what language it is in.
   */
  data class Raw(
    val text: String,
    val sourceLang: Lang = Lang.EN,
  ) : L10nText {
    override val wireKind: String get() = "raw"
    override fun toWire(): Map<String, Any> = mapOf(
      "kind" to wireKind,
      "text" to text,
      "sourceLang" to sourceLang.wire,
    )
  }
}

/**
 * Factory for building [L10nText] values inside an ask builder block,
 * exposed as `l10n` so the DSL reads `l10n.key(...)` / `l10n.raw(...)`.
 */
object L10n {
  fun key(bundleKey: String, vararg args: String): L10nText =
    L10nText.Key(bundleKey, args.toList())

  fun raw(text: String, source: Lang = Lang.EN): L10nText =
    L10nText.Raw(text, source)
}

/** Choice option primitives for the ask channel. */
object Choice {
  /**
   * Stable semantic code for a choice option. The wire carries the semantic
   * [wire] code, NOT display copy — the plugin maps semantics (e.g.
   * `ALLOW_ONCE`) to its own localized button label.
   */
  enum class Meaning(val wire: String) {
    ALLOW_ONCE("allow_once"),
    ALLOW_ALWAYS("allow_always"),
    REJECT("reject");

    companion object {
      fun fromWire(wire: String?): Meaning? = entries.firstOrNull { it.wire == wire }
    }
  }
}

/** The outcome of an [AskScope.askInteraction], returned once the user answers. */
sealed interface AskResult {
  /**
   * The user picked a choice option. [id] is the choice's stable id (a
   * rendering / answer key); [meaning] is the authoritative semantic code
   * the caller switches on, so callers never branch on string ids.
   */
  data class Case(
    val id: String,
    val meaning: Choice.Meaning = Choice.Meaning.REJECT,
  ) : AskResult

  /** The user submitted free text (`.input` flavor); [value] is the submitted text. */
  data class Text(val value: String) : AskResult

  /** The user dismissed / closed the ask card without choosing. */
  data object Cancelled : AskResult
}
