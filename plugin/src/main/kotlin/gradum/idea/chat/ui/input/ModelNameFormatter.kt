/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelNameFormatter.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.ui.input

/**
 * Structured display breakdown of a raw model name.
 *
 * Returned by [parseModelName] so the UI can render parts separately:
 * [displayName] for the title, [parameterSize] for a badge (e.g. "7B"),
 * [quant] for a secondary tag (e.g. "Q4_K_M"), [provider] for an icon.
 * The split is deliberately lossy — noise is dropped; [rawName] preserves the original.
 */
data class FormattedModelName(
  /** Human-friendly family + variant name, e.g. "Qwen 2.5 Coder". */
  val displayName: String,
  /** Parameter count badge, e.g. "7B", "70B", "8x7B". `null` for size-less cloud models. */
  val parameterSize: String?,
  /** Quantization tag, e.g. "Q4_K_M", "AWQ". `null` if not specified. */
  val quant: String?,
  /** Provider display name, e.g. "Alibaba", "Anthropic", "OpenAI". `null` if unknown. */
  val provider: String?,
  /** `true` when the (normalized) name is in our offline mini-catalog of well-known models. */
  val isFromCatalog: Boolean,
  /** The original raw string, kept verbatim for tooltips. */
  val rawName: String
)

/**
 * Known families → human-friendly display names.
 * Lookup key is the lower-cased family root after stripping org, tag, size, quant, variant.
 * Fuzzy: key miss triggers progressive prefix shortening (`phi-3-mini-4k` → `phi-3-mini` → `phi-3` → `phi`)
 * and no-dash fallback (`llama-3` → `llama3`).
 *
 * Map (not regex ladder) because family roots are short, stable, and ~150 entries;
 * a regex would either under- or over-match.
 */
