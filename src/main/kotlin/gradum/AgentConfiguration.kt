/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentConfiguration.kt  2026-06-20 Created by gwy
 */

package gradum

/**
 * LLM backend selection. Replaces the previous free-form `providerName: String`
 * field which forced every consumer to perform string equality checks and
 * silently accepted typos.
 */
enum class Provider {
    OLLAMA,
    OPENAI,
    ;

    companion object {
        /**
         * Parse a [Provider] from a free-form string (typically user input
         * from the HTTP `config` map). Returns [default] when [rawValue] is
         * null, blank, or unknown — matching the previous best-effort
         * behaviour where unrecognised providers fell back to Ollama.
         */
        fun fromStringOrDefault(rawValue: String?, default: Provider = OLLAMA): Provider {
            if (rawValue.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: default
        }
    }
}

data class AgentConfiguration(
    val baseUrl: String = "http://localhost:11434",
    val modelName: String = "minimax-m2.5:cloud",
    val timeoutSeconds: Int = 3000,
    val enableThinking: Boolean = false,
    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
    val contextWindowSize: Int = 4096,
    val maxTokensToGenerate: Int = 24576,
    val provider: Provider = Provider.OLLAMA,
)
