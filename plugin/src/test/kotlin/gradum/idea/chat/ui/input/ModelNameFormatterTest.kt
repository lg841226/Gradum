/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelNameFormatterTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.input

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [parseModelName] and [formatModelName].
 *
 * The tests are organized by what they cover, not by file
 * structure. Each block starts with a header comment that names
 * the behavior. The cases are deliberately redundant across
 * shapes (e.g. `qwen2.5-coder:7b` vs `qwen2.5-coder-7b`) so a
 * regression in any one branch shows up immediately as a
 * specific failing case, not a generic "the family map is
 * wrong" failure.
 */
class ModelNameFormatterTest {

  @Test
  fun `formatModelName returns bare family name for Ollama colon-tagged name`() {
    assertEquals(
      "Qwen 2.5 Coder",
      formatModelName("qwen2.5-coder:7b")
    )
  }

  @Test
  fun `formatModelName drops HF org prefix in LM Studio names`() {
    assertEquals(
      "Qwen 2.5",
      formatModelName("lmstudio-community/qwen2.5-7b")
    )
  }

  @Test
  fun `formatModelName handles simple colon-tagged Qwen`() {
    assertEquals(
      "Qwen 2.5",
      formatModelName("qwen2.5:7b")
    )
  }

  @Test
  fun `formatModelName title-cases PascalCase slash input`() {
    assertEquals(
      "DeepSeek V3",
      formatModelName("DeepSeek-V3")
    )
  }

