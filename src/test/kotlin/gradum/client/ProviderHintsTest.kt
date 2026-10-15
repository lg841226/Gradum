package gradum.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderHintsTest {

  @Test
  fun `Default hints use OpenAI-standard field names`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("https://api.openai.com/v1")
    assertEquals(
      "max_tokens",
      hints.maxTokensFieldName
    )
  }

  @Test
  fun `Zhipu and OpenRouter and local OpenAI-compatible servers all fall back to Default`() {
    // None of these need a quirk; thinking and reasoning deltas live in
    // OpenAiProfile, not in this table.
    val zhipu: ProviderHints = ProviderHints.forBaseUrl("https://open.bigmodel.cn/api/coding/paas/v4")
    val openRouter: ProviderHints = ProviderHints.forBaseUrl("https://openrouter.ai/api/v1")
    val lmStudio: ProviderHints = ProviderHints.forBaseUrl("http://localhost:1234")

    for (hints: ProviderHints in listOf(zhipu, openRouter, lmStudio)) {
      assertEquals(
        "max_tokens",
        hints.maxTokensFieldName
      )
    }
  }

  @Test
  fun `MiniMax renames the token cap field`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("https://api.minimaxi.com/v1")
    assertEquals(
      "max_completion_tokens",
      hints.maxTokensFieldName,
      "MiniMax silently ignores max_tokens — must use Anthropic-style field"
    )
  }

  @Test
  fun `DeepSeek falls back to Default now the thinking quirks live in OpenAiProfile`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("https://api.deepseek.com/v1")
    assertEquals(
      ProviderHints.Default,
      hints
    )
  }

  @Test
  fun `empty baseUrl falls back to Default`() {
    val hints: ProviderHints = ProviderHints.forBaseUrl("")
    assertEquals(
      hints,
      ProviderHints.Default
    )
  }
}
