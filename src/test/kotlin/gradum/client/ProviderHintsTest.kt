/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderHintsTest.kt  2026-08-14 23:30:00 Changed by gwy
 */

package gradum.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderHintsTest {

  @Test
  fun `Default hints use OpenAI-standard field names`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("https://api.openai.com/v1")
    assertEquals("max_tokens", hints.maxTokensFieldName)
    assertNull(hints.thinkingFieldValue, "OpenAI standard has no native thinking field")
    assertNull(hints.reasoningDeltaField)
  }

  @Test
  fun `Zhipu and OpenRouter and local OpenAI-compatible servers all fall back to Default`() {
    // None of these declare a native thinking field; sending one
    // would either be ignored or rejected.
    val zhipu: ProviderHints = ProviderHints.forBaseUrl("https://open.bigmodel.cn/api/coding/paas/v4")
    val openRouter: ProviderHints = ProviderHints.forBaseUrl("https://openrouter.ai/api/v1")
    val lmStudio: ProviderHints = ProviderHints.forBaseUrl("http://localhost:1234")

    for (hints in listOf(zhipu, openRouter, lmStudio)) {
      assertEquals("max_tokens", hints.maxTokensFieldName)
      assertNull(hints.thinkingFieldValue)
      assertNull(hints.reasoningDeltaField)
    }
  }

  @Test
  fun `DeepSeek uses enabled thinking and reasoning_content delta`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("https://api.deepseek.com/v1")
    assertEquals("max_tokens", hints.maxTokensFieldName, "DeepSeek follows OpenAI's max_tokens")
    assertEquals(mapOf("type" to "enabled"), hints.thinkingFieldValue)
    assertEquals("reasoning_content", hints.reasoningDeltaField)
  }

  @Test
  fun `MiniMax uses adaptive thinking and max_completion_tokens`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("https://api.minimaxi.com/v1")
    assertEquals(
      "max_completion_tokens", hints.maxTokensFieldName,
      "MiniMax silently ignores max_tokens — must use Anthropic-style field"
    )
    assertEquals(mapOf("type" to "adaptive"), hints.thinkingFieldValue)
    assertEquals("reasoning_content", hints.reasoningDeltaField)
  }

  @Test
  fun `baseUrl match is case-insensitive and tolerates trailing path`() {
    // A future /v1/models/ → /v1/beta/models move on the same host
    // should not break the hint detection.
    val upper: ProviderHints = ProviderHints.forBaseUrl("https://API.DEEPSEEK.COM/v1")
    val withPath: ProviderHints = ProviderHints.forBaseUrl("https://api.deepseek.com/v1/beta")
    assertEquals(mapOf("type" to "enabled"), upper.thinkingFieldValue)
    assertEquals("reasoning_content", upper.reasoningDeltaField)
    assertEquals(mapOf("type" to "enabled"), withPath.thinkingFieldValue)
  }

  @Test
  fun `empty baseUrl falls back to Default`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("")
    assertEquals(ProviderHints.Default, hints)
  }
}
