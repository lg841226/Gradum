package gradum.acp

/**
 * Human-readable label for the ACP model picker. ACP option labels are a single
 * string (no badge slot like the plugin's own selector), so a recognized tag
 * parameter size is appended as a trailing token and noise tags (`latest`,
 * quantization) are dropped. Only the label is cosmetic: the caller keeps the
 * raw id as the option's `value` so `session/set_config_option` still round-trips.
 *
 * Deliberately a small local approximation of the plugin's `ModelNameFormatter`
 * (the plugin is a separate module): well-known family casing comes from
 * [MODEL_FAMILY_LABELS], everything else falls back to title case.
 *
 * Examples:
 *   qwen3.5:9b                  -> Qwen 3.5 9B
 *   qwen2.5-coder:7b            -> Qwen 2.5 Coder 7B
 *   deepseek-r1:7b              -> DeepSeek R1 7B
 *   llama3.1:8b-instruct-q4_K_M -> Llama 3.1 8B Instruct
 *   mistral:latest              -> Mistral
 *   org/some-model-name         -> Some Model Name
 */
internal fun modelDisplayLabel(modelName: String): String {
  val baseName: String = modelName.substringBefore(delimiter = ":").substringAfterLast(delimiter = "/")
  val tagName: String = modelName.substringAfter(delimiter = ":", missingDelimiterValue = "")

  val labelParts: MutableList<String> = mutableListOf()
  baseName.split('-', '_').filter { it.isNotBlank() }.forEachIndexed { index, part ->
    labelParts += if (index == 0) familyLabel(part) else wordLabel(part)
  }
  tagName.split('-', '_').filter { it.isNotBlank() }.forEach { part ->
    val sizeLabel: String? = parameterSize(part)
    when {
      sizeLabel != null -> labelParts += sizeLabel
      part.lowercase() in MODEL_VARIANT_WORDS -> labelParts += wordLabel(part)
    }
  }
  return labelParts.joinToString(separator = " ").ifBlank { modelName }
}

/** Family token split into its letters and version: `qwen3.5` -> `Qwen 3.5`. */
private fun familyLabel(part: String): String {
  val familyRoot: String = part.takeWhile { it.isLetter() }.lowercase()
  if (familyRoot.isEmpty()) return wordLabel(part)

  val versionText: String = part.dropWhile { it.isLetter() }
  val familyText: String = MODEL_FAMILY_LABELS[familyRoot]
    ?: familyRoot.replaceFirstChar { it.uppercase() }
  return if (versionText.isEmpty()) familyText else "$familyText $versionText"
}

/** Non-leading token: `coder` -> `Coder`, `r1` -> `R1`, `qwen3` -> `Qwen 3`. */
private fun wordLabel(part: String): String {
  parameterSize(part)?.let { sizeLabel -> return sizeLabel }
  val lowerPart: String = part.lowercase()
  if (lowerPart in MODEL_ACRONYMS) return part.uppercase()

  val letters: String = part.takeWhile { it.isLetter() }
  val digits: String = part.dropWhile { it.isLetter() }
  return when {
    letters.isEmpty() -> digits
    digits.isEmpty() -> letters.replaceFirstChar { it.uppercase() }
    letters.length <= 2 -> letters.uppercase() + digits
    else -> letters.replaceFirstChar { it.uppercase() } + " " + digits
  }
}

/** Canonical size token for `9b` / `8x7b`; null when the token is not a size. */
private fun parameterSize(part: String): String? =
  part.takeIf { PARAMETER_SIZE_PATTERN.matches(it) }
    ?.let { sizeText -> sizeText.dropLast(n = 1) + sizeText.last().uppercaseChar() }

/** Family root -> display casing; the plugin's own selector keeps the full table. */
private val MODEL_FAMILY_LABELS: Map<String, String> = mapOf(
  "qwen" to "Qwen",
  "qwq" to "QwQ",
  "qvq" to "QVQ",
  "llama" to "Llama",
  "codellama" to "CodeLlama",
  "deepseek" to "DeepSeek",
  "mistral" to "Mistral",
  "mixtral" to "Mixtral",
  "codestral" to "Codestral",
  "gemma" to "Gemma",
  "codegemma" to "CodeGemma",
  "phi" to "Phi",
  "glm" to "GLM",
  "chatglm" to "ChatGLM",
  "kimi" to "Kimi",
  "moonshot" to "Moonshot",
  "minimax" to "MiniMax",
  "gpt" to "GPT",
  "claude" to "Claude",
  "gemini" to "Gemini",
  "grok" to "Grok",
  "yi" to "Yi",
  "internlm" to "InternLM",
  "baichuan" to "Baichuan",
  "falcon" to "Falcon",
  "granite" to "Granite",
  "starcoder" to "StarCoder",
  "command" to "Command",
  "hunyuan" to "Hunyuan",
  "nomic" to "Nomic",
  "solar" to "Solar"
)

/** Tag tokens worth keeping next to the family name; everything else is noise. */
private val MODEL_VARIANT_WORDS: Set<String> = setOf("instruct", "chat", "base", "thinking", "vision")

/** Short codes that read better fully upper-cased (`vl`, `it`, `oss`). */
private val MODEL_ACRONYMS: Set<String> = setOf("vl", "it", "hf", "moe", "oss")

private val PARAMETER_SIZE_PATTERN: Regex = Regex(
  pattern = """(?:\d+x)?\d+(?:\.\d+)?[bm]""",
  option = RegexOption.IGNORE_CASE
)
