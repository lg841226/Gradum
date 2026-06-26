/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelNameFormatter.kt  2026-06-26 23:55:00 Changed by gwy
 */

package gradum.idea.chat.ui.input

private val modelDisplayNames: Map<String, String> = mapOf(
    "qwen2.5" to "Qwen 2.5",
    "qwen2.5-coder" to "Qwen 2.5 Coder",
    "qwen2.5vl" to "Qwen 2.5 VL",
    "qwen3" to "Qwen 3",
    "qwen3.5" to "Qwen 3.5",
    "qwen3.6" to "Qwen 3.6",
    "qwen3-coder" to "Qwen 3 Coder",
    "qwen3-coder-next" to "Qwen 3 Coder Next",
    "qwen3-next" to "Qwen 3 Next",
    "qwen3-vl" to "Qwen 3 VL",
    "llama3" to "Llama 3",
    "llama3.1" to "Llama 3.1",
    "llama3.2" to "Llama 3.2",
    "llama3.3" to "Llama 3.3",
    "llama4" to "Llama 4",
    "deepseek-r1" to "DeepSeek R1",
    "deepseek-v2" to "DeepSeek V2",
    "deepseek-v2.5" to "DeepSeek V2.5",
    "deepseek-v3" to "DeepSeek V3",
    "deepseek-v3.1" to "DeepSeek V3.1",
    "deepseek-v3.2" to "DeepSeek V3.2",
    "deepseek-coder" to "DeepSeek Coder",
    "deepseek-coder-v2" to "DeepSeek Coder V2",
    "minimax-m2" to "MiniMax M2",
    "minimax-m2.1" to "MiniMax M2.1",
    "minimax-m2.5" to "MiniMax M2.5",
    "minimax-m2.7" to "MiniMax M2.7",
    "minimax-m3" to "MiniMax M3",
    "gemma3" to "Gemma 3",
    "gemma4" to "Gemma 4",
    "glm-5" to "GLM 5",
    "glm-5.1" to "GLM 5.1",
    "glm-5.2" to "GLM 5.2",
    "kimi-k2" to "Kimi K2",
    "kimi-k2.5" to "Kimi K2.5",
    "kimi-k2.6" to "Kimi K2.6",
    "gpt-oss" to "ChatGPT 4 Nano",
)

/**
 * Converts a raw model name (e.g. "qwen2.5-coder:cloud") to a display-friendly name.
 * Uses [modelDisplayNames] lookup first, then falls back to title-cased hyphen replacement.
 */
fun formatModelName(raw: String): String {
    val modelBase: String = raw.substringBefore(":")
    modelDisplayNames[modelBase]?.let { return it }
    return modelBase.replace("-", " ").replaceFirstChar { it.uppercase() }
}
