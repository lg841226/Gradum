package gradum.client

import gradum.Provider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Provider-neutral dial for how hard the model should reason on a turn.
 *
 * The levels are deliberately abstract: each [ProviderProfile] translates
 * them into the wire vocabulary its backend understands (Ollama's `think`
 * field, OpenAI-style `reasoning_effort`, …), so neither the agent nor the
 * HTTP route ever hard-codes a provider-specific thinking field.
 */
enum class ThinkingLevel {
  OFF,
  LOW,
  MEDIUM,
  HIGH;

  companion object {
    /**
     * Parse a [ThinkingLevel] from a free-form wire string (`low` /
     * `medium` / `high`), returning [default] when [rawValue] is null,
     * blank, or unknown.
     */
    fun fromStringOrDefault(rawValue: String?, default: ThinkingLevel): ThinkingLevel {
      if (rawValue.isNullOrBlank()) return default
      return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: default
    }
  }
}

/**
 * Generation knobs shared by every provider request, resolved from
 * [gradum.AgentConfiguration] once per turn and handed to a
 * [ProviderProfile] for wire encoding.
 */
data class GenerationParams(
  val temperature: Double,
  val topP: Double,
  val contextWindow: Int,
  val maxTokens: Int,

  /**
   * Ollama-only model residency in whole minutes; other providers have no
   * equivalent and leave it null. Encoded by [RequestBodyScope.keepAlive].
   */
  val keepAliveMinutes: Int? = null
)

/**
 * Backing key→value store shared by the request-body and options scopes so
 * one tiny delegate factory can serve both.
 */
internal interface FieldStore {
  val fields: MutableMap<String, Any>
}

/**
 * Delegate factory for a named wire field: reads and writes [key] inside
 * the owning scope's [FieldStore.fields]. [encode] maps the declared value
 * onto its wire shape; returning null skips the field entirely (used by
 * `keep_alive` for the "leave unset" case).
 */
private fun <T> field(key: String, encode: (T) -> Any? = { it }): ReadWriteProperty<FieldStore, T> =
  object : ReadWriteProperty<FieldStore, T> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: FieldStore, property: KProperty<*>): T = thisRef.fields[key] as T

    override fun setValue(thisRef: FieldStore, property: KProperty<*>, value: T) {
      val encodedValue: Any? = encode(value)
      if (encodedValue != null) thisRef.fields[key] = encodedValue
    }
  }

/**
 * Mutable accumulator for one provider request body. The named delegates
 * write straight into [fields] under the provider's wire key; the encoded
 * map is merged into the outgoing payload by the LLM clients.
 */
class RequestBodyScope internal constructor() : FieldStore {

  override val fields: MutableMap<String, Any> = mutableMapOf()

  var temperature: Double by field(key = "temperature")

  var topP: Double by field(key = "top_p")

  /** Token cap; OpenAI-style providers that renamed the field override the key at the client. */
  var maxTokens: Int by field(key = "max_tokens")

  /** OpenAI-style reasoning strength (`reasoning_effort`): `"low"` / `"medium"` / `"high"`. */
  var reasoningEffort: String by field(key = "reasoning_effort")

  /**
   * Ollama `keep_alive` in whole minutes: `0` omits the field so the server
   * default applies, a negative value pins the model forever (wire `-1`),
   * a positive value becomes a duration string (`"5m"`).
   */
  var keepAlive: Int by field(key = "keep_alive") { minutes: Int ->
    when {
      minutes == 0 -> null
      minutes < 0 -> -1
      else -> "${minutes}m"
    }
  }

  /** Native thinking flag / level (Ollama `think`): a Boolean switch or an effort String. */
  fun think(value: Any) {
    fields["think"] = value
  }

  /** Write the nested Ollama `options` object produced by [block]. */
  fun options(block: OptionsScope.() -> Unit) {
    val optionsScope: OptionsScope = OptionsScope().apply(block)
    fields["options"] = optionsScope.fields
  }
}

/**
 * Ollama's nested `options` object: the sampler and window knobs that
 * OpenAI-style providers carry as flat top-level fields.
 */
class OptionsScope internal constructor() : FieldStore {

  override val fields: MutableMap<String, Any> = mutableMapOf()

  var temperature: Double by field(key = "temperature")

  var topP: Double by field(key = "top_p")

  var numCtx: Int by field(key = "num_ctx")

  var numPredict: Int by field(key = "num_predict")
}

/**
 * Declaration side of [providerProfile]: a profile describes (a) how the
 * shared [GenerationParams] map onto the request body, (b) how the neutral
 * [ThinkingLevel] maps onto native thinking fields, and (c) which response
 * delta carries the model's reasoning.
 */
