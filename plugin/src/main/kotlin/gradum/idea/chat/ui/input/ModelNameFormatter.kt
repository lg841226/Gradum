/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelNameFormatter.kt  2026-07-11 12:30:00 Changed by gwy
 */

package gradum.idea.chat.ui.input

/**
 * Structured display breakdown of a raw model name.
 *
 * Returned by [parseModelName] so the UI can render the parts
 * separately — the main title uses [displayName] (e.g. "Qwen 2.5
 * Coder"), the parameter size is dropped into a blue badge via
 * [parameterSize] (e.g. "7B", "8x7B"), and the quantization is
 * shown as a secondary tag via [quant] (e.g. "Q4_K_M", "AWQ").
 *
 * The split is deliberately lossy on purpose: a raw name like
 * `lmstudio-community/qwen2.5-coder:7b-Q4_K_M` carries way more
 * noise than the user wants to see in a selector row, and the
 * selectors only ever need the three pieces above plus the
 * provider for an icon. Everything else is preserved in [rawName]
 * for tooltips.
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
 * Known families + their human-friendly display names.
 *
 * Lookup key is the lower-cased **family root** after the org,
 * tag, size, quant, and variant suffix have all been stripped
 * (e.g. `qwen2.5-coder:7b` → key `qwen2.5-coder`). The intent is
 * to bucket any size/quant/instruct variant of the same family
 * under one canonical name so a user sees a consistent label
 * regardless of which quantization or parameter count they pick.
 *
 * Why a map and not a regex ladder: the family roots are short,
 * stable, and the list of "blessed" names is small (~150 entries).
 * A regex ladder would either under-match (collapse distinct
 * families like `qwen3-coder` vs `qwen3-vl` into one) or over-match
 * (catch `claude-3-opus` when the user actually wanted
 * `claude-3.5-sonnet`). The map sidesteps both failure modes.
 *
 * Lookup is fuzzy: when a key miss happens, the formatter also
 * tries progressively shorter prefixes (`phi-3-mini-4k` →
 * `phi-3-mini` → `phi-3` → `phi`) and the no-dash form
 * (`llama-3` → `llama3`). This handles the common case where
 * users spell the same model in slightly different ways.
 */
