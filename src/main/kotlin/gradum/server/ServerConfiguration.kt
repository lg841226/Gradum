/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-08-14 22:29:50 Changed by gwy
 */

package gradum.server

/**
 * Configuration for the Gradum embedded HTTP server.
 *
 * Cloud providers (Zhipu BigModel, future first-class additions) are
 * hard-coded inside [gradum.ModelIdentity] — they are discovered at
 * startup and exposed in the plugin's model selector without any
 * JSON-config / env-var plumbing on the user's side. Users only need
 * to drop their API key into one of the env vars listed in
 * [resolveDefaultApiKeyFromEnv] and the provider shows up.
 */
data class ServerConfiguration(
  val hostAddress: String = DEFAULT_HOST_ADDRESS,
  val portNumber: Int = DEFAULT_PORT_NUMBER,

  /**
   * Server-wide fallback bearer token for hosted providers
   * (Zhipu BigModel, future first-class additions). Used by `/events`
   * when the request body does not include an `apiKey` in its `config`
   * map, so the plugin can stay key-less and the user only has to
   * set this once per machine.
   *
   * Resolved from the first non-blank of:
   * `GRADUM_OPENAI_API_KEY` → `BIGMODEL_API_KEY` → `OPENAI_API_KEY`.
   * Pass `null` to skip the lookup (default), in which case providers
   * without a per-request key fall back to anonymous (works for local
   * Ollama only).
   */
  val defaultApiKey: String? = null,
) {
  companion object {
    /** Default bind host; the single source of truth. */
    const val DEFAULT_HOST_ADDRESS: String = "localhost"

    /** Default bind port; the single source of truth. */
    const val DEFAULT_PORT_NUMBER: Int = 8765

    /**
     * Env-var lookup order for the server-wide fallback API key.
     * First non-blank value wins; precedence matters because
     * `BIGMODEL_API_KEY` is the most specific (Zhipu users tend to
     * have one), so we honor it before the generic OpenAI one.
     *
     * Mirror of the list inside
     * [gradum.ModelIdentity.Discovery.cloudApiKeyEnvCandidates] — keep
     * both in sync so the probe key and the chat fallback key agree.
     */
    private val API_KEY_ENV_CANDIDATES: List<String> = listOf(
      "GRADUM_OPENAI_API_KEY",
      "BIGMODEL_API_KEY",
      "OPENAI_API_KEY",
    )

    /**
     * Resolve the server-wide API key from environment variables.
     * Returns the first non-blank value, or `null` when none is set.
     * Trims whitespace so a stray `export FOO=bar ` still works.
     */
    fun resolveDefaultApiKeyFromEnv(): String? {
      for (envVarName in API_KEY_ENV_CANDIDATES) {
        val rawValue: String? = System.getenv(envVarName)
        val trimmed: String? = rawValue?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) return trimmed
      }
      return null
    }
  }
}