private val modelDisplayNames: Map<String, String> = buildMap {
  put("qwen", "Qwen")
  put("qwen2", "Qwen 2")
  put("qwen2.5", "Qwen 2.5")
  put("qwen2.5-coder", "Qwen 2.5 Coder")
  put("qwen2.5vl", "Qwen 2.5 VL")
  put("qwen2-vl", "Qwen 2 VL")
  put("qwen2.5-vl", "Qwen 2.5 VL")
  put("qwen3", "Qwen 3")
  put("qwen3.5", "Qwen 3.5")
  put("qwen3.6", "Qwen 3.6")
  put("qwen3-coder", "Qwen 3 Coder")
  put("qwen3-coder-next", "Qwen 3 Coder Next")
  put("qwen3-next", "Qwen 3 Next")
  put("qwen3-vl", "Qwen 3 VL")
  put("qwen3-max", "Qwen 3 Max")
  put("qwen-long", "Qwen Long")
  put("qwq", "QwQ")
  put("qvq", "QVQ")
  put("llama", "Llama")
  put("llama2", "Llama 2")
  put("llama3", "Llama 3")
  put("llama3.1", "Llama 3.1")
  put("llama3.2", "Llama 3.2")
  put("llama3.3", "Llama 3.3")
  put("llama4", "Llama 4")
  put("codellama", "CodeLlama")
  put("deepseek", "DeepSeek")
  put("deepseek-r1", "DeepSeek R1")
  put("deepseek-r1-lite", "DeepSeek R1 Lite")
  put("deepseek-r1-distill", "DeepSeek R1 Distill")
  put("deepseek-v2", "DeepSeek V2")
  put("deepseek-v2.5", "DeepSeek V2.5")
  put("deepseek-v2-lite", "DeepSeek V2 Lite")
  put("deepseek-v3", "DeepSeek V3")
  put("deepseek-v3.1", "DeepSeek V3.1")
  put("deepseek-v3.2", "DeepSeek V3.2")
  put("deepseek-coder", "DeepSeek Coder")
  put("deepseek-coder-v2", "DeepSeek Coder V2")
  put("deepseek-coder-v2.5", "DeepSeek Coder V2.5")
  put("minimax-m2", "MiniMax M2")
  put("minimax-m2.1", "MiniMax M2.1")
  put("minimax-m2.5", "MiniMax M2.5")
  put("minimax-m2.7", "MiniMax M2.7")
  put("minimax-m3", "MiniMax M3")
  put("gemma", "Gemma")
  put("gemma2", "Gemma 2")
  put("gemma3", "Gemma 3")
  put("gemma4", "Gemma 4")
  put("codegemma", "CodeGemma")
  put("gemini-1.5-pro", "Gemini 1.5 Pro")
  put("gemini-1.5-flash", "Gemini 1.5 Flash")
  put("gemini-1.5-flash-8b", "Gemini 1.5 Flash 8B")
  put("gemini-2.0-flash", "Gemini 2.0 Flash")
  put("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite")
  put("gemini-2.0-pro", "Gemini 2.0 Pro")
  put("gemini-2.5-flash", "Gemini 2.5 Flash")
  put("gemini-2.5-pro", "Gemini 2.5 Pro")
  put("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite")
  put("gemini-3-pro", "Gemini 3 Pro")
  put("glm-4", "GLM 4")
  put("glm-4.5", "GLM 4.5")
  put("glm-4.6", "GLM 4.6")
  put("glm-4.7", "GLM 4.7")
  put("glm-4.7-flash", "GLM 4.7 Flash")
  put("glm-5", "GLM 5")
  put("glm-5.1", "GLM 5.1")
  put("glm-5.2", "GLM 5.2")
  put("glm-z1", "GLM Z1")
  put("glm-z1-rumination", "GLM Z1 Rumination")
  put("chatglm", "ChatGLM")
  put("chatglm2", "ChatGLM 2")
  put("chatglm3", "ChatGLM 3")
  put("chatglm4", "ChatGLM 4")
  put("kimi-k2", "Kimi K2")
  put("kimi-k2.5", "Kimi K2.5")
  put("kimi-k2.6", "Kimi K2.6")
  put("kimi-k2.7", "Kimi K2.7")
  put("kimi-k2.7-thinking", "Kimi K2.7 Thinking")
  put("kimi-k3", "Kimi K3")
  put("kimi-k3-mini", "Kimi K3 Mini")
  put("kimi-k3-thinking", "Kimi K3 Thinking")
  put("moonshot-v1", "Moonshot V1")
  put("moonshot-v2", "Moonshot V2")
  put("moonshot-v2-thinking", "Moonshot V2 Thinking")
  put("kimi", "Kimi")
  put("mistral", "Mistral")
  put("mistral-7b", "Mistral 7B")
  put("mistral-nemo", "Mistral Nemo")
  put("mistral-small", "Mistral Small")
  put("mistral-medium", "Mistral Medium")
  put("mistral-large", "Mistral Large")
  put("mistral-large-2", "Mistral Large 2")
  put("mistral-large-latest", "Mistral Large")
  put("mistral-small-latest", "Mistral Small")
  put("mistral-tiny", "Mistral Tiny")
  put("mixtral", "Mixtral")
  put("mixtral-8x7b", "Mixtral 8x7B")
  put("mixtral-8x22b", "Mixtral 8x22B")
  put("codestral", "Codestral")
  put("codestral-mamba", "Codestral Mamba")
  put("codestral-22b", "Codestral 22B")
  put("pixtral", "Pixtral")
  put("pixtral-large", "Pixtral Large")
  put("ministral", "Ministral")
  put("ministral-3b", "Ministral 3B")
  put("ministral-8b", "Ministral 8B")
  put("phi", "Phi")
  put("phi-2", "Phi 2")
  put("phi-3", "Phi 3")
  put("phi-3-mini", "Phi 3 Mini")
  put("phi-3-small", "Phi 3 Small")
  put("phi-3-medium", "Phi 3 Medium")
  put("phi-3.5-mini", "Phi 3.5 Mini")
  put("phi-3.5-moe", "Phi 3.5 MoE")
  put("phi-4", "Phi 4")
  put("phi-4-mini", "Phi 4 Mini")
  put("phi-4-multimodal", "Phi 4 Multimodal")
  put("wizardlm", "WizardLM")
  put("wizardcoder", "WizardCoder")
  put("orca-2", "Orca 2")
  put("yi", "Yi")
  put("yi-1.5", "Yi 1.5")
  put("yi-6b", "Yi 6B")
  put("yi-9b", "Yi 9B")
  put("yi-34b", "Yi 34B")
  put("yi-coder", "Yi Coder")
  put("yi-large", "Yi Large")
  put("yi-vision", "Yi Vision")
  put("starcoder", "StarCoder")
  put("starcoder2", "StarCoder 2")
  put("starcoderbase", "StarCoder Base")
  put("magicoder", "Magicoder")
  put("granite-code", "Granite Code")
  put("granite-3b-code", "Granite 3B Code")
  put("granite-8b-code", "Granite 8B Code")
  put("command", "Command")
  put("command-r", "Command R")
  put("command-r-plus", "Command R+")
  put("command-r7b", "Command R7B")
  put("command-light", "Command Light")
  put("command-nightly", "Command Nightly")
  put("c4ai-command-r-plus", "Command R+")
  put("c4ai-command-r", "Command R")

  put("falcon", "Falcon")
  put("falcon-7b", "Falcon 7B")
  put("falcon-40b", "Falcon 40B")
  put("falcon-180b", "Falcon 180B")
  put("falcon3", "Falcon 3")
  put("falcon-mamba", "Falcon Mamba")

  put("baichuan", "Baichuan")
  put("baichuan2", "Baichuan 2")
  put("baichuan2-7b", "Baichuan 2 7B")
  put("baichuan2-13b", "Baichuan 2 13B")
  put("baichuan3", "Baichuan 3")

  put("internlm", "InternLM")
  put("internlm2", "InternLM 2")
  put("internlm2.5", "InternLM 2.5")
  put("internlm3", "InternLM 3")

  put("mimo", "MiMo")
  put("mimo-7b", "MiMo 7B")

  put("grok-1", "Grok 1")
  put("grok-1.5", "Grok 1.5")
  put("grok-2", "Grok 2")
  put("grok-2-mini", "Grok 2 Mini")
  put("grok-2-vision", "Grok 2 Vision")
  put("grok-3", "Grok 3")
  put("grok-3-mini", "Grok 3 Mini")
  put("grok-4", "Grok 4")

  put("claude-3-opus", "Claude 3 Opus")
  put("claude-3-sonnet", "Claude 3 Sonnet")
  put("claude-3-haiku", "Claude 3 Haiku")
  put("claude-3.5-sonnet", "Claude 3.5 Sonnet")
  put("claude-3-5-sonnet", "Claude 3.5 Sonnet")
  put("claude-3.5-haiku", "Claude 3.5 Haiku")
  put("claude-3-5-haiku", "Claude 3.5 Haiku")
  put("claude-3.7-sonnet", "Claude 3.7 Sonnet")
  put("claude-3-7-sonnet", "Claude 3.7 Sonnet")
  put("claude-3.5-opus", "Claude 3.5 Opus")
  put("claude-3-5-opus", "Claude 3.5 Opus")
  put("claude-4-opus", "Claude 4 Opus")
  put("claude-4-sonnet", "Claude 4 Sonnet")
  put("claude-4.5-sonnet", "Claude 4.5 Sonnet")
  put("claude-4.5-opus", "Claude 4.5 Opus")
  put("claude-4.5-haiku", "Claude 4.5 Haiku")
  put("claude-4-5-opus", "Claude 4.5 Opus")
  put("claude-4-5-sonnet", "Claude 4.5 Sonnet")
  put("claude-4-5-haiku", "Claude 4.5 Haiku")
  put("claude-sonnet-4", "Claude Sonnet 4")
  put("claude-opus-4", "Claude Opus 4")
  put("claude-haiku-4", "Claude Haiku 4")
  put("claude-sonnet-4.5", "Claude Sonnet 4.5")
  put("claude-opus-4.5", "Claude Opus 4.5")
  put("claude-haiku-4.5", "Claude Haiku 4.5")
  put("claude-5-opus", "Claude 5 Opus")
  put("claude-5-sonnet", "Claude 5 Sonnet")
  put("claude-5-haiku", "Claude 5 Haiku")
  put("claude-opus-5", "Claude Opus 5")
  put("claude-sonnet-5", "Claude Sonnet 5")
  put("claude-haiku-5", "Claude Haiku 5")
  put("claude-fable-5", "Claude Fable 5")

  put("gpt-3.5", "GPT-3.5")
  put("gpt-3.5-turbo", "GPT-3.5 Turbo")
  put("gpt-4", "GPT-4")
  put("gpt-4-turbo", "GPT-4 Turbo")
  put("gpt-4o", "GPT-4o")
  put("gpt-4o-mini", "GPT-4o Mini")
  put("gpt-4.1", "GPT-4.1")
  put("gpt-4.1-mini", "GPT-4.1 Mini")
  put("gpt-4.1-nano", "GPT-4.1 Nano")
  put("gpt-5", "GPT-5")
  put("gpt-5-mini", "GPT-5 Mini")
  put("gpt-5-nano", "GPT-5 Nano")
  put("gpt-5-codex", "GPT-5 Codex")
  put("gpt-5.1", "GPT-5.1")
  put("gpt-5.1-mini", "GPT-5.1 Mini")
  put("gpt-5.1-pro", "GPT-5.1 Pro")
  put("gpt-5.1-codex", "GPT-5.1 Codex")
  // ChatGPT 5.6 — OpenAI's 3-tier release. Stays separate from `gpt-5.6`
  // because "ChatGPT 5.6" is the consumer product name vs the API-facing model.
  put("chatgpt-5.6", "ChatGPT 5.6")
  put("chatgpt-5.6-mini", "ChatGPT 5.6 Mini")
  put("chatgpt-5.6-nano", "ChatGPT 5.6 Nano")
  put("o1", "o1")
  put("o1-mini", "o1 Mini")
  put("o1-preview", "o1 Preview")
  put("o1-pro", "o1 Pro")
  put("o3", "o3")
  put("o3-mini", "o3 Mini")
  put("o3-pro", "o3 Pro")
  put("o4-mini", "o4 Mini")
  put("o5", "o5")
  put("o5-mini", "o5 Mini")
  put("o5-pro", "o5 Pro")
  put("chatgpt-4o-latest", "ChatGPT-4o")
  // GPT-OSS — OpenAI's open-weights distilled line.
  // 20b presents itself as "ChatGPT 4 Nano", 120b as "ChatGPT 4"
  // (per system prompt, not the closed-source flagship).
  put("gpt-oss", "ChatGPT 4")
  put("gpt-oss-20b", "ChatGPT 4 Nano")
  put("gpt-oss-120b", "ChatGPT 4")
  put("text-embedding-3-small", "Embedding 3 Small")
  put("text-embedding-3-large", "Embedding 3 Large")
  put("text-embedding-nomic-embed-text-v1.5", "Nomic Embed V1.5")

  put("nous-hermes", "Nous Hermes")
  put("openhermes", "OpenHermes")
  put("dolphin", "Dolphin")
  put("dolphin-mixtral", "Dolphin Mixtral")
  put("solar", "Solar")
  put("nanbeige", "Nanbeige")
  put("numina-math", "NuminaMath")
  put("skywork", "Skywork")
  put("hunyuan", "Hunyuan")
  put("hunyuan-pro", "Hunyuan Pro")
  put("ernie", "ERNIE")
  put("ernie-4", "ERNIE 4")
}