private val modelDisplayNames: Map<String, String> = buildMap {
  // ---- Alibaba (Qwen) ----
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

  // ---- Meta (Llama) ----
  put("llama", "Llama")
  put("llama2", "Llama 2")
  put("llama3", "Llama 3")
  put("llama3.1", "Llama 3.1")
  put("llama3.2", "Llama 3.2")
  put("llama3.3", "Llama 3.3")
  put("llama4", "Llama 4")
  put("codellama", "CodeLlama")

  // ---- DeepSeek ----
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

  // ---- MiniMax (mystery placeholder family, kept for back-compat) ----
  put("minimax-m2", "MiniMax M2")
  put("minimax-m2.1", "MiniMax M2.1")
  put("minimax-m2.5", "MiniMax M2.5")
  put("minimax-m2.7", "MiniMax M2.7")
  put("minimax-m3", "MiniMax M3")

  // ---- Google ----
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

  // ---- Zhipu AI (GLM) ----
  put("glm-4", "GLM 4")
  put("glm-4.5", "GLM 4.5")
  put("glm-4.6", "GLM 4.6")
  put("glm-4.7", "GLM 4.7")
  put("glm-5", "GLM 5")
  put("glm-5.1", "GLM 5.1")
  put("glm-5.2", "GLM 5.2")
  put("glm-z1", "GLM Z1")
  put("glm-z1-rumination", "GLM Z1 Rumination")
  put("chatglm", "ChatGLM")
  put("chatglm2", "ChatGLM 2")
  put("chatglm3", "ChatGLM 3")
  put("chatglm4", "ChatGLM 4")

  // ---- Moonshot (Kimi) ----
  put("kimi-k2", "Kimi K2")
  put("kimi-k2.5", "Kimi K2.5")
  put("kimi-k2.6", "Kimi K2.6")
  put("moonshot-v1", "Moonshot V1")
  put("kimi", "Kimi")

  // ---- Mistral AI ----
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

  // ---- Microsoft (Phi) ----
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

  // ---- 01.AI (Yi) ----
  put("yi", "Yi")
  put("yi-1.5", "Yi 1.5")
  put("yi-6b", "Yi 6B")
  put("yi-9b", "Yi 9B")
  put("yi-34b", "Yi 34B")
  put("yi-coder", "Yi Coder")
  put("yi-large", "Yi Large")
  put("yi-vision", "Yi Vision")

  // ---- BigCode / IBM (StarCoder, Granite) ----
  put("starcoder", "StarCoder")
  put("starcoder2", "StarCoder 2")
  put("starcoderbase", "StarCoder Base")
  put("magicoder", "Magicoder")
  put("granite-code", "Granite Code")
  put("granite-3b-code", "Granite 3B Code")
  put("granite-8b-code", "Granite 8B Code")

  // ---- Cohere (Command) ----
  put("command", "Command")
  put("command-r", "Command R")
  put("command-r-plus", "Command R+")
  put("command-r7b", "Command R7B")
  put("command-light", "Command Light")
  put("command-nightly", "Command Nightly")
  put("c4ai-command-r-plus", "Command R+")
  put("c4ai-command-r", "Command R")

  // ---- TII (Falcon) ----
  put("falcon", "Falcon")
  put("falcon-7b", "Falcon 7B")
  put("falcon-40b", "Falcon 40B")
  put("falcon-180b", "Falcon 180B")
  put("falcon3", "Falcon 3")
  put("falcon-mamba", "Falcon Mamba")

  // ---- Baichuan ----
  put("baichuan", "Baichuan")
  put("baichuan2", "Baichuan 2")
  put("baichuan2-7b", "Baichuan 2 7B")
  put("baichuan2-13b", "Baichuan 2 13B")
  put("baichuan3", "Baichuan 3")

  // ---- Shanghai AI Lab (InternLM) ----
  put("internlm", "InternLM")
  put("internlm2", "InternLM 2")
  put("internlm2.5", "InternLM 2.5")
  put("internlm3", "InternLM 3")

  // ---- Xiaomi (MiMo) ----
  put("mimo", "MiMo")
  put("mimo-7b", "MiMo 7B")

  // ---- xAI (Grok) ----
  put("grok-1", "Grok 1")
  put("grok-1.5", "Grok 1.5")
  put("grok-2", "Grok 2")
  put("grok-2-mini", "Grok 2 Mini")
  put("grok-2-vision", "Grok 2 Vision")
  put("grok-3", "Grok 3")
  put("grok-3-mini", "Grok 3 Mini")
  put("grok-4", "Grok 4")

  // ---- Anthropic (Claude) ----
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
  put("claude-sonnet-4", "Claude Sonnet 4")
  put("claude-opus-4", "Claude Opus 4")
  put("claude-haiku-4", "Claude Haiku 4")

  // ---- OpenAI (GPT) ----
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
  put("o1", "o1")
  put("o1-mini", "o1 Mini")
  put("o1-preview", "o1 Preview")
  put("o1-pro", "o1 Pro")
  put("o3", "o3")
  put("o3-mini", "o3 Mini")
  put("o3-pro", "o3 Pro")
  put("o4-mini", "o4 Mini")
  put("chatgpt-4o-latest", "ChatGPT-4o")
  put("gpt-oss", "GPT-OSS")
  put("gpt-oss-20b", "GPT-OSS 20B")
  put("gpt-oss-120b", "GPT-OSS 120B")
  put("text-embedding-3-small", "Embedding 3 Small")
  put("text-embedding-3-large", "Embedding 3 Large")
  put("text-embedding-nomic-embed-text-v1.5", "Nomic Embed V1.5")

  // ---- Misc but common in the wild ----
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
  // Anthropic
  "claude" to "Anthropic",
  // OpenAI — most specific first
  "gpt-oss" to "OpenAI",
  "chatgpt" to "OpenAI",
  "gpt-3.5" to "OpenAI",
  "gpt-4" to "OpenAI",
  "gpt-5" to "OpenAI",
  "o1" to "OpenAI",
  "o3" to "OpenAI",
  "o4" to "OpenAI",
  "gpt" to "OpenAI",
  // Google — gemini before gemma
  "gemini" to "Google",
  "codegemma" to "Google",
  "gemma" to "Google",
  // Alibaba
  "qwen" to "Alibaba",
  "qwq" to "Alibaba",
  "qvq" to "Alibaba",
  // Meta
  "codellama" to "Meta",
  "llama" to "Meta",
  // DeepSeek
  "deepseek" to "DeepSeek",
  // MiniMax
  "minimax" to "MiniMax",
  // Mistral AI — codestral/pixtral/ministral all win over bare mistral
  "codestral" to "Mistral AI",
  "pixtral" to "Mistral AI",
  "ministral" to "Mistral AI",
  "mixtral" to "Mistral AI",
  "mistral" to "Mistral AI",
  // xAI
  "grok" to "xAI",
  // Xiaomi
  "mimo" to "Xiaomi",
  // Zhipu AI
  "glm" to "Zhipu AI",
  "chatglm" to "Zhipu AI",
  // Moonshot
  "kimi" to "Moonshot",
  "moonshot" to "Moonshot",
  // 01.AI
  "yi" to "01.AI",
  // Cohere — command-r-plus / c4ai-* win over bare command
  "cohere" to "Cohere",
  "c4ai-command" to "Cohere",
  "command-r" to "Cohere",
  "command" to "Cohere",
  // TII
  "falcon" to "TII",
  // Baichuan
  "baichuan" to "Baichuan",
  // Shanghai AI Lab
  "internlm" to "Shanghai AI Lab",
  // BigCode
  "starcoder" to "BigCode",
  "magicoder" to "BigCode",
  // IBM
  "granite" to "IBM",
  // Microsoft
  "phi" to "Microsoft",
  "wizard" to "Microsoft",
  "orca" to "Microsoft",
  // Research collectives
  "nous-hermes" to "Nous Research",
  "openhermes" to "Nous Research",
  "dolphin" to "Eric Hartford",
  // Chinese tech
  "hunyuan" to "Tencent",
  "ernie" to "Baidu",
  "skywork" to "Skywork"
)

