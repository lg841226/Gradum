/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfigFile.kt  2026-08-15  Changed by gwy
 */
package gradum.idea.provider

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Reads and edits the shared local environment file `~/.gradum/provider.env`.
 *
 * This file is the single source of truth between the plugin (read /
 * write) and the embedded Gradum server (read). The server re-reads it
 * on every model discovery, so plugin edits are picked up without a
 * restart. Format is plain Java Properties (`KEY=VALUE`), with keys
 * `GRADUM_<PROVIDER>_BASE_URL` / `GRADUM_<PROVIDER>_API_KEY`.
 */
object ProviderConfigFile {

  private val configDir: File
    get() = System.getProperty("gradum.provider.configDir")?.let { File(it) }
      ?: File(System.getProperty("user.home"), ".gradum")

  private val configFile: File
    get() = File(configDir, "provider.env")

  /** Read the full provider map from disk. Empty when missing/unreadable. */
  fun load(): Properties {
    val file: File = configFile
    if (!file.isFile) return Properties()
    return try {
      FileInputStream(file).use { input ->
        Properties().also { it.load(input) }
      }
    } catch (exception: Exception) {
      Properties()
    }
  }

  /**
   * Read-modify-write one provider's base URL / API key into the shared
   * file, creating the directory as needed. Other providers' entries
   * are preserved. Failures are swallowed so a write never breaks the
   * settings panel.
   */
  fun updateProvider(configKey: String, baseUrl: String, apiKey: String) {
    try {
      configDir.mkdirs()
      val props: Properties = load()
      props.setProperty("GRADUM_${configKey.uppercase()}_BASE_URL", baseUrl.trim())
      props.setProperty("GRADUM_${configKey.uppercase()}_API_KEY", apiKey.trim())
      FileOutputStream(configFile).use { output ->
        props.store(output, "Gradum provider configuration (edited by the plugin)")
      }
    } catch (exception: Exception) {
      // Best-effort by design — see KDoc.
    }
  }
}