/**
 * Provider display names, in priority order. The first keyword
 * to match (substring, lowercased) wins. Order matters: more
 * specific keywords (e.g. `gpt-oss`, `command-r`) are listed
 * before their generic parents (`gpt`, `command`) so a `gpt-oss-20b`
 * resolves to OpenAI and a `command-r-plus` resolves to Cohere
 * (not "OpenAI via command-r" or similar nonsense).
 */
private val providerDisplayByKeyword: List<Pair<String, String>> = listOf(
  "claude" to "Anthropic",
  "gpt-oss" to "OpenAI",
  "chatgpt" to "OpenAI",
  "gpt-3.5" to "OpenAI",
  "gpt-4" to "OpenAI",
  "gpt-5" to "OpenAI",
  "o1" to "OpenAI",
  "o3" to "OpenAI",
  "o4" to "OpenAI",
  "gpt" to "OpenAI",
  "gemini" to "Google",
  "codegemma" to "Google",
  "gemma" to "Google",
  "qwen" to "Alibaba",
  "qwq" to "Alibaba",
  "qvq" to "Alibaba",
  "codellama" to "Meta",
  "llama" to "Meta",
  "deepseek" to "DeepSeek",
  "minimax" to "MiniMax",
  "codestral" to "Mistral AI",
  "pixtral" to "Mistral AI",
  "ministral" to "Mistral AI",
  "mixtral" to "Mistral AI",
  "mistral" to "Mistral AI",
  "grok" to "xAI",
  "mimo" to "Xiaomi",
  "glm" to "Zhipu AI",
  "chatglm" to "Zhipu AI",
  "kimi" to "Moonshot",
  "moonshot" to "Moonshot",
  "yi" to "01.AI",
  "cohere" to "Cohere",
  "c4ai-command" to "Cohere",
  "command-r" to "Cohere",
  "command" to "Cohere",
  "falcon" to "TII",
  "baichuan" to "Baichuan",
  "internlm" to "Shanghai AI Lab",
  "starcoder" to "BigCode",
  "magicoder" to "BigCode",
  "granite" to "IBM",
  "phi" to "Microsoft",
  "wizard" to "Microsoft",
  "orca" to "Microsoft",
  "nous-hermes" to "Nous Research",
  "openhermes" to "Nous Research",
  "dolphin" to "Eric Hartford",
  "hunyuan" to "Tencent",
  "ernie" to "Baidu",
  "skywork" to "Skywork"
)

