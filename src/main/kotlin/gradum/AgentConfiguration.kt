/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AgentConfiguration.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum

/**
 * LLM backend selection, parsed from free-form user input.
 */
enum class Provider {
  OLLAMA,
  OPENAI;

  /**
   * Wire-level identifier used in model discovery payloads and the
   * `providerType` field of [gradum.ModelEntry]. Single source of truth
   * for the lowercase provider string — call sites must not hard-code
   * `"ollama"` / `"openai"` literals.
   */
  val wireType: String get() = name.lowercase()

  companion object {
    /**
     * Parse a [Provider] from a free-form string, returning [default]
     * when [rawValue] is null, blank, or unknown.
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
 * - [EDIT] All skills except to_do / finish_to_do_item.
 * For local models that can't plan reliably.
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

    /** Resolve an [AUTO] request to a concrete variant: OPENAI → CLOUD, OLLAMA → LOCAL. */
    fun resolveAuto(provider: Provider): PromptVariant = when (provider) {
      Provider.OPENAI -> CLOUD
      Provider.OLLAMA -> LOCAL
    }
  }
}

data class AgentConfiguration(
  val modelName: String = "",
  val provider: Provider = Provider.OLLAMA,
  val baseUrl: String = DEFAULT_OLLAMA_BASE_URL,

  /**
   * Optional bearer token for OpenAI-compatible providers that require
   * authentication (e.g. Zhipu BigModel, OpenAI, OpenRouter). `null`
   * leaves the `Authorization` header off, which is what the local
   * Ollama server expects.
   *
   * When the request body / plugin UI doesn't supply a key, the server
   * falls back to [gradum.server.ServerConfiguration.defaultApiKey]
   * (resolved from env at startup) so the plugin can stay key-less.
   */
  val apiKey: String? = null,

  /**
   * Path appended to [baseUrl] for chat completions. Default
   * `/v1/chat/completions` matches OpenAI / DeepSeek / OpenRouter;
   * Zhipu BigModel uses `/chat/completions` (no `/v1`) under its
   * `api/coding/paas/v4` sub-domain. Plugin sets this per-provider
   * via the `config` map in the `/events` request body.
   */
  val chatCompletionsPath: String = DEFAULT_CHAT_COMPLETIONS_PATH,

  val toolMode: ToolMode = ToolMode.AGENT,
  val promptVariant: PromptVariant = PromptVariant.AUTO,
  val enableThinking: Boolean = DEFAULT_ENABLE_THINKING,

  val topPValue: Double = DEFAULT_TOP_P,
  val temperatureValue: Double = DEFAULT_TEMPERATURE,

  val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,

  val maxRedLineHits: Int = 3,
  val maxRepeatedResponses: Int = 3,
  val maxRepeatedToolCalls: Int = 5,
  val maxTokensToGenerate: Int = DEFAULT_MAX_TOKENS_TO_GENERATE,

  val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,

  /**
   * Absolute, validated, normalized path to the project the current
   * session is operating on. Resolved by `Routes` from the
   * `projectRoot` field of the HTTP request body, which the plugin
   * populates from `Project.basePath`. The server never infers this
   * from CWD or any other source — only the plugin knows which
   * project is actually open in the IDE.
   */
  val projectRoot: String = "",

  /**
   * Stable conversation id sent by the plugin on every `/events` request
   * of the same chat thread. The `ContextManager` writes this session's
   * context to `<projectRoot>/.gradum/sessions/<sessionId>/context.json`,
   * so switching sessions switches model memory and "New Chat" (a fresh
   * sessionId) genuinely forgets. Null/blank falls back to the legacy
   * `<projectRoot>/.gradum/context.json` for old-clients compatibility.
   */
  val sessionId: String? = null,

  /**
   * When non-null, the agent runs as a focused sub-agent dedicated to
   * this task. The task description is injected into the system prompt,
   * and the agent's conversation history is NOT persisted to disk.
   */
  val taskDescription: String? = null
) {
  companion object {

    const val DEFAULT_TOP_P: Double = GradumConfig.DEFAULT_TOP_P
    const val DEFAULT_TEMPERATURE: Double = GradumConfig.DEFAULT_TEMPERATURE
    const val DEFAULT_TIMEOUT_SECONDS: Int = GradumConfig.AGENT_TIMEOUT_SECONDS
    const val DEFAULT_MAX_TOKENS_TO_GENERATE: Int = GradumConfig.DEFAULT_MAX_TOKENS
    const val DEFAULT_ENABLE_THINKING: Boolean = false
    const val DEFAULT_OLLAMA_BASE_URL: String = GradumConfig.DEFAULT_OLLAMA_BASE_URL

    /**
     * Default chat-completions path. Matches the OpenAI / DeepSeek /
     * OpenRouter wire shape. Providers whose base URL already encodes
     * the version (e.g. Zhipu BigModel `api/coding/paas/v4`) override
     * this via the `config.chatCompletionsPath` plugin field.
     */
    const val DEFAULT_CHAT_COMPLETIONS_PATH: String = GradumConfig.DEFAULT_CHAT_COMPLETIONS_PATH

    /**
     * Default context window size (num_ctx) used by the Ollama backend
     * and as the fallback when the client does not supply a `numCtx`
     * value.  The canonical source of truth is [GradumConfig].
     */
    const val DEFAULT_CONTEXT_WINDOW_SIZE: Int = GradumConfig.DEFAULT_CONTEXT_WINDOW_SIZE
  }
}
