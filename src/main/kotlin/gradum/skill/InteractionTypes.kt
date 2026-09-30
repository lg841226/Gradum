package gradum.skill

/** Source language of a dynamically generated text (the server only sends the tag, never localized copy). */
enum class Lang(val wire: String) {
  EN("en");

  companion object {
    fun fromWire(value: String?): Lang =
      entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: EN
  }
}

/**
 * A text value the server wants displayed to the user, abstracted for i18n.
 *
 * wireKind is the stable machine tag that the plugin dispatches on: `key`
 * vs `raw`, and toWire() serializes to the wire payload
 * (JsonUtil.encodeMap-compatible).
 *
 * Key references an i18n bundle entry (`xxx.xxx.xxx=copy`): the server emits
 * bundleKey plus interpolation args, and the plugin resolves the copy. Raw
 * is model-generated (or server-assembled) text with an explicit sourceLang
 * tag so a translating IDE knows what language it is in.
 */
sealed interface L10nText {

  val wireKind: String

  fun toWire(): Map<String, Any>

  data class Key(
    val bundleKey: String,
    val args: List<String> = emptyList()
  ) : L10nText {
    override val wireKind: String get() = "key"
    override fun toWire(): Map<String, Any> = mapOf(
      "args" to args,
      "kind" to wireKind,
      "key" to bundleKey
    )
  }

  data class Raw(
    val text: String,
    val sourceLang: Lang = Lang.EN
  ) : L10nText {
    override val wireKind: String get() = "raw"
    override fun toWire(): Map<String, Any> = mapOf(
      "text" to text,
      "kind" to wireKind,
      "sourceLang" to sourceLang.wire
    )
  }
}

/**
 * Factory for building [L10nText] values inside an ask builder block,
 * exposed as `l10n` so the DSL reads `l10n.key(...)` or `l10n.raw(...)`.
 */
object L10n {
  fun key(bundleKey: String, vararg args: String): L10nText =
    L10nText.Key(bundleKey, args.toList())

  fun raw(text: String, source: Lang = Lang.EN): L10nText =
    L10nText.Raw(text, source)
}

/**
 * Choice option primitives for the ask channel.
 *
 * Meaning holds the stable semantic code for a choice option: the wire
 * carries the semantic wire code, NOT display copy, and the plugin maps
 * semantics (e.g. `ALLOW_ONCE`) to its own localized button label.
 */
object Choice {

  enum class Meaning(val wire: String) {
    ALLOW_ONCE("allow_once"),
    ALLOW_ALWAYS("allow_always"),
    REJECT("reject");
  }
}

/**
 * The outcome of an [AskScope.askInteraction], returned once the user answers.
 *
 * Case reports that the user picked a choice option: id is the choice's
 * stable id (a rendering and answer key), meaning is the authoritative
 * semantic code the caller switches on, so callers never branch on string
 * ids. Text reports that the user submitted free text (`.input` flavor) via
 * value, and Canceled reports that the user dismissed the ask card without
 * choosing.
 */
sealed interface AskResult {

  data class Case(
    val id: String,
    val meaning: Choice.Meaning = Choice.Meaning.REJECT
  ) : AskResult

  data class Text(val value: String) : AskResult

  data object Cancelled : AskResult
}
