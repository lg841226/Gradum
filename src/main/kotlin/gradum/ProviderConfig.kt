/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfig.kt  2026-08-15 22:25:40 Changed by gwy
 */
package gradum

import java.io.File
import java.io.FileInputStream
import java.util.*

/**
 * Reads provider overrides from the shared local environment file
 * `~/.gradum/provider.env`.
 *
 * The file is a public, user-editable artifact shared between the plugin
 * (which reads and edits it) and the embedded server (which only reads it).
 * The server re-reads it on every model discovery, so changing the plugin
 * settings — or hand-editing the file — is picked up without a server
 * restart.
 *
 * Format is plain `KEY=VALUE` lines (Java Properties syntax; `#` comments
 * are allowed). Keys follow the pattern `GRADUM_<PROVIDER>_BASE_URL` and
 * `GRADUM_<PROVIDER>_API_KEY`, where `<PROVIDER>` is the uppercase
 * `configKey` of a provider (see
 * [gradum.ModelIdentity.Discovery.baseKnownServers]): `OLLAMA`,
 * `LM_STUDIO`, `VLLM`, `LOCALAI`, `ZHIPU`, `DEEPSEEK`, `MINIMAX`.
 */
object ProviderConfigStore {

  /** Directory holding the shared config file, overridable via the
   *  `gradum.provider.configDir` system property (useful for tests).
   *  Re-evaluated on every call so the property can change at runtime. */
  private val configDir: File
    get() = System.getProperty("gradum.provider.configDir")?.let { File(it) }
      ?: File(System.getProperty("user.home"), ".gradum")

  /** The shared provider config file, e.g. `~/.gradum/provider.env`. */
  private val configFile: File
    get() = File(configDir, "provider.env")

  /**
   * Read the env file as a [Properties] map. Returns an empty [Properties]
   * when the file is missing or unreadable — discovery then falls back to
   * defaults.
   */
  fun load(): Properties {
    val file: File = configFile
    if (!file.isFile) return Properties()
    return try {
      FileInputStream(file).use { input ->
        Properties().also { it.load(input) }
      }
    } catch (_: Exception) {
      Properties()
    }
  }

  /** env key holding the base URL override for a provider, or null if the
   *  provider has no configKey. */
  fun baseUrlKey(configKey: String?): String? =
    configKey?.let { "GRADUM_${it.uppercase()}_BASE_URL" }

  /** env key holding the API key override for a provider, or null if the
   *  provider has no configKey. */
  fun apiKeyKey(configKey: String?): String? =
    configKey?.let { "GRADUM_${it.uppercase()}_API_KEY" }
}