/**
 * Recognized parameter-size patterns, in the order the regex
 * tries them. MoE (`8x7B`) must come first because the standalone
 * `7B` pattern would otherwise consume the trailing `7B` and leave
 * a stray `8x` prefix on the family name. Decimals (`0.5B`,
 * `1.5B`) come before the integer `5B`/`7B` patterns for the same
 * reason — `5B` would otherwise clip the decimal off.
 *
 * Unit suffix is `[bm]` only. `k` is reserved (a `4k` token means
 * "4K context" in some model names, not "4K parameters"), and
 * `M` here means "million params" (used by some embedding models).
 */
private val parameterSizePatterns: List<Regex> = listOf(
  Regex("""(\d+)x(\d+(?:\.\d+)?)[bm]""", RegexOption.IGNORE_CASE),
  Regex("""(\d+\.\d+)[bm]""", RegexOption.IGNORE_CASE),
  Regex("""(\d+)[bm]""", RegexOption.IGNORE_CASE)
)

/**
 * Recognized quant tags. Listed in long-form-first order so a
 * model like `Q4_K_M` matches the full pattern, not just `Q4`.
 *
 * Covers the formats actually shipped to the catalog (GGUF, AWQ,
 * GPTQ, EXL2, BNB) plus the standard precision tags
 * (FP16/BF16/FP32/INT4/INT8). The match is anchored to a word
 * boundary so `qwen2.5` doesn't accidentally lose the `2.5`
 * (the leading `q` is not a quant prefix in this regex).
 */
private val quantPatterns: List<Regex> = listOf(
  // Long-form GGUF: Q4_K_M, Q5_K_S, Q4_0, Q4_1, etc. Must
  // come first so Q4_K_M doesn't get matched by the bare Q4 below.
  Regex("""\bq\d+_k_[sml]\b""", RegexOption.IGNORE_CASE),
  Regex("""\bq\d+_k\b""", RegexOption.IGNORE_CASE),
  Regex("""\bq\d+_[01]\b""", RegexOption.IGNORE_CASE),
  Regex("""\bq[2-8]\b""", RegexOption.IGNORE_CASE),
  // Precision: FP16, FP32, BF16, INT4, INT8
  Regex("""\b(?:fp16|fp32|bf16|int4|int8)\b""", RegexOption.IGNORE_CASE),
  // Generic quant methods: AWQ, GPTQ, EXL2, GGUF, GGML, BNB
  Regex("""\b(?:awq|gptq|exl2|gguf|ggml|bnb)\b""", RegexOption.IGNORE_CASE)
)