  @Test
  fun `parseModelName resolves GLM family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "glm-4-9b" to "GLM 4",
      "glm-4-9b-chat" to "GLM 4",
      "THUDM/glm-4-9b-chat" to "GLM 4",
      "glm-4.5" to "GLM 4.5",
      "glm-4.7-flash" to "GLM 4.7 Flash",
      "glm-4-9b-chat-128k" to "GLM 4",
      "chatglm-6b" to "ChatGLM",
      "chatglm3-6b" to "ChatGLM 3"
    )
    for ((input, expected) in cases)
      assertEquals(
        "input=$input",
        expected,
        formatModelName(input)
      )
  }

  @Test
  fun `parseModelName detects Zhipu provider for GLM and ChatGLM`() {
    assertEquals(
      "Zhipu AI",
      parseModelName("glm-4-9b-chat").provider
    )
    assertEquals(
      "Zhipu AI",
      parseModelName("chatglm-6b").provider
    )
  }

  @Test
  fun `parseModelName extracts 9B size from glm-4-9b`() {
    val parsed: FormattedModelName = parseModelName("glm-4-9b-chat")
    assertEquals(
      "9B",
      parsed.parameterSize
    )
    assertEquals(
      "GLM 4",
      parsed.displayName
    )
  }


  @Test
  fun `parseModelName inserts dash for dashless family roots`() {
    val cases: List<Pair<String, String>> = listOf(
      "glm4:latest" to "GLM 4",
      "gpt4:latest" to "GPT-4",
      "phi3:medium" to "Phi 3",
      "llama3:8b-instruct-q4_0" to "Llama 3",
      "mistral7b:latest" to "Mistral 7B"
    )
    for ((input, expected) in cases)
      assertEquals(
        "input=$input",
        expected,
        formatModelName(input)
      )
  }

  @Test
  fun `formatModelName normalizes MiniMax family`() {
    assertEquals(
      "MiniMax M2",
      formatModelName("MiniMax-M2")
    )
  }

  @Test
  fun `formatModelName falls back to title-case for unknown families`() {
    assertEquals(
      "Unsupported Xyz",
      formatModelName("unsupported-xyz:70b")
    )
  }

  @Test
  fun `parseModelName resolves Qwen family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "qwen2.5-coder:7b" to "Qwen 2.5 Coder",
      "qwen3-coder:30b" to "Qwen 3 Coder",
      "qwen3-vl:8b" to "Qwen 3 VL",
      "qwen-long" to "Qwen Long",
      "qwq:32b" to "QwQ"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves Llama family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "llama3.1:8b" to "Llama 3.1",
      "llama3.3:70b" to "Llama 3.3",
      "llama4:128x17b" to "Llama 4",
      "codellama:13b" to "CodeLlama"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves DeepSeek family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "deepseek-r1:7b" to "DeepSeek R1",
      "deepseek-r1-distill-llama:8b" to "DeepSeek R1 Distill",
      "deepseek-v3:67b" to "DeepSeek V3",
      "deepseek-coder-v2:16b" to "DeepSeek Coder V2"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves Google family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "gemma3:9b" to "Gemma 3",
      "codegemma:7b" to "CodeGemma",
      "gemini-1.5-pro" to "Gemini 1.5 Pro",
      "gemini-2.5-flash" to "Gemini 2.5 Flash"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves Mistral family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "mistral:7b" to "Mistral",
      "mistral-nemo:12b" to "Mistral Nemo",
      "mistral-large" to "Mistral Large",
      "mixtral:8x7b" to "Mixtral",
      "codestral:22b" to "Codestral",
      "pixtral-large" to "Pixtral Large"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves Microsoft Phi family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "phi-3-mini:4k" to "Phi 3 Mini",
      "phi-3.5-moe" to "Phi 3.5 MoE",
      "phi-4-mini" to "Phi 4 Mini"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves Claude family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "claude-3-opus-20240229" to "Claude 3 Opus",
      "claude-3.5-sonnet-20241022" to "Claude 3.5 Sonnet",
      "claude-3.5-haiku-20241022" to "Claude 3.5 Haiku",
      "claude-3.7-sonnet" to "Claude 3.7 Sonnet",
      "claude-sonnet-4-20250514" to "Claude Sonnet 4",
      "claude-opus-4" to "Claude Opus 4",
      "claude-opus-5.5" to "Claude Opus 5.5",
      "claude-fable-5.1" to "Claude Fable 5.1",
      "claude-mythos-5.1" to "Claude Mythos 5.1"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves GPT family variants`() {
    val cases: List<Pair<String, String>> = listOf(
      "gpt-3.5-turbo" to "GPT-3.5 Turbo",
      "gpt-4-turbo" to "GPT-4 Turbo",
      "gpt-4o" to "GPT-4o",
      "gpt-4o-mini" to "GPT-4o Mini",
      "gpt-4.1" to "GPT-4.1",
      "gpt-4.1-nano" to "GPT-4.1 Nano",
      "gpt-5" to "GPT-5",
      "gpt-5.6" to "GPT-5.6",
      "gpt-5.6-sol" to "GPT-5.6 Sol",
      "gpt-6-astra" to "GPT-6 Astra",
      "gpt-6-sol" to "GPT-6 Sol",
      "gpt-6-luna" to "GPT-6 Luna",
      "gpt-oss:20b" to "ChatGPT 4 Nano",
      "gpt-oss-120b" to "ChatGPT 4"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves o-series reasoning models`() {
    val cases: List<Pair<String, String>> = listOf(
      "o1" to "o1",
      "o1-preview" to "o1 Preview",
      "o1-pro" to "o1 Pro",
      "o3-mini" to "o3 Mini",
      "o4-mini" to "o4 Mini"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }

  @Test
  fun `parseModelName resolves other cloud providers`() {
    val cases: List<Pair<String, String>> = listOf(
      "gemini-2.0-flash" to "Gemini 2.0 Flash",
      "command-r-plus" to "Command R+",
      "command-r" to "Command R",
      "grok-3" to "Grok 3",
      "grok-4" to "Grok 4"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).displayName
      )
    }
  }


  @Test
  fun `parseModelName extracts simple integer sizes`() {
    assertEquals(
      "7B",
      parseModelName("qwen2.5-7b").parameterSize
    )
    assertEquals(
      "70B",
      parseModelName("llama-3-70b").parameterSize
    )
    assertEquals(
      "405B",
      parseModelName("llama-3-405b").parameterSize
    )
  }

  @Test
  fun `parseModelName extracts decimal sizes`() {
    assertEquals(
      "0.5B",
      parseModelName("qwen2.5-0.5b").parameterSize
    )
    assertEquals(
      "1.5B",
      parseModelName("phi-1.5b").parameterSize
    )
    assertNull(parseModelName("minimax-m2.7").parameterSize)
    assertEquals(
      "2.7B",
      parseModelName("minimax-m2.7b").parameterSize
    )
  }

  @Test
  fun `parseModelName extracts MoE sizes with lowercase x`() {
    val result: FormattedModelName = parseModelName("mixtral-8x7b-instruct")
    assertEquals(
      "8x7B",
      result.parameterSize
    )
  }

  @Test
  fun `parseModelName canonicalizes MoE with uppercase X to lowercase x`() {
    val result: FormattedModelName = parseModelName("mixtral-8X7B")
    assertEquals(
      "8x7B",
      result.parameterSize
    )
  }

  @Test
  fun `parseModelName canonicalizes MoE with uppercase B to uppercase B`() {
    val result: FormattedModelName = parseModelName("mixtral-8x7b")
    assertEquals(
      "8x7B",
      result.parameterSize
    )
  }

  @Test
  fun `parseModelName returns null parameter size for cloud models`() {
    assertNull(parseModelName("gpt-4o").parameterSize)
    assertNull(parseModelName("claude-3.5-sonnet").parameterSize)
    assertNull(parseModelName("gemini-1.5-pro").parameterSize)
  }

  @Test
  fun `parseModelName extracts size from afterColon tag`() {
    val result: FormattedModelName = parseModelName("qwen2.5-coder:7b")
    assertEquals(
      "7B",
      result.parameterSize
    )
  }

  @Test
  fun `parseModelName extracts size from combined afterColon-with-quant`() {
    val result: FormattedModelName = parseModelName("qwen2.5-coder:7b-q4_k_m")
    assertEquals(
      "7B",
      result.parameterSize
    )
    assertEquals(
      "Q4_K_M",
      result.quant
    )
  }

  @Test
  fun `parseModelName extracts long-form GGUF quant tags`() {
    val cases: List<Pair<String, String>> = listOf(
      "qwen2.5-7b-instruct-q4_k_m" to "Q4_K_M",
      "llama-3-8b-q5_k_s" to "Q5_K_S",
      "llama-3-8b-q6_k" to "Q6_K",
      "mistral-7b-q8_0" to "Q8_0",
      "llama-3-8b-q4_1" to "Q4_1"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).quant
      )
    }
  }

  @Test
  fun `parseModelName extracts generic quant methods`() {
    val cases: List<Pair<String, String>> = listOf(
      "qwen2.5-7b-awq" to "AWQ",
      "llama-3-8b-gptq" to "GPTQ",
      "qwen2.5-7b-exl2" to "EXL2"
    )
    cases.forEach { (input, expected) ->
      assertEquals(
        "Failed for $input",
        expected,
        parseModelName(input).quant
      )
    }
  }

  @Test
  fun `parseModelName extracts precision tokens`() {
    assertEquals(
      "FP16",
      parseModelName("llama-3-8b-fp16").quant
    )
    assertEquals(
      "BF16",
      parseModelName("llama-3-8b-bf16").quant
    )
    assertEquals(
      "INT4",
      parseModelName("qwen2.5-7b-int4").quant
    )
    assertEquals(
      "INT8",
      parseModelName("qwen2.5-7b-int8").quant
    )
  }

  @Test
  fun `parseModelName extracts file-format quant from HF-style name`() {
    val result: FormattedModelName =
      parseModelName("TheBloke/Llama-2-7B-Chat-GGUF")
    assertEquals(
      "GGUF",
      result.quant
    )
  }

  @Test
  fun `parseModelName prefers long-form GGUF over bare Q4`() {
    val result: FormattedModelName =
      parseModelName("qwen2.5-7b-instruct-q4_k_m")
    assertEquals(
      "Q4_K_M",
      result.quant
    )
  }

  @Test
  fun `parseModelName returns null quant when no quant tag present`() {
    assertNull(parseModelName("qwen2.5-coder:7b").quant)
    assertNull(parseModelName("gpt-4o").quant)
  }

  @Test
  fun `parseModelName strips 8-digit YYYYMMDD date suffix`() {
    assertEquals(
      "Claude 3.5 Sonnet",
      parseModelName("claude-3-5-sonnet-20241022").displayName
    )
    assertEquals(
      "Claude 3 Opus",
      parseModelName("claude-3-opus-20240229").displayName
    )
  }

  @Test
  fun `parseModelName strips ISO YYYY-MM-DD date suffix`() {
    assertEquals(
      "GPT-4o",
      parseModelName("gpt-4o-2024-08-06").displayName
    )
    assertEquals(
      "Claude Sonnet 4",
      parseModelName("claude-sonnet-4-20250514").displayName
    )
  }

  @Test
  fun `parseModelName preserves model version v-tags`() {
    assertEquals(
      "Mistral",
      parseModelName("mistral-7b-instruct-v0.2").displayName
    )
  }

  @Test
  fun `parseModelName preserves single-digit model generations in Claude name`() {
    // `claude-3-5-sonnet` has `3` and `5` as model generations.
    // These must not be stripped as versions.
    val result: FormattedModelName = parseModelName("claude-3-5-sonnet")
    assertEquals(
      "Claude 3.5 Sonnet",
      result.displayName
    )
  }

  @Test
  fun `parseModelName drops LM Studio HF org prefix`() {
    val result: FormattedModelName =
      parseModelName("lmstudio-community/qwen2.5-7b")
    assertEquals(
      "Qwen 2.5",
      result.displayName
    )
    assertEquals(
      "7B",
      result.parameterSize
    )
  }

  @Test
  fun `parseModelName drops TheBloke HF org prefix`() {
    val result: FormattedModelName =
      parseModelName("TheBloke/Llama-2-7B-Chat-GGUF")
    assertEquals(
      "Llama 2",
      result.displayName
    )
    assertEquals(
      "7B",
      result.parameterSize
    )
    assertEquals(
      "GGUF",
      result.quant
    )
  }

  @Test
  fun `parseModelName handles multiple org path segments`() {
    // Some HF paths have more than one slash (e.g.
    // `org/sub-org/model-name`). We strip everything up to the
    // last slash.
    val result: FormattedModelName =
      parseModelName("a/b/c/mistral-7b-instruct")
    assertEquals(
      "Mistral",
      result.displayName
    )
  }

  @Test
  fun `parseModelName strips instruct suffix from family key`() {
    assertEquals(
      "Qwen 2.5",
      parseModelName("qwen2.5-7b-instruct").displayName
    )
  }

  @Test
  fun `parseModelName strips chat suffix from family key`() {
    assertEquals(
      "Llama 2",
      parseModelName("Llama-2-7B-Chat").displayName
    )
  }

  @Test
  fun `parseModelName strips base suffix from family key`() {
    assertEquals(
      "Llama 2",
      parseModelName("Llama-2-7B-Base").displayName
    )
  }

  @Test
  fun `parseModelName strips multiple stacked variant suffixes`() {
    val result: FormattedModelName =
      parseModelName("mistral-7b-instruct-v0.2")
    assertEquals(
      "Mistral",
      result.displayName
    )
  }

  @Test
  fun `parseModelName strips all trailing metadata in mixed order`() {
    assertEquals(
      "Qwen 2.5 Coder",
      parseModelName("qwen2.5-coder-7b-instruct-q4_k_m").displayName
    )
    assertEquals(
      "7B",
      parseModelName("qwen2.5-coder-7b-instruct-q4_k_m").parameterSize
    )
    assertEquals(
      "Q4_K_M",
      parseModelName("qwen2.5-coder-7b-instruct-q4_k_m").quant
    )
  }

  @Test
  fun `parseModelName detects Anthropic provider from Claude family`() {
    assertEquals(
      "Anthropic",
      parseModelName("claude-3.5-sonnet").provider
    )
    assertEquals(
      "Anthropic",
      parseModelName("claude-3-opus-20240229").provider
    )
  }

  @Test
  fun `parseModelName detects OpenAI provider from GPT family`() {
    assertEquals(
      "OpenAI",
      parseModelName("gpt-4o").provider
    )
    assertEquals(
      "OpenAI",
      parseModelName("gpt-4.1-nano").provider
    )
    assertEquals(
      "OpenAI",
      parseModelName("o1-preview").provider
    )
  }

  @Test
  fun `parseModelName detects OpenAI provider from gpt-oss`() {
    assertEquals(
      "OpenAI",
      parseModelName("gpt-oss-120b").provider
    )
  }

  @Test
  fun `parseModelName detects Google provider from Gemini and Gemma`() {
    assertEquals(
      "Google",
      parseModelName("gemini-1.5-pro").provider
    )
    assertEquals(
      "Google",
      parseModelName("gemma3:9b").provider
    )
    assertEquals(
      "Google",
      parseModelName("codegemma:7b").provider
    )
  }

  @Test
  fun `parseModelName detects Alibaba provider from Qwen family`() {
    assertEquals(
      "Alibaba",
      parseModelName("qwen2.5-coder:7b").provider
    )
    assertEquals(
      "Alibaba",
      parseModelName("qwq:32b").provider
    )
  }

  @Test
  fun `parseModelName detects Meta provider from Llama family`() {
    assertEquals(
      "Meta",
      parseModelName("llama3.1:8b").provider
    )
    assertEquals(
      "Meta",
      parseModelName("codellama:13b").provider
    )
  }

  @Test
  fun `parseModelName detects Mistral AI provider with specific keyword first`() {
    assertEquals(
      "Mistral AI",
      parseModelName("codestral:22b").provider
    )
    assertEquals(
      "Mistral AI",
      parseModelName("pixtral-large").provider
    )
    assertEquals(
      "Mistral AI",
      parseModelName("mixtral:8x7b").provider
    )
    assertEquals(
      "Mistral AI",
      parseModelName("mistral:7b").provider
    )
  }

  @Test
  fun `parseModelName detects Cohere provider for Command family`() {
    assertEquals(
      "Cohere",
      parseModelName("command-r-plus").provider
    )
    assertEquals(
      "Cohere",
      parseModelName("command-r").provider
    )
  }

  @Test
  fun `parseModelName returns null provider for unknown families`() {
    assertNull(parseModelName("unknown-xyz:7b").provider)
  }

  @Test
  fun `parseModelName marks known models as in catalog`() {
    assertTrue(parseModelName("qwen2.5-coder:7b").isFromCatalog)
    assertTrue(parseModelName("gpt-4o").isFromCatalog)
    assertTrue(parseModelName("claude-3.5-sonnet").isFromCatalog)
    assertTrue(parseModelName("llama3.1:8b").isFromCatalog)
  }

  @Test
  fun `parseModelName marks unknown models as not in catalog`() {
    assertEquals(
      false,
      parseModelName("totally-unknown-model:7b").isFromCatalog
    )
  }


  @Test
  fun `parseModelName preserves raw name verbatim`() {
    val raw = "lmstudio-community/qwen2.5-coder:7b-q4_k_m"
    assertEquals(
      raw,
      parseModelName(raw).rawName
    )
  }

  @Test
  fun `parseModelName trims whitespace from raw name`() {
    val result: FormattedModelName = parseModelName("  qwen2.5-coder:7b  ")
    assertEquals(
      "qwen2.5-coder:7b",
      result.rawName
    )
  }


  @Test
  fun `parseModelName fuzzy-lookup falls back from phi-3-mini-4k to phi-3-mini`() {
    val result: FormattedModelName = parseModelName("phi-3-mini-4k")
    assertEquals(
      "Phi 3 Mini",
      result.displayName
    )
  }

  @Test
  fun `parseModelName fuzzy-lookup tries no-dash form for llama-3`() {
    val result: FormattedModelName = parseModelName("llama-3-8b-instruct")
    assertEquals(
      "Llama 3",
      result.displayName
    )
  }


  @Test
  fun `parseModelName title-cases unknown model for fallback display`() {
    assertEquals(
      "Unknown Model",
      parseModelName("unknown-model:7b").displayName
    )
    assertEquals(
      "My Unrecognized Model",
      parseModelName("my-unrecognized-model:7b").displayName
    )
  }

  @Test
  fun `parseModelName still extracts size and quant for unknown models`() {
    val result: FormattedModelName = parseModelName("unknown-model:70b-q4_k_m")
    assertEquals(
      "Unknown Model",
      result.displayName
    )
    assertEquals(
      "70B",
      result.parameterSize
    )
    assertEquals(
      "Q4_K_M",
      result.quant
    )
  }


  @Test
  fun `parseModelName handles empty string gracefully`() {
    val result: FormattedModelName = parseModelName("")
    assertEquals(
      "",
      result.displayName
    )
    assertNull(result.parameterSize)
    assertNull(result.quant)
    assertNull(result.provider)
    assertEquals(
      false,
      result.isFromCatalog
    )
    assertEquals(
      "",
      result.rawName
    )
  }

  @Test
  fun `parseModelName handles whitespace-only string`() {
    val result: FormattedModelName = parseModelName("   ")
    assertEquals(
      "",
      result.rawName
    )
  }

  @Test
  fun `parseModelName handles model name with only a size`() {
    val result: FormattedModelName = parseModelName("7b")
    assertEquals(
      "7B",
      result.parameterSize
    )
  }

  @Test
  fun `parseModelName handles model name with trailing dash`() {
    val result: FormattedModelName = parseModelName("qwen2.5-coder-")
    assertNotNull(result.displayName)
  }

  @Test
  fun `parseModelName is case-insensitive for family lookup`() {
    assertEquals(
      "Qwen 2.5",
      parseModelName("QWEN2.5-7B").displayName
    )
    assertEquals(
      "Mistral",
      parseModelName("MISTRAL-7B-INSTRUCT").displayName
    )
  }

  @Test
  fun `parseModelName handles colon with no tag content`() {
    val result: FormattedModelName = parseModelName("qwen2.5-coder:")
    assertEquals(
      "Qwen 2.5 Coder",
      result.displayName
    )
    assertNull(result.parameterSize)
  }
}