/**
 * Recognized parameter-size patterns. MoE (`8x7B`) must come before standalone `7B`
 * so the trailing `7B` in `8x7B` is consumed first. Decimals (`0.5B`) before integer
 * patterns for the same reason.
 * Unit is `[bm]` only — `k` is reserved for context size, `M` for million-param models.
 */
private val parameterSizePatterns: List<Regex> = listOf(
  Regex("""(\d+)x(\d+(?:\.\d+)?)[bm]""", RegexOption.IGNORE_CASE),
  Regex("""(\d+\.\d+)[bm]""", RegexOption.IGNORE_CASE),
  Regex("""(\d+)[bm]""", RegexOption.IGNORE_CASE)
)

/**
 * Recognized quant tags, tried in long-form-first order. Covers GGUF (`Q4_K_M`), AWQ,
 * GPTQ, EXL2, BNB, and precision tags (FP16/BF16/INT4/INT8).
 * Anchored to word boundary so `qwen2.5` doesn't lose the `2.5`.
 */
private val quantPatterns: List<Regex> = listOf(
  Regex("""\bq\d+_k_[sml]\b""", RegexOption.IGNORE_CASE),
  Regex("""\bq\d+_k\b""", RegexOption.IGNORE_CASE),
  Regex("""\bq\d+_[01]\b""", RegexOption.IGNORE_CASE),
  Regex("""\bq[2-8]\b""", RegexOption.IGNORE_CASE),
  Regex("""\b(?:fp16|fp32|bf16|int4|int8)\b""", RegexOption.IGNORE_CASE),
  Regex("""\b(?:awq|gptq|exl2|gguf|ggml|bnb)\b""", RegexOption.IGNORE_CASE)
)

