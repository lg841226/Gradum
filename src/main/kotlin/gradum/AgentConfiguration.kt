/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentConfiguration.kt  2026-07-04 22:43:11 Changed by gwy
 */

package gradum

/**
 * LLM backend selection. Replaces the previous free-form `providerName: String`
 * field which forced every consumer to perform string equality checks and
 * silently accepted typos.
 */
enum class Provider {
    OLLAMA,
    OPENAI;

    companion object {
        /**
         * Parse a [Provider] from a free-form string (typically user input
         * from the HTTP `config` map). Returns [default] when [rawValue] is
         * null, blank, or unknown — matching the previous best-effort
         * behavior where unrecognized providers fell back to Ollama.
         */
        fun fromStringOrDefault(rawValue: String?, default: Provider = OLLAMA): Provider {
            if (rawValue.isNullOrBlank())
                return default
            return entries.firstOrNull {
                it.name.equals(rawValue, ignoreCase = true)
            } ?: default
        }
    }
}

/**
 * Tool surface selection.
 *
 * - [AGENT] All skills. Full code generation capability.
 * - [READ_ONLY] Inspect only. No file writes. Use for analysis.
 * - [EDIT] All skills except to_do / finish_to_do_item. For local models that can't plan reliably.
 *   Task tracking wastes tokens; step-by-step works better.
 */
enum class ToolMode {
    AGENT,
    READ_ONLY,
    EDIT;

    companion object {
        private val ALIASES: Map<String, ToolMode> = mapOf(
            "write" to AGENT,
            "single_step" to EDIT,
            "read_only" to READ_ONLY,
        )

        fun fromStringOrDefault(rawValue: String?, default: ToolMode = AGENT): ToolMode {
            if (rawValue.isNullOrBlank()) return default
            val normalized = rawValue.trim().lowercase()
            return ALIASES[normalized]
                ?: entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: default
        }
    }
}

/**
 * System prompt selection.
 *
 * - [CLOUD] Verbose, philosophy-rich prompt for frontier models (GPT-4, Claude).
 * - [LOCAL] Terse, rule-only prompt for small models (7B-14B).
 * - [AUTO] Based on [Provider]: OPENAI → CLOUD, others → LOCAL.
 */
enum class PromptVariant {
    CLOUD, LOCAL, AUTO;

    companion object {
        fun fromStringOrDefault(rawValue: String?, default: PromptVariant = AUTO): PromptVariant {
            if (rawValue.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: default
        }

        /**
         * Resolve an [AUTO] request to a concrete variant based on the
         * configured [provider]. OPENAI → CLOUD, OLLAMA → LOCAL.
         */
        fun resolveAuto(provider: Provider): PromptVariant = when (provider) {
            Provider.OPENAI -> CLOUD
            Provider.OLLAMA -> LOCAL
        }
    }
}

data class AgentConfiguration(
    val provider: Provider = Provider.OLLAMA,
    val modelName: String = "minimax-m2.5:cloud",
    val baseUrl: String = "http://localhost:11434",

    val toolMode: ToolMode = ToolMode.AGENT,
    val promptVariant: PromptVariant = PromptVariant.AUTO,
    val enableThinking: Boolean = false,

    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
    val maxTokensToGenerate: Int = 2048 * 12,
    val contextWindowSize: Int = 8192 * 2,

    val timeoutSeconds: Int = 3000,

    val maxRepeatedResponses: Int = 3,
    val maxRedLineHits: Int = 3,
    val maxRepeatedToolCalls: Int = 5,
    /**
     * Absolute, validated, normalized path to the project the current
     * session is operating on. Resolved by `Routes` from the
     * `projectRoot` field of the HTTP request body, which the plugin
     * populates from `Project.basePath`. The server never infers this
     * from CWD or any other source — only the plugin knows which
     * project is actually open in the IDE.
     */
    val projectRoot: String = "",
)