class ProfileScope internal constructor() {

  /**
   * Response delta field the provider streams native reasoning into
   * (`thinking` for Ollama, `reasoning_content` for DeepSeek / MiniMax);
   * null when the provider has no such surface.
   */
  var reasoningDelta: String? = null

  private var requestEncoder: ((GenerationParams) -> Map<String, Any>)? = null
  private var thinkingEncoder: ((ThinkingLevel) -> Map<String, Any>)? = null

  /**
   * Declare the thinking wire fields per [ThinkingLevel]. The block runs
   * against a fresh [RequestBodyScope] for every level; an empty produced
   * field set means "send no thinking field" (OpenAI-style providers at
   * [ThinkingLevel.OFF]).
   */
  fun thinking(block: RequestBodyScope.(ThinkingLevel) -> Unit) {
    thinkingEncoder = { level ->
      val bodyScope = RequestBodyScope()
      bodyScope.block(level)
      bodyScope.fields
    }
  }

  /** Declare the generation-parameter section of the request body. */
  fun body(block: RequestBodyScope.(GenerationParams) -> Unit) {
    requestEncoder = { params ->
      val bodyScope = RequestBodyScope()
      bodyScope.block(params)
      bodyScope.fields
    }
  }

  internal fun build(): ProviderProfile = ProviderProfile(
    requestEncoder = requestEncoder ?: { emptyMap() },
    thinkingEncoder = thinkingEncoder ?: { emptyMap() },
    reasoningDeltaField = reasoningDelta
  )
}

/**
 * Declarative wire-format profile for one [Provider]: the single place that
 * knows how generation knobs and thinking levels are spelled for that
 * backend, replacing the per-client hard-coded request-body branches.
 */
class ProviderProfile internal constructor(
  private val requestEncoder: (GenerationParams) -> Map<String, Any>,
  private val thinkingEncoder: (ThinkingLevel) -> Map<String, Any>,

  /** Response delta field carrying native reasoning, null when none exists. */
  val reasoningDeltaField: String?
) {
  /** Encode the generation knobs into this provider's request-body fields. */
  fun requestFields(params: GenerationParams): Map<String, Any> = requestEncoder(params)

  /** Encode a thinking level into this provider's request-body fields (empty map = no thinking field). */
  fun thinkingFields(level: ThinkingLevel): Map<String, Any> = thinkingEncoder(level)
}

/** Build a [ProviderProfile] from a declarative [ProfileScope] block. */
fun providerProfile(configure: ProfileScope.() -> Unit): ProviderProfile =
  ProfileScope().apply(configure).build()

/**
 * Ollama native `/api/chat` encoding: sampler knobs live in the nested
 * `options` object, thinking is carried by the `think` field (a boolean
 * off switch or an effort level), and reasoning streams back through the
 * `message.thinking` delta.
 */
val OllamaProfile: ProviderProfile = providerProfile {
  reasoningDelta = "thinking"
  thinking { level ->
    when (level) {
      ThinkingLevel.OFF -> think(value = false)
      ThinkingLevel.LOW -> think(value = "low")
      ThinkingLevel.MEDIUM -> think(value = "medium")
      ThinkingLevel.HIGH -> think(value = "high")
    }
  }
  body { params ->
    options {
      temperature = params.temperature
      topP = params.topP
      numCtx = params.contextWindow
      numPredict = params.maxTokens
    }
    params.keepAliveMinutes?.let { minutes -> keepAlive = minutes }
  }
}

/**
 * OpenAI-compatible `/v1/chat/completions` encoding: flat sampler fields,
 * thinking carried by `reasoning_effort` (omitted entirely at
 * [ThinkingLevel.OFF] so non-reasoning providers see a plain request), and
 * reasoning streamed back through the `reasoning_content` delta.
 */
val OpenAiProfile: ProviderProfile = providerProfile {
  reasoningDelta = "reasoning_content"
  thinking { level ->
    when (level) {
      ThinkingLevel.OFF -> Unit
      ThinkingLevel.LOW -> reasoningEffort = "low"
      ThinkingLevel.MEDIUM -> reasoningEffort = "medium"
      ThinkingLevel.HIGH -> reasoningEffort = "high"
    }
  }
  body { params ->
    temperature = params.temperature
    topP = params.topP
    maxTokens = params.maxTokens
  }
}

/** Pick the wire profile matching a [Provider]. */
fun profileFor(provider: Provider): ProviderProfile = when (provider) {
  Provider.OLLAMA -> OllamaProfile
  Provider.OPENAI -> OpenAiProfile
}