/**
 * Recognized variant / pipeline suffixes that are dropped during family-key lookup.
 * `preview`, `experimental`, `exp` are NOT here — they are valid identifiers
 * (`o1-preview`, `gpt-4o-exp`) and the fuzzy lookup handles them by dropping
 * the suffix and re-checking. File-format tags (`gguf`, `ggml`) are also NOT
 * here — quant extraction handles them.
 */
private val variantSuffixes: Set<String> = setOf(
  "instruct", "chat", "base", "it", "hf",
  "awq", "gptq", "exl2", "fp16", "bf16", "fp32", "cloud"
)

/**
 * Version/date tokens dropped when at the end of the family key.
 * Requires either `v` prefix (`v0.1`) or 3+ segment numeric pattern (`0.2.1`).
 * Bare digits preserved — they are usually model generations (`V3`, `3`, `5`).
 */
private val versionPatterns: List<Regex> = listOf(
  Regex("""v\d+(\.\d+)+""", RegexOption.IGNORE_CASE),
  Regex("""\d+\.\d+\.\d+.*""")
)

/**
 * Parse a raw model identifier into a [FormattedModelName].
 *
 * Pipeline: strip Ollama/LM Studio tag → strip org prefix → strip date stamps → extract
 * [parameterSize] (MoE → decimal → integer) → extract [quant] (long-form GGUF first) → build
 * family-root lookup key → look up [displayName] (exact → no-dash → dashed-insert → prefix drop)
 * → infer [provider] → set [isFromCatalog].
 */