/**
 * Recognized variant / pipeline suffixes. These are dropped
 * during the family-key lookup because they do not change which
 * display-name bucket the model belongs to.
 *
 * `preview`, `experimental`, and `exp` are deliberately NOT in
 * this set. They are valid model identifiers on their own
 * (`o1-preview`, `claude-3-7-sonnet-experimental`, `gpt-4o-exp`),
 * and the fuzzy lookup already handles them by dropping the
 * suffix and re-checking the map. Stripping them eagerly would
 * collapse `o1-preview` to `o1` and lose the "Preview" label.
 *
 * File-format tags (`gguf`, `ggml`) are also NOT here — the
 * quant extractor handles them. Listing them in both places
 * would make the family-key loop try to strip them twice in
 * different orders, which is wasteful and easy to get wrong.
 */
private val variantSuffixes: Set<String> = setOf(
  "instruct", "chat", "base", "it", "hf",
  "awq", "gptq", "exl2", "fp16", "bf16", "fp32", "cloud"
)

/**
 * Version / date tokens. These are dropped only when they
 * appear at the end of the family key.
 *
 * The pattern is deliberately strict: it requires either an
 * explicit `v` prefix (`v0.1`, `v0.2.1`) or a 3+ segment
 * numeric pattern (`0.2.1`). A bare digit or two-digit number
 * is preserved because it is almost always a model generation
 * (`V3` in `DeepSeek-V3`, `3` and `5` in `claude-3-5-sonnet`)
 * rather than a version stamp. The single-digit case is the
 * most common miss we guard against.
 */
private val versionPatterns: List<Regex> = listOf(
  Regex("""v\d+(\.\d+)+""", RegexOption.IGNORE_CASE),
  Regex("""\d+\.\d+\.\d+.*""")
)

/**
 * Parse a raw model identifier into a [FormattedModelName].
 *
 * Pipeline (run in order; each step feeds the next):
 *
 *  1. **Strip Ollama / LM Studio tag.** `qwen2.5-coder:7b` →
 *     `qwen2.5-coder`. The tag reappears in [quant] / [parameterSize]
 *     if it carries a size or quant we can recognize.
 *  2. **Strip HF / Ollama org prefix.** `lmstudio-community/qwen2.5-7b`
 *     and `TheBloke/Llama-2-7B-Chat-GGUF` both drop the leading
 *     `org/`. The org is not preserved — the selector shows
 *     `Qwen 2.5`, not "via LM Studio community".
 *  3. **Strip date stamps.** `claude-3-5-sonnet-20241022` and
 *     `gpt-4o-2024-08-06` collapse to their model root. Date
 *     detection is `-\d{8}$` (8-digit YYYYMMDD) and
 *     `-\d{4}-\d{2}-\d{2}$` (ISO date). Year-only or month-only
 *     stamps are left alone to avoid mangling `mistral-7b-v0.3`.
 *  4. **Extract [parameterSize].** MoE → decimal → integer.
 *     Tries the post-colon tail first (Ollama tags like `:7b`
 *     are the cleanest source) and falls back to the body.
 *  5. **Extract [quant].** Tries long-form GGUF first
 *     (`Q4_K_M`), then precision tokens (`FP16`), then generic
 *     `AWQ`/`GPTQ`/`EXL2`. The first match wins — a name like
 *     `qwen2.5-7b-instruct-q4_k_m-gguf` returns `Q4_K_M`, the
 *     earlier `GGUF` token is shadowed by the more specific
 *     `Q4_K_M` tag.
 *  6. **Build the family-root lookup key** by repeatedly
 *     stripping trailing variant / version / size / quant
 *     tokens until nothing changes. See [buildLookupKey].
 *  7. **Look up [displayName].** Tries the key, then
 *     progressively shorter prefixes (`phi-3-mini-4k` → `phi-3-mini`),
 *     then the no-dash form (`llama-3` → `llama3`). First hit
 *     wins. Misses fall through to a Title-Case fallback.
 *  8. **Infer [provider].** First keyword match in
 *     [providerDisplayByKeyword]. Order is enforced by the list
 *     order (more specific keywords come first).
 *  9. **Set [isFromCatalog].** `true` when the normalized
 *     name appears in our offline mini-catalog (a small list of
 *     well-known models — the plugin can't directly reach the
 *     server-side `ModelCatalog` over the module boundary, so
 *     this is a local proxy that covers the common case).
 *
 * Examples:
 *  - `qwen2.5-coder:7b` → displayName "Qwen 2.5 Coder",
 *    parameterSize "7B", provider "Alibaba"
 *  - `lmstudio-community/qwen2.5-7b` → displayName "Qwen 2.5",
 *    parameterSize "7B", provider "Alibaba"
 *  - `claude-3-5-sonnet-20241022` → displayName "Claude 3.5
 *    Sonnet", no size (cloud), provider "Anthropic"
 *  - `mixtral-8x7b-instruct-v0.1.Q4_K_M.gguf` → displayName
 *    "Mixtral", parameterSize "8x7B", quant "Q4_K_M", provider
 *    "Mistral AI"
 */
