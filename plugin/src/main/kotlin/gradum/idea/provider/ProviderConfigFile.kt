/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfigFile.kt  2026-08-17 09:21:43 Changed by gwy
 */
package gradum.idea.provider

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

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
  fun loadProperties(): Properties {
    val targetFile: File = configFile
    if (!targetFile.isFile) return Properties()
    return try {
      FileInputStream(targetFile).use { inputStream ->
        Properties().also { it.load(inputStream) }
      }
    } catch (_: Exception) {
      Properties()
    }
  }

  /**
   * Read-modify-write one provider's base URL / API key into the shared
   * file, creating the directory as needed. Other providers' entries
   * are preserved. Failures are swallowed so a write never breaks the
   * settings panel.
   */
  fun updateProviderConfig(configKey: String, baseUrl: String, apiKey: String) {
    val prefix = "GRADUM_${configKey.uppercase()}"
    editConfigFile { properties ->
      properties.setProperty("${prefix}_BASE_URL", baseUrl.trim())
      properties.setProperty("${prefix}_API_KEY", apiKey.trim())
    }
  }

  /**
   * Read-modify-write one provider's "allow remote" flag into the shared
   * file. The embedded server reads this flag and refuses to dial any
   * non-localhost base URL for that provider while it is `false`.
   * Failures are swallowed so a toggle never breaks the settings panel.
   */
  fun updateAllowRemote(configKey: String, allowRemote: Boolean) {
    editConfigFile { properties ->
      properties.setProperty("GRADUM_${configKey.uppercase()}_ALLOW_REMOTE", allowRemote.toString())
    }
  }

  /**
   * Loads the current properties, applies [transform], and stores the
   * result back. Shared by every read-modify-write entry point so the
   * "create dirs → load → mutate → store" dance lives in one place.
   * Failures are swallowed by design — see KDoc on the public callers.
   */
  private fun editConfigFile(transform: (Properties) -> Unit) {
    try {
      configDir.mkdirs()
      val properties: Properties = loadProperties()
      transform(properties)
      FileOutputStream(configFile).use { output ->
        properties.store(output, "Gradum provider configuration (edited by the plugin)")
      }
    } catch (_: Exception) {
      // Best-effort by design, please see KDoc.
    }
  }
}
