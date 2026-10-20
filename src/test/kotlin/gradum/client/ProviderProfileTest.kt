package gradum.client

import gradum.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Encodings of the two wire profiles: the request-body field shapes and the
 * [ThinkingLevel] translation, per provider. Guards against regressions in
 * the keys the backends actually read.
 */
class ProviderProfileTest {

  private val sampleParams: GenerationParams = GenerationParams(
    topP = 0.9,
    temperature = 0.7,
    maxTokens = 1024,
    contextWindow = 8192,
    keepAliveMinutes = 2
  )

  @Test
  fun `OllamaProfile nests sampler options and maps think per level`() {
    val requestFields: Map<String, Any> = OllamaProfile.requestFields(sampleParams)

    assertEquals(
      mapOf(
        "temperature" to 0.7,
        "top_p" to 0.9,
        "num_ctx" to 8192
      ),
      requestFields["options"],
      "num_predict must stay unset so a configured max_tokens is never forwarded"
    )
    assertEquals(
      "2m",
      requestFields["keep_alive"],
      "positive minutes become a duration string"
    )

    assertEquals(false, OllamaProfile.thinkingFields(ThinkingLevel.OFF)["think"])
    assertEquals("low", OllamaProfile.thinkingFields(ThinkingLevel.LOW)["think"])
    assertEquals("medium", OllamaProfile.thinkingFields(ThinkingLevel.MEDIUM)["think"])
    assertEquals("high", OllamaProfile.thinkingFields(ThinkingLevel.HIGH)["think"])
    assertEquals("thinking", OllamaProfile.reasoningDeltaField)
  }

  @Test
  fun `OllamaProfile omits keep_alive at zero and pins forever when negative`() {
    val blankFields: Map<String, Any> =
      OllamaProfile.requestFields(sampleParams.copy(keepAliveMinutes = 0))
    assertFalse(blankFields.containsKey("keep_alive"))

    val pinnedFields: Map<String, Any> =
      OllamaProfile.requestFields(sampleParams.copy(keepAliveMinutes = -1))
    assertEquals(-1, pinnedFields["keep_alive"])
  }

  @Test
  fun `OpenAiProfile sends flat fields and reasoning_effort only above off`() {
    val requestFields: Map<String, Any> = OpenAiProfile.requestFields(sampleParams)

    assertEquals(0.7, requestFields["temperature"])
    assertEquals(0.9, requestFields["top_p"])
    assertEquals(1024, requestFields["max_tokens"])
    assertFalse(requestFields.containsKey("keep_alive"))

    assertTrue(
      OpenAiProfile.thinkingFields(ThinkingLevel.OFF).isEmpty(),
      "non-reasoning providers must see a plain request"
    )
    assertEquals("low", OpenAiProfile.thinkingFields(ThinkingLevel.LOW)["reasoning_effort"])
    assertEquals("medium", OpenAiProfile.thinkingFields(ThinkingLevel.MEDIUM)["reasoning_effort"])
    assertEquals("high", OpenAiProfile.thinkingFields(ThinkingLevel.HIGH)["reasoning_effort"])
    assertEquals("reasoning_content", OpenAiProfile.reasoningDeltaField)
  }

  @Test
  fun `OllamaProfile encodes think from probed capabilities`() {
    val levelOnlyCapabilities = OllamaModelCapabilities(
      supportsThinking = true,
      supportsThinkingLevels = true,
      supportsTools = true,
      supportsVision = false,
      contextLength = 128_000,
      parameterSizeB = 20.0
    )
    // gpt-oss-style: graded depth -> string levels on every non-OFF level.
    assertEquals("low", OllamaProfile.thinkingFields(ThinkingLevel.LOW, levelOnlyCapabilities)["think"])
    assertEquals("medium", OllamaProfile.thinkingFields(ThinkingLevel.MEDIUM, levelOnlyCapabilities)["think"])
    assertEquals("high", OllamaProfile.thinkingFields(ThinkingLevel.HIGH, levelOnlyCapabilities)["think"])
    assertEquals(false, OllamaProfile.thinkingFields(ThinkingLevel.OFF, levelOnlyCapabilities)["think"])

    val binaryCapabilities: OllamaModelCapabilities = levelOnlyCapabilities.copy(supportsThinkingLevels = false)
    // Thinking model without graded depth -> boolean switch, level ignored.
    assertEquals(true, OllamaProfile.thinkingFields(ThinkingLevel.LOW, binaryCapabilities)["think"])
    assertEquals(true, OllamaProfile.thinkingFields(ThinkingLevel.HIGH, binaryCapabilities)["think"])

    val noThinkingCapabilities: OllamaModelCapabilities = levelOnlyCapabilities.copy(supportsThinking = false)
    // Model cannot think -> the field must not appear at all.
    assertTrue(OllamaProfile.thinkingFields(ThinkingLevel.LOW, noThinkingCapabilities).isEmpty())
    assertTrue(OllamaProfile.thinkingFields(ThinkingLevel.HIGH, noThinkingCapabilities).isEmpty())

    // Null capabilities keep the legacy string-level behavior.
    assertEquals("medium", OllamaProfile.thinkingFields(ThinkingLevel.MEDIUM, null)["think"])
  }

  @Test
  fun `LmStudioProfile mirrors OpenAI body and never sends a thinking param`() {
    val requestFields: Map<String, Any> = LmStudioProfile.requestFields(sampleParams)

    assertEquals(0.7, requestFields["temperature"])
    assertEquals(0.9, requestFields["top_p"])
    assertEquals(1024, requestFields["max_tokens"])
    assertFalse(requestFields.containsKey("keep_alive"))

    // LM Studio ignores reasoning_effort on the OpenAI-compatible surface, so
    // no level (including OFF) may add a field to the request.
    for (level in ThinkingLevel.entries) {
      assertTrue(
        LmStudioProfile.thinkingFields(level).isEmpty(),
        "LM Studio must not send a thinking parameter for $level"
      )
    }

    // No declared reasoning field: the client falls back across the known
    // key names (`reasoning_content`, then `reasoning`).
    assertEquals(null, LmStudioProfile.reasoningDeltaField)
  }

  @Test
  fun `profileFor dispatches by provider`() {
    assertEquals(OllamaProfile, profileFor(Provider.OLLAMA))
    assertEquals(OpenAiProfile, profileFor(Provider.OPENAI))
    assertEquals(DeepSeekProfile, profileFor(Provider.DEEPSEEK))
    assertEquals(MiniMaxProfile, profileFor(Provider.MINIMAX))
    assertEquals(LmStudioProfile, profileFor(Provider.LMSTUDIO))
  }

  @Test
  fun `thinking level parsing falls back to the given default`() {
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault(null, ThinkingLevel.MEDIUM))
    assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromStringOrDefault("HIGH", ThinkingLevel.MEDIUM))
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault("bogus", ThinkingLevel.MEDIUM))
  }
}