fun parseModelName(raw: String): FormattedModelName {
  val rawTrimmed: String = raw.trim()

  val beforeColon: String = rawTrimmed.substringBefore(":")
  val afterColon: String = rawTrimmed.substringAfter(":", missingDelimiterValue = "")

  // `Mistral`, not "via a/b/c".
  val baseName: String = if (beforeColon.contains("/")) beforeColon.substringAfterLast("/") else beforeColon

  val dateStripped: String = baseName
    .replace(Regex("""-\d{8}$"""), "")
    .replace(Regex("""-\d{4}-\d{2}-\d{2}$"""), "")

  val combined: String = if (afterColon.isNotBlank()) "$dateStripped-$afterColon" else dateStripped
  val parameterSize: String? = extractParameterSize(combined)
  val quant: String? = extractQuant(combined)

  val lookupKey: String = buildLookupKey(dateStripped)

  val resolvedDisplay: String = resolveGlmDisplay(lookupKey)
    ?: lookupDisplayNameWithSize(lookupKey, parameterSize)
    ?: if (lookupKey.isEmpty()) rawTrimmed else fallbackDisplay(lookupKey)

  val lowerName: String = rawTrimmed.lowercase()
  val provider: String? = providerDisplayByKeyword
    .firstOrNull { lowerName.contains(it.first) }
    ?.second
  val isFromCatalog: Boolean = lookupKey in knownModelSet

  return FormattedModelName(
    displayName = resolvedDisplay,
    parameterSize = parameterSize,
    quant = quant,
    provider = provider,
    isFromCatalog = isFromCatalog,
    rawName = rawTrimmed
  )
}

/**
 * Backward-compatible string-only formatter. Returns the
 * [FormattedModelName.displayName] of a [parseModelName] call.
 *
 * Kept as a thin wrapper (rather than a duplicate of the logic)
 * so the four existing call sites — [ModelSelectorBar] (3×) and
 * [AssistantChatBubble] (1×) — can keep using the `String`
 * return type until the badge UI lands.
 */
fun formatModelName(raw: String): String = parseModelName(raw).displayName

/**
 * Look up the display name in [modelDisplayNames]. Tries in order: literal key → no-dash form
 * (`llama-3` → `llama3`) → dash-inserted form (`glm4` → `glm-4`) → drop last segment
 * (`phi-3-mini-4k` → `phi-3-mini` → ...). First hit wins. Returns `null` on no match.
 *
 * Order: "first specific, then general". Step 3 (dash-inserted) exists for Ollama-style names
 * (`glm4:latest` → `glm-4`); the alpha↔digit boundary insert handles `gpt4` → `gpt-4`, `phi3` → `phi-3`.
 */
private fun lookupDisplayName(key: String): String? {
  if (key.isEmpty()) return null
  modelDisplayNames[key]?.let { return it }
  if ("-" in key) {
    val noDash: String = key.replace("-", "")
    if (noDash != key) {
      modelDisplayNames[noDash]?.let { return it }
    }
  }
  if ("-" !in key) {
    val dashInserted: String = key
      .replace(Regex("(?<=[a-z])(?=\\d)"), "-")
    if (dashInserted != key) {
      modelDisplayNames[dashInserted]?.let { return it }
    }
    var current: String = dashInserted
    while (current.contains('-')) {
      current = current.substringBeforeLast('-')
      modelDisplayNames[current]?.let { return it }
    }
    return null
  }
  var current: String = key
  while (current.contains('-')) {
    current = current.substringBeforeLast('-')
    modelDisplayNames[current]?.let { return it }
  }
  return null
}

/**
 * Variant of [lookupDisplayName] that tries a size-qualified key (e.g. `gpt-oss-20b`)
 * before falling through to the bare key. Needed because [buildLookupKey] operates on the
 * base name only — the colon tag's size is lost if not re-appended here.
 *
 * The size-qualified entry wins only when the bare entry ALSO exists AND the size-specific
 * displayName is NOT simply `bareDisplay + " " + size`. This rejects cases where the suffix
 * just appends the parameter count (which the badge already shows) and accepts cases
 * where the suffix carries independent semantic content (distillation tier name, e.g. "Nano").
 */
