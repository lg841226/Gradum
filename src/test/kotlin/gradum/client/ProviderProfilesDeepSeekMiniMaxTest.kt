package gradum.client

import gradum.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Wire encodings of the DeepSeek and MiniMax profiles: the request-body
 * field shapes and the [ThinkingLevel] translation. Guards the two fields
 * the backends actually differ on: MiniMax's deprecated `max_tokens`
 * (replaced by `max_completion_tokens`) and the MiniMax `thinking` object
 * (`{"thinking": {"type": ...}}`) that rides along with DeepSeek's flat
 * `reasoning_effort` toggle-and-effort parameter.
 */
class ProviderProfilesDeepSeekMiniMaxTest {

  private val sampleParams: GenerationParams = GenerationParams(
    topP = 0.9,
    maxTokens = 1024,
    temperature = 0.7,
    contextWindow = 8192
  )

  @Test
  fun `DeepSeekProfile sends flat OpenAI fields with max_tokens`() {
    val requestFields: Map<String, Any> = DeepSeekProfile.requestFields(sampleParams)

    assertEquals(0.7, requestFields["temperature"])
    assertEquals(0.9, requestFields["top_p"])
    assertEquals(1024, requestFields["max_tokens"])
    assertFalse(requestFields.containsKey("max_completion_tokens"))
    assertFalse(requestFields.containsKey("keep_alive"))
  }

  @Test
  fun `DeepSeekProfile maps thinking levels onto reasoning_effort`() {
    assertEquals("none", DeepSeekProfile.thinkingFields(ThinkingLevel.OFF)["reasoning_effort"])
    assertEquals("low", DeepSeekProfile.thinkingFields(ThinkingLevel.LOW)["reasoning_effort"])
    assertEquals("high", DeepSeekProfile.thinkingFields(ThinkingLevel.MEDIUM)["reasoning_effort"])
    assertEquals("max", DeepSeekProfile.thinkingFields(ThinkingLevel.HIGH)["reasoning_effort"])
    assertEquals("reasoning_content", DeepSeekProfile.reasoningDeltaField)
  }

  @Test
  fun `MiniMaxProfile never sends max_tokens and caps via max_completion_tokens`() {
    val requestFields: Map<String, Any> = MiniMaxProfile.requestFields(sampleParams)

    assertEquals(1024, requestFields["max_completion_tokens"])
    assertFalse(
      requestFields.containsKey("max_tokens"),
      "MiniMax deprecated max_tokens and ignores it — the cap must go out as max_completion_tokens"
    )
    assertEquals(0.7, requestFields["temperature"])
    assertEquals(0.9, requestFields["top_p"])
    assertEquals(true, requestFields["reasoning_split"])
  }

  @Test
  fun `MiniMaxProfile thinking is a binary adaptive-disabled switch nested under thinking`() {
    assertEquals(
      mapOf("thinking" to mapOf("type" to "disabled")),
      MiniMaxProfile.thinkingFields(ThinkingLevel.OFF)
    )
    assertEquals(
      mapOf("thinking" to mapOf("type" to "adaptive")),
      MiniMaxProfile.thinkingFields(ThinkingLevel.LOW)
    )
    assertEquals(
      mapOf("thinking" to mapOf("type" to "adaptive")),
      MiniMaxProfile.thinkingFields(ThinkingLevel.MEDIUM)
    )
    assertEquals(
      mapOf("thinking" to mapOf("type" to "adaptive")),
      MiniMaxProfile.thinkingFields(ThinkingLevel.HIGH)
    )
    assertEquals("reasoning_content", MiniMaxProfile.reasoningDeltaField)
  }

  @Test
  fun `profileFor dispatches the new cloud providers`() {
    assertEquals(DeepSeekProfile, profileFor(Provider.DEEPSEEK))
    assertEquals(MiniMaxProfile, profileFor(Provider.MINIMAX))
  }
}
