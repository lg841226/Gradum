package gradum.client

import gradum.Provider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Provider-neutral reasoning dial; each [ProviderProfile] translates a level
 * into its backend's wire vocabulary (Ollama `think`, OpenAI `reasoning_effort`).
 */
enum class ThinkingLevel {
  OFF,
  LOW,
  MEDIUM,
  HIGH;

  companion object {
    fun fromStringOrDefault(rawValue: String?, default: ThinkingLevel): ThinkingLevel {
      if (rawValue.isNullOrBlank()) return default
      return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: default
    }
  }
}

/** Generation knobs shared by every provider request, encoded by a [ProviderProfile]. */
data class GenerationParams(
  val temperature: Double,
  val topP: Double,
  val contextWindow: Int,
  val maxTokens: Int,

  /** Ollama-only model residency in minutes; other providers leave it null. */
  val keepAliveMinutes: Int? = null
)

internal interface FieldStore {
  val fields: MutableMap<String, Any>
}

/** Named wire-field delegate; [encode] returning null omits the field (keep_alive unset case). */
private fun <T> field(key: String, encode: (T) -> Any? = { it }): ReadWriteProperty<FieldStore, T> =
  object : ReadWriteProperty<FieldStore, T> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: FieldStore, property: KProperty<*>): T = thisRef.fields[key] as T

    override fun setValue(thisRef: FieldStore, property: KProperty<*>, value: T) {
      val encodedValue: Any? = encode(value)
      if (encodedValue != null) thisRef.fields[key] = encodedValue
    }
  }

/** Mutable accumulator for one provider request body; delegates write provider wire keys. */
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

  /** 0 omits the field, negative pins the model forever, positive becomes "Nm". */
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

/** Ollama's nested `options` object. */
class OptionsScope internal constructor() : FieldStore {

  override val fields: MutableMap<String, Any> = mutableMapOf()

  var temperature: Double by field(key = "temperature")

  var topP: Double by field(key = "top_p")

  var numCtx: Int by field(key = "num_ctx")

  var numPredict: Int by field(key = "num_predict")
}

/** Declaration side of [providerProfile]: request encoding, thinking encoding, reasoning delta. */
class ProfileScope internal constructor() {

  /** Response delta carrying native reasoning; null when the provider has none. */
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

/** Wire-format profile for one [Provider]: the single place spelling out its request dialect. */
class ProviderProfile internal constructor(
  private val requestEncoder: (GenerationParams) -> Map<String, Any>,
  private val thinkingEncoder: (ThinkingLevel) -> Map<String, Any>,
  val reasoningDeltaField: String?
) {
  fun requestFields(params: GenerationParams): Map<String, Any> = requestEncoder(params)

  /** Empty map means "send no thinking field". */
  fun thinkingFields(level: ThinkingLevel): Map<String, Any> = thinkingEncoder(level)
}

fun providerProfile(configure: ProfileScope.() -> Unit): ProviderProfile =
  ProfileScope().apply(configure).build()

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

/** DeepSeek folds `medium` into `high`, so MEDIUM sends the canonical value; OFF must send `none` (thinking is on by default). */
val DeepSeekProfile: ProviderProfile = providerProfile {
  reasoningDelta = "reasoning_content"
  thinking { level ->
    when (level) {
      ThinkingLevel.OFF -> reasoningEffort = "none"
      ThinkingLevel.LOW -> reasoningEffort = "low"
      ThinkingLevel.MEDIUM -> reasoningEffort = "high"
      ThinkingLevel.HIGH -> reasoningEffort = "max"
    }
  }
  body { params ->
    temperature = params.temperature
    topP = params.topP
    maxTokens = params.maxTokens
  }
}

/** MiniMax ignores `max_tokens` and has no effort gradation (`thinking` is a binary switch); `reasoning_split`
 * keeps thinking out of the answer text. */
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

fun profileFor(provider: Provider): ProviderProfile = when (provider) {
  Provider.OLLAMA -> OllamaProfile
  Provider.OPENAI -> OpenAiProfile
  Provider.DEEPSEEK -> DeepSeekProfile
  Provider.MINIMAX -> MiniMaxProfile
}