private fun lookupDisplayNameWithSize(key: String, parameterSize: String?): String? {
  if (parameterSize != null) {
    val sizeQualified = "$key-${parameterSize.lowercase()}"
    val sizeQualifiedDisplay: String? = modelDisplayNames[sizeQualified]
    val bareDisplay: String? = modelDisplayNames[key]
    if (sizeQualifiedDisplay != null && bareDisplay != null &&
      sizeQualifiedDisplay != "$bareDisplay $parameterSize"
    ) {
      return sizeQualifiedDisplay
    }
  }
  return lookupDisplayName(key)
}

/**
 * Extract the parameter size from a model name. Returns a
 * canonical `NB` form (or `NxMB` for MoE) or `null` if the
 * name doesn't carry one (cloud models, embeddings).
 *
 * Tries each pattern in [parameterSizePatterns] in order, taking
 * the **first** match in the input string.
 */
private fun extractParameterSize(name: String): String? {
  for (pattern in parameterSizePatterns) {
    val match: MatchResult? = pattern.find(name)
    if (match != null) {
      val raw: String = match.value
      // Canonicalize: lowercase, then re-format the unit
      // suffix. `8X7B` and `8x7b` both become `8x7B`.
      return raw.lowercase().replace(Regex("""(\d+)x(\d+(?:\.\d+)?)([bm])""")) {
        val count: String = it.groupValues[1]
        val perExpert: String = it.groupValues[2]
        val unit: String = it.groupValues[3]
        "${count}x${perExpert}${unit.uppercase()}"
      }.let { value ->
        // Uppercase the trailing unit (B or M). We don't touch
        // the leading digits so `0.5b` stays `0.5B`.
        if (value.last() in "bm") value.dropLast(1) + value.last().uppercaseChar() else value
      }
    }
  }
  return null
}

/**
 * Extract the quant tag from a model name. Returns the matched
 * substring in its canonical form (uppercase letters, lowercase
 * digits+separators) or `null` if none is recognized.
 *
 * Examples:
 *  - `qwen2.5-7b-instruct-q4_k_m` → `Q4_K_M`
 *  - `Mistral-7B-Instruct-v0.2.AWQ` → `AWQ`
 *  - `llama-3-8b-instruct-4bit` → (not matched — 4bit isn't in
 *    our list; would only show in rawName)
 */
private fun extractQuant(name: String): String? {
  for (pattern in quantPatterns) {
    val match: MatchResult? = pattern.find(name)
    if (match != null) {
      val raw: String = match.value
      // GGUF-style names need the underscore casing preserved
      // (`Q4_K_M`), other names get full uppercase. We split on
      // underscores and uppercase each part for safety.
      return raw.split("_").joinToString("_") { segment ->
        segment.uppercase()
      }
    }
  }
  return null
}

/**
 * Build the family-root lookup key by repeatedly stripping trailing size, quant, version,
 * and variant tokens until nothing changes. Loop (not fixed pipeline) because the order
 * of trailing tokens varies — any fixed order leaves at least one case partially stripped.
 */
private fun buildLookupKey(name: String): String {
  var working: String = name
  var changed = true
  while (changed) {
    changed = false
    val lastDash: Int = working.lastIndexOf('-')
    if (lastDash > 0) {
      val tail: String = working.substring(lastDash + 1)
      val tailLower: String = tail.lowercase()
      val isVariant: Boolean = tailLower in variantSuffixes
      val isVersion: Boolean = versionPatterns.any { it.matches(tailLower) }
      if (isVariant || isVersion) {
        working = working.substring(0, lastDash)
        changed = true
        continue
      }
    }
    val moeStripped: String = working.replace(
      Regex("""-\d+x\d+(?:\.\d+)?[bm]$""", RegexOption.IGNORE_CASE),
      ""
    )
    if (moeStripped != working) {
      working = moeStripped
      changed = true
      continue
    }

    val sizeStripped: String = working.replace(
      Regex("""-\d+(?:\.\d+)?[bm]$""", RegexOption.IGNORE_CASE),
      ""
    )
    if (sizeStripped != working) {
      working = sizeStripped
      changed = true
      continue
    }
    for (pattern in quantPatterns) {
      val match: MatchResult? = pattern.find(working)
      if (match != null && match.range.last == working.lastIndex) {
        working = working.substring(0, match.range.first).trimEnd('-')
        changed = true
        break
      }
    }
  }
  return working.lowercase()
}

