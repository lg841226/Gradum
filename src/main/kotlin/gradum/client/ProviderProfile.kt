package gradum.client

import gradum.Provider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Provider-neutral reasoning dial. Each [ProviderProfile] translates a level
 * into its backend's wire vocabulary (Ollama `think`, OpenAI `reasoning_effort`).
 */
enum class ThinkingLevel {
  OFF, LOW, MEDIUM, HIGH;

  companion object {
    fun fromStringOrDefault(rawValue: String?, default: ThinkingLevel): ThinkingLevel {
      if (rawValue.isNullOrBlank()) return default
      return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: default
    }
  }
}

/**
 * Generation knobs shared by every provider request, resolved from
 * [gradum.AgentConfiguration] once per turn and handed to a [ProviderProfile]
 * for wire encoding.
 */
data class GenerationParams(
  val topP: Double,
  val temperature: Double,
  val maxTokens: Int,
  val contextWindow: Int,
  val keepAliveMinutes: Int? = null
)

/**
 * Backing key to value store shared by the request-body and options scopes.
 * Named delegates write straight into [fields] under the provider's wire key;
 * the encoded map is merged into the outgoing payload by the LLM clients.
 */
internal interface FieldStore {
  val fields: MutableMap<String, Any>
}

/**
 * Delegate factory for a named wire field. [encode] maps the declared value
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

class RequestBodyScope internal constructor() : FieldStore {

  override val fields: MutableMap<String, Any> = mutableMapOf()

  var temperature: Double by field(key = "temperature")
  var topP: Double by field(key = "top_p")
  var maxTokens: Int by field(key = "max_tokens")
  var maxCompletionTokens: Int by field(key = "max_completion_tokens")
  var reasoningEffort: String by field(key = "reasoning_effort")

  fun thinkingSwitch(type: String) {
    fields["thinking"] = mapOf("type" to type)
  }

  var reasoningSplit: Boolean by field(key = "reasoning_split")

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

  fun think(value: Any) {
    fields["think"] = value
  }

  fun options(block: OptionsScope.() -> Unit) {
    val optionsScope: OptionsScope = OptionsScope().apply(block)
    fields["options"] = optionsScope.fields
  }
}

class OptionsScope internal constructor() : FieldStore {

  override val fields: MutableMap<String, Any> = mutableMapOf()

  var temperature: Double by field(key = "temperature")
  var topP: Double by field(key = "top_p")
  var numCtx: Int by field(key = "num_ctx")
  var numPredict: Int by field(key = "num_predict")
}

/**
 * Declaration side of [providerProfile]. A profile describes:
 * (a) how [GenerationParams] map onto the request body,
 * (b) how [ThinkingLevel] maps onto native thinking fields,
 * (c) which response delta carries the model's reasoning.
 */
class ProfileScope internal constructor() {

  var reasoningDelta: String? = null

  private var requestEncoder: ((GenerationParams) -> Map<String, Any>)? = null
  private var thinkingEncoder: ((ThinkingLevel) -> Map<String, Any>)? = null

  fun thinking(block: RequestBodyScope.(ThinkingLevel) -> Unit) {
    thinkingEncoder = { level ->
      val bodyScope = RequestBodyScope()
      bodyScope.block(level)
      bodyScope.fields
    }
  }

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
 * Wire-format profile for one [Provider]: the single place that knows how
 * generation knobs and thinking levels are spelled for that backend.
 */
class ProviderProfile internal constructor(
  private val requestEncoder: (GenerationParams) -> Map<String, Any>,
  private val thinkingEncoder: (ThinkingLevel) -> Map<String, Any>,
  val reasoningDeltaField: String?
) {
  fun requestFields(params: GenerationParams): Map<String, Any> = requestEncoder(params)

  fun thinkingFields(level: ThinkingLevel): Map<String, Any> = thinkingEncoder(level)
}

fun providerProfile(configure: ProfileScope.() -> Unit): ProviderProfile =
  ProfileScope().apply(configure).build()

/**
 * Ollama native `/api/chat` encoding: sampler knobs live in the nested
 * `options` object, thinking is carried by the `think` field (a boolean
 * off switch or an effort level), and reasoning streams back through
 * the `message.thinking` delta.
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
 * [ThinkingLevel.OFF] so non-reasoning providers see a plain request),
 * and reasoning streamed back through the `reasoning_content` delta.
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

/**
 * DeepSeek: `reasoning_effort` uses `none/low/high/max` (no `medium`),
 * and thinking is on by default so OFF must explicitly send `none`.
 */
val DeepSeekProfile: ProviderProfile = providerProfile {
  reasoningDelta = "reasoning_content"
  thinking { level ->
    reasoningEffort =
      when (level) {
        ThinkingLevel.OFF -> "none"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "high"
        ThinkingLevel.HIGH -> "max"
      }
  }
  body { params ->
    temperature = params.temperature
    topP = params.topP
    maxTokens = params.maxTokens
  }
}

/**
 * MiniMax: ignores `max_tokens` (uses `max_completion_tokens`), has no
 * effort gradation (`thinking` is a binary switch), and uses
 * `reasoning_split` to keep thinking out of the answer text.
 */
val MiniMaxProfile: ProviderProfile = providerProfile {
  reasoningDelta = "reasoning_content"
  thinking { level ->
    when (level) {
      ThinkingLevel.OFF -> thinkingSwitch(type = "disabled")
      ThinkingLevel.LOW -> thinkingSwitch(type = "adaptive")
      ThinkingLevel.MEDIUM -> thinkingSwitch(type = "adaptive")
      ThinkingLevel.HIGH -> thinkingSwitch(type = "adaptive")
    }
  }
  body { params ->
    temperature = params.temperature
    topP = params.topP
    maxCompletionTokens = params.maxTokens
    reasoningSplit = true
  }
}

fun profileFor(provider: Provider): ProviderProfile =
  when (provider) {
    Provider.OLLAMA -> OllamaProfile
    Provider.OPENAI -> OpenAiProfile
    Provider.DEEPSEEK -> DeepSeekProfile
    Provider.MINIMAX -> MiniMaxProfile
  }
