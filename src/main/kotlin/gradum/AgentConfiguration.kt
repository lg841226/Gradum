/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentConfiguration.kt  2026-06-21 07:53:44 Changed by gwy
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
 * Tool surface selection. Local models benefit from a smaller tool list —
 * fewer schemas to attend to, less prompt noise, and clearer intent
 * (a read-only session can't accidentally mutate the project; a
 * single-step session can't fake multi-step planning it can't actually do).
 *
 * - [WRITE] exposes every registered Skill. Use for code generation.
 * - [READ_ONLY] exposes only file inspection, project exploration, and
 *   read-only shell commands (e.g. `cat`, `ls`, `grep`). Use for analysis
 *   and bug-hunting where the LLM should not modify the project.
 * - [SINGLE_STEP] is [WRITE] minus the task-tracking tools
 *   (`to_do` / `finish_to_do_item`). For small local models that cannot
 *   plan reliably — task decomposition is a strong-model skill, and giving
 *   a 7B model a `to_do` tool just produces fake planning the model can't
 *   follow. Let it work step-by-step instead.
 */
enum class ToolMode {
    WRITE,
    READ_ONLY,
    SINGLE_STEP;

    companion object {
        fun fromStringOrDefault(rawValue: String?, default: ToolMode = WRITE): ToolMode {
            if (rawValue.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(rawValue, ignoreCase = true) } ?: default
        }
    }
}

/**
 * Which system prompt to load.
 *
 * - [CLOUD] loads the verbose, philosophy-rich prompt for hosted frontier
 *   models (GPT-4, Claude, Gemini) that can follow complex instructions.
 * - [LOCAL] loads the terse, rule-only prompt for small local models
 *   (7B-14B Ollama / LM Studio) where every token of prompt has real cost
 *   pressure and complex instructions get ignored.
 * - [AUTO] picks based on [Provider] — [Provider.OPENAI] → [CLOUD],
 *   everything else → [LOCAL]. Override explicitly when a local model
 *   is large enough to handle the cloud prompt, or when a hosted model
 *   is running on a tight budget.
 */
enum class PromptVariant {
    CLOUD,
    LOCAL,
    AUTO;

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
    val baseUrl: String = "http://localhost:11434",
    val modelName: String = "minimax-m2.5:cloud",
    val timeoutSeconds: Int = 3000,
    val enableThinking: Boolean = false,
    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
    val contextWindowSize: Int = 8192*2,
    val maxTokensToGenerate: Int = 2048*12,
    val provider: Provider = Provider.OLLAMA,
    val toolMode: ToolMode = ToolMode.WRITE,
    val promptVariant: PromptVariant = PromptVariant.AUTO,
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