/**
 * Title-Case-Space-Replace fallback for unrecognized models.
 * Hyphens → spaces, first letter uppercase, rest lowercase.
 * Deliberately simple — if the model isn't in the map, the user
 * gets a readable approximation, not a guess.
 */
private fun fallbackDisplay(key: String): String =
  key.split("-").joinToString(" ") { part ->
    if (part.isEmpty()) "" else part.replaceFirstChar { it.uppercase() }
  }

/**
 * GLM (Zhipu BigModel) family formatter. The chat UI pulls the live
 * model list from the server's `/models` endpoint, which lists every
 * variant the provider offers (`glm-4.5`, `glm-4.5-air`, `glm-4.6`,
 * `glm-5-turbo`, `glm-5.1`, ...). [modelDisplayNames] only has ~150
 * hand-curated entries and would never keep up, so we synthesize a
 * readable display name for any `glm-*` key by splitting on `-`,
 * title-casing each segment, and joining with a space.
 *
 * Crucially this keeps the variant suffix visible in the selector:
 * `glm-4.5-air` → `GLM 4.5 Air`, `glm-5-turbo` → `GLM 5 Turbo`.
 * The old `fallbackDisplay` would render both as `Glm 4.5` / `Glm 5`,
 * which made `glm-4.5` (Ollama) and `glm-4.5-air` (Zhipu) look
 * identical to the user.
 */
private val OLLAMA_TRAILING_SUFFIXES: Regex = Regex(
  // Ollama-style trailing markers — `9b`, `70b`, `chat`, `instruct`,
  // `128k` context length, etc. Crucially does NOT match `air` or
  // `turbo`, which are the real Zhipu GLM-4.5 / GLM-5 variant names
  // and must survive into the display.
  """-(?:\d+(?:\.\d+)?[bm]|chat|instruct|base|it|hf|cloud|\d+k)$""",
  RegexOption.IGNORE_CASE
)

private fun resolveGlmDisplay(key: String): String? {
  if (!key.startsWith("glm")) return null

  // Strip trailing Ollama-style suffixes first. Without this
  // `glm-4-9b-chat-128k` would render as the noisy
  // `GLM 4 9b Chat 128k` — buildLookupKey only handles 9b/70b
  // (size) and chat/instruct (variant), so a 128k context suffix
  // would survive and end up as a tail word in the display name.
  // We deliberately do NOT touch `air` / `turbo` because those are
  // the real Zhipu GLM-4.5 / GLM-5 variant names.
  var working: String = key
  while (true) {
    val next: String = OLLAMA_TRAILING_SUFFIXES.replace(working, "")
    if (next == working) break
    working = next
  }

  // Handle both dash-separated (`glm-4.5-air`) and dashless
  // (`glm4`) forms. For the dashless form we split on the
  // alpha↔digit boundary so the family root and the version
  // land in separate words: `glm4` → `GLM 4`, not `GLM4`.
  val parts: List<String> = working.split("-").flatMap { part ->
    val lower: String = part.lowercase()
    if (lower == "glm") listOf("glm")
    else if (lower.startsWith("glm") && lower.length > 3) {
      // "glm4" → ["glm", "4"], "glm12" → ["glm", "12"]
      listOf("glm", lower.removePrefix("glm"))
    } else {
      listOf(part)
    }
  }
  return parts.joinToString(" ") { part ->
    if (part.equals("glm", ignoreCase = true)) "GLM"
    else part.replaceFirstChar { it.uppercase() }
  }
}

/**
 * Mini-catalog of well-known model identifiers. Used only for [FormattedModelName.isFromCatalog].
 * Local proxy — can't reach server-side `ModelCatalog` from the plugin module.
 */
private val knownModelSet: Set<String> = modelDisplayNames.keys
