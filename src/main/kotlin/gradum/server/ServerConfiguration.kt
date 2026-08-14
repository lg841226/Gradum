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
 * Cloud providers (Zhipu BigModel, DeepSeek, MiniMax, future
 * first-class additions) are hard-coded inside
 * [gradum.ModelIdentity] — they are discovered at startup and
 * exposed in the plugin's model selector without any JSON-config /
 * env-var plumbing on the user's side.
 *
 * The chat path uses [defaultApiKey] as a per-machine fallback for
 * `/events` requests whose body does not include an `apiKey` in
 * its `config` map. The lookup chain (see
 * [resolveDefaultApiKeyFromEnv]) covers both the generic OpenAI
 * key and each built-in provider's dedicated env var, so a user
 * who has only set `DEEPSEEK_API_KEY` can still chat with
 * DeepSeek-hosted models without typing the key into the plugin
 * UI. The first non-blank value wins.
 */
data class ServerConfiguration(
  val hostAddress: String = DEFAULT_HOST_ADDRESS,
  val portNumber: Int = DEFAULT_PORT_NUMBER,

  /**
   * Server-wide fallback bearer token for hosted providers
   * (Zhipu BigModel, DeepSeek, MiniMax, future first-class
   * additions). Used by `/events` when the request body does
   * not include an `apiKey` in its `config` map, so the plugin
   * can stay key-less and the user only has to set this once
   * per machine.
   *
   * Resolved from the first non-blank of: `GRADUM_OPENAI_API_KEY`
   * → `BIGMODEL_API_KEY` → `DEEPSEEK_API_KEY` → `MiniMax_API_KEY`
   * → `OPENAI_API_KEY`. Pass `null` to skip the lookup (default),
   * in which case providers without a per-request key fall back
   * to anonymous (works for local Ollama only).
   *
   * Note: this is a single value shared across providers. A user
   * with two providers configured (e.g. DeepSeek + Zhipu) should
   * supply keys per-request via the plugin UI to avoid having the
   * wrong vendor's key attached to a request.
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
     *
     * **Per-provider env vars** (`BIGMODEL_API_KEY`,
     * `DEEPSEEK_API_KEY`, `MiniMax_API_KEY`) win over the generic
     * `OPENAI_API_KEY` so that users who only configure one
     * vendor don't need to set the generic variable. The order
     * is: explicit Gradum key → per-vendor keys → generic
     * fallback. Adding a new provider here is a one-liner that
     * should mirror the `apiKeyEnvVar` declared in
     * [gradum.ModelIdentity.Discovery.baseKnownServers] so probe
     * and chat agree on the same key.
     */
    private val API_KEY_ENV_CANDIDATES: List<String> = listOf(
      "GRADUM_OPENAI_API_KEY",
      "BIGMODEL_API_KEY",
      "DEEPSEEK_API_KEY",
      "MiniMax_API_KEY",
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