fun parseModelName(raw: String): FormattedModelName {
  val rawTrimmed: String = raw.trim()

  // 1. Strip the Ollama / LM Studio ":tag" suffix. Re-attach
  // its size/quant signal later if we recognize one.
  val beforeColon: String = rawTrimmed.substringBefore(":")
  val afterColon: String = rawTrimmed.substringAfter(":", missingDelimiterValue = "")

  // 2. Strip HF / Ollama org prefix. We use the LAST `/` so
  // paths like `a/b/c/mistral-7b-instruct` (rare but possible
  // when an org nests sub-orgs) drop everything up to the final
  // model name. The org is not preserved — the selector shows
  // `Mistral`, not "via a/b/c".
  val baseName: String = if (beforeColon.contains("/")) beforeColon.substringAfterLast("/") else beforeColon

  // 3. Strip date stamps. Both 8-digit YYYYMMDD and ISO
  // YYYY-MM-DD are handled. Single-digit or short number
  // suffixes are left alone.
  val dateStripped: String = baseName
    .replace(Regex("""-\d{8}$"""), "")
    .replace(Regex("""-\d{4}-\d{2}-\d{2}$"""), "")

  // 4 + 5. Pull the size and quant out of the name. Try the
  // post-colon tail first (Ollama tags like `:7b-q4_k_m` are
  // the cleanest source) and fall back to the body.
  val combined: String = if (afterColon.isNotBlank()) "$dateStripped-$afterColon" else dateStripped
  val parameterSize: String? = extractParameterSize(combined)
  val quant: String? = extractQuant(combined)

  // 6. Build the family-root lookup key. We strip size/quant/
  // variant suffixes off `dateStripped` so all sizes of a family
  // hit the same map entry. The afterColon tail is irrelevant
  // here because it's already been harvested for size/quant.
  val lookupKey: String = buildLookupKey(dateStripped)

  // 7. Try the key and progressively shorter prefixes against
  // the family map. Misses fall through to a Title-Case
  // fallback of the key itself.
  val resolvedDisplay: String = lookupDisplayName(lookupKey)
    ?: if (lookupKey.isEmpty()) rawTrimmed else fallbackDisplay(lookupKey)

  // 8 + 9. Provider + catalog flag.
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
 * Try the no-dash form + the key + progressively shorter
 * prefixes against the family map. Returns the first hit, or
 * `null` if nothing matches.
 *
 * Order matters and is the most subtle bit of this function:
 * we try the no-dash form **first** because it's the most
 * specific — `llama3` is more specific than `llama-3` (which
 * is more specific than `llama`). The first hit wins, so
 * getting this order right is the difference between
 * `Llama 3.1` (correct) and `Llama` (the bare fallback hit).
 *
 * Examples for key `llama-3`:
 *   - `llama3`           (no-dash first, hit → "Llama 3")
 *   - `llama-3`          (skipped)
 *   - `llama`            (skipped)
 *
 * Examples for key `phi-3-mini-4k`:
 *   - `phi3mini4k`       (no-dash first, miss)
 *   - `phi-3-mini-4k`    (full key, miss)
 *   - `phi-3-mini`       (drop suffix, hit → "Phi 3 Mini")
 *   - `phi-3`            (skipped)
 *   - `phi`              (skipped)
 */
private fun lookupDisplayName(key: String): String? {
  if (key.isEmpty()) return null
  // First try the no-dash form (most specific: `llama3` over
  // `llama-3` and `llama`).
  val noDash: String = key.replace("-", "")
  if (noDash != key) {
    modelDisplayNames[noDash]?.let { return it }
  }
  // Then the full key.
  modelDisplayNames[key]?.let { return it }
  // Then progressively drop the last segment.
  var current: String = key
  while (current.contains('-')) {
    current = current.substringBeforeLast('-')
    modelDisplayNames[current]?.let { return it }
  }
  return null
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
 * Build the family-root lookup key. Strips size, quant, version,
 * and common variant suffixes from the end of the name — one
 * at a time, in a loop — until nothing changes. We only strip
 * *trailing* tokens so internal parts of the name (like
 * `coder` in `qwen2.5-coder`) are preserved.
 *
 * Why a loop instead of a fixed pipeline: the order of trailing
 * tokens varies. `qwen2.5-7b-instruct-q4_k_m` ends with quant,
 * `llama-3-8b-instruct` ends with variant, `mixtral-8x7b-instruct-v0.1`
 * ends with version. Any fixed order leaves at least one of
 * these partially stripped. The loop handles all three with
 * the same code.
 */
private fun buildLookupKey(name: String): String {
  var working: String = name
  var changed: Boolean = true
  while (changed) {
    changed = false
    // 1. Trailing variant or version token. Variants are
    // matched against [variantSuffixes]; versions are matched
    // against [versionPatterns] (which require an explicit `v`
    // prefix or a year-length digit count).
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
    // 2. Trailing MoE size (e.g. `-8x7b`). Must come before
    // the plain size pattern so the MoE `7b` part isn't
    // stripped first, leaving a stray `8x` prefix.
    val moeStripped: String = working.replace(
      Regex("""-\d+x\d+(?:\.\d+)?[bm]$""", RegexOption.IGNORE_CASE),
      ""
    )
    if (moeStripped != working) {
      working = moeStripped
      changed = true
      continue
    }
    // 3. Trailing plain size (e.g. `-7b`, `-70b`, `-0.5b`).
    val sizeStripped: String = working.replace(
      Regex("""-\d+(?:\.\d+)?[bm]$""", RegexOption.IGNORE_CASE),
      ""
    )
    if (sizeStripped != working) {
      working = sizeStripped
      changed = true
      continue
    }
    // 4. Trailing quant tag (e.g. `-q4_k_m`, `-awq`). We use
    // the same patterns as [extractQuant] but require the match
    // to end at the last character of the working string, so a
    // quant token in the middle (e.g. `qwen2.5-q4_k_m-7b`) is
    // left alone until the trailing size has been stripped.
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
 * Title-Case-Space-Replace fallback for models that miss
 * [modelDisplayNames]. Hyphens become spaces, the first letter
 * of each word is upper-cased, and the rest stay lower-case.
 *
 * We deliberately do NOT try to be smart here — if the model
 * isn't blessed, the user gets a readable approximation, not
 * a guess. The provider / size / quant fields are still
 * extracted normally, so the badge UI works even on
 * unrecognized models.
 */
private fun fallbackDisplay(key: String): String =
  key.split("-").joinToString(" ") { part ->
    if (part.isEmpty()) "" else part.replaceFirstChar { it.uppercase() }
  }

/**
 * Mini-catalog of well-known model identifiers. Used only to
 * set [FormattedModelName.isFromCatalog].
 *
 * We can't reach the server-side [gradum.discovery.ModelCatalog]
 * from the plugin module (the two modules don't share a classpath),
 * so this set is the plugin's local proxy. Coverage is biased
 * toward the same models the server-side catalog is most likely
 * to have populated — anything in [modelDisplayNames] is also in
 * this set, so the badge UI gets a consistent "this is a
 * recognized model" signal regardless of which side resolved
 * the name.
 */
private val knownModelSet: Set<String> = modelDisplayNames.keys
