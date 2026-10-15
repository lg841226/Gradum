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
    temperature = 0.7,
    topP = 0.9,
    contextWindow = 8192,
    maxTokens = 1024,
    keepAliveMinutes = 2
  )

  @Test
  fun `OllamaProfile nests sampler options and maps think per level`() {
    val requestFields: Map<String, Any> = OllamaProfile.requestFields(sampleParams)

    assertEquals(
      mapOf(
        "temperature" to 0.7,
        "top_p" to 0.9,
        "num_ctx" to 8192,
        "num_predict" to 1024
      ),
      requestFields["options"]
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
  fun `profileFor dispatches by provider`() {
    assertEquals(OllamaProfile, profileFor(Provider.OLLAMA))
    assertEquals(OpenAiProfile, profileFor(Provider.OPENAI))
  }

  @Test
  fun `thinking level parsing falls back to the given default`() {
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault(null, ThinkingLevel.MEDIUM))
    assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromStringOrDefault("HIGH", ThinkingLevel.MEDIUM))
    assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromStringOrDefault("bogus", ThinkingLevel.MEDIUM))
  }
}
