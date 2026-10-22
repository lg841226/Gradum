package gradum.server

import gradum.AgentConfiguration

/**
 * Configuration for the Gradum embedded HTTP server.
 *
 * Cloud providers (Zhipu BigModel, DeepSeek, MiniMax, future first-class
 * additions) are hard-coded inside [gradum.ModelIdentity]: they are discovered
 * at startup and exposed in the plugin's model selector without any JSON-config
 * or env-var plumbing on the user's side.
 *
 * The chat path uses [defaultApiKey] as a per-machine fallback for `/events`
 * requests whose body does not include an `apiKey` in its `config` map. The
 * lookup chain (see [resolveDefaultApiKeyFromEnv]) covers both the generic
 * OpenAI key and each built-in provider's dedicated env var, so a user who has
 * only set `DEEPSEEK_API_KEY` can still chat with DeepSeek-hosted models
 * without typing the key into the plugin UI. The first non-blank value wins.
 */
data class ServerConfiguration(
  val portNumber: Int = DEFAULT_PORT_NUMBER,
  val hostAddress: String = DEFAULT_HOST_ADDRESS,

  /**
   * Server-wide fallback bearer token for hosted providers.
   * Used by `/events` when request body omits `apiKey`. Lookup order:
   * GRADUM_OPENAI_API_KEY -> BIGMODEL_API_KEY -> DEEPSEEK_API_KEY
   * -> MiniMax_API_KEY -> OPENAI_API_KEY. Null = skip (works for local Ollama).
   */
  val defaultApiKey: String? = null,

  /**
   * Bearer token every HTTP request must present (see ServerAuth).
   * Null = auth disabled (default for tests/embedded); production entry
   * point always supplies the resolved token.
   */
  val authToken: String? = null,

  /** Server-wide default LLM base URL (mirrors AgentConfiguration.DEFAULT_OLLAMA_BASE_URL). */
  val defaultBaseUrl: String = DEFAULT_BASE_URL,

  /** Server-wide default model name; empty = "first discovered available model". */
  val defaultModelName: String = "",

  /** Server-wide default thinking toggle (mirrors AgentConfiguration.DEFAULT_ENABLE_THINKING). */
  val defaultThinkEnabled: Boolean = false,

  /** Server-wide default Ollama keep-alive minutes (mirrors AgentConfiguration.DEFAULT_KEEP_ALIVE_MINUTES). */
  val defaultKeepAliveMinutes: Int = AgentConfiguration.DEFAULT_KEEP_ALIVE_MINUTES,

  /** Per-skill plugin config sections (plugins.<skillName>), carried to each Agent session. */
  val plugins: Map<String, Map<String, Any?>> = emptyMap(),
) {
  companion object {
    const val DEFAULT_HOST_ADDRESS: String = "localhost"
    const val DEFAULT_PORT_NUMBER: Int = 8765
    const val DEFAULT_BASE_URL: String = "http://localhost:11434"

    // Env-var lookup order for server-wide fallback API key.
    // Per-provider vars (BIGMODEL_API_KEY, DEEPSEEK_API_KEY, MiniMax_API_KEY)
    // win over generic OPENAI_API_KEY so users configuring one vendor
    // don't need the generic variable. Order: explicit Gradum key -> per-vendor
    // keys -> generic fallback. Must mirror apiKeyEnvVar in
    // ModelIdentity.Discovery.baseKnownServers so probe and chat agree.
    private val API_KEY_ENV_CANDIDATES: List<String> =
      listOf(
        "OPENAI_API_KEY",
        "MiniMax_API_KEY",
        "BIGMODEL_API_KEY",
        "DEEPSEEK_API_KEY",
        "GRADUM_OPENAI_API_KEY"
      )

    /** Resolve server-wide API key from env vars; first non-blank wins, trimmed. */
    fun resolveDefaultApiKeyFromEnv(): String? {
      for (envVarName: String in API_KEY_ENV_CANDIDATES) {
        val rawValue: String? = System.getenv(envVarName)
        val trimmed: String? = rawValue?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) return trimmed
      }
      return null
    }
  }
}