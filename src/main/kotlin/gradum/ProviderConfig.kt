/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfig.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum

import gradum.utils.JsonUtil
import java.io.File
import java.io.FileInputStream
import java.util.*

/**
 * Reads provider overrides from the shared local settings file
 * `~/.gradum/settings.json`.
 *
 * The file is a public, user-editable artifact shared between the plugin
 * (which reads and edits it) and the embedded server (which reads it).
 * The server re-reads it on every model discovery, so changing the plugin
 * settings — or hand-editing the file — is picked up without a server
 * restart.
 *
 * Provider overrides live at the top level of the JSON object using VS
 * Code style dotted keys: `<configKey>.baseUrl`, `<configKey>.apiKey`,
 * `<configKey>.allowRemote` (e.g. `ollama.baseUrl`,
 * `lmstudio.apiKey`, `zhipu.allowRemote`). `configKey` is the lowercase
 * provider key (see [gradum.ModelIdentity.Discovery.baseKnownServers]):
 * `ollama`, `lmstudio`, `zhipu`, `deepseek`, `minimax`. The rest of the
 * file (`server.*`, `llm.*`, `commandFilter.*`, `$schema`) is owned by
 * [gradum.server.ServerSettingsStore] and left untouched.
 *
 * The legacy `~/.gradum/provider.env` file (`GRADUM_<PROVIDER>_BASE_URL` /
 * `_API_KEY` / `_ALLOW_REMOTE` properties) is migrated into settings.json
 * once on first access and then deleted.
 */
object ProviderConfigStore {

  private val KNOWN_CONFIG_KEYS: List<String> =
    listOf("ollama", "lmstudio", "zhipu", "deepseek", "minimax")

  private val LEGACY_KEY_PATTERN: Regex =
    Regex("GRADUM_(.+)_(BASE_URL|API_KEY|ALLOW_REMOTE)")

  /** Directory holding the shared config file, overridable via the
   *  `gradum.provider.configDir` system property (useful for tests).
   *  Re-evaluated on every call so the property can change at runtime. */
  private val configDir: File
    get() = System.getProperty("gradum.provider.configDir")?.let { File(it) }
      ?: File(System.getProperty("user.home"), ".gradum")

  /** The shared provider config file, e.g. `~/.gradum/settings.json`. */
  private val configFile: File
    get() = File(configDir, "settings.json")

  /**
   * One-time migration from the legacy `provider.env` into settings.json.
   *
   * Runs only when settings.json contains no known provider dotted key
   * AND a legacy `provider.env` still exists: every
   * `GRADUM_<X>_BASE_URL` / `_API_KEY` / `_ALLOW_REMOTE` property is
   * mapped to `<lowercase(x)>.baseUrl` / `.apiKey` / `.allowRemote` and
   * merged into settings.json (read-modify-write, preserving
   * `server`/`llm`/`commandFilter`/`$schema` and all other keys). The
   * legacy file is deleted only after a successful write.
   */
  private fun migrateLegacyProviderEnv() {
    val legacyEnv = File(configDir, "provider.env")
    if (!legacyEnv.isFile) return

    val settingsFile: File = configFile
    val root: MutableMap<String, Any?> = try {
      if (settingsFile.isFile) JsonUtil.decodeMap(settingsFile.readText(Charsets.UTF_8)).toMutableMap()
      else mutableMapOf()
    } catch (_: Exception) {
      mutableMapOf()
    }
    if (KNOWN_CONFIG_KEYS.any { configKey ->
        root.containsKey("$configKey.baseUrl") ||
          root.containsKey("$configKey.apiKey") ||
          root.containsKey("$configKey.allowRemote")
      }
    ) {
      return
    }

    val legacy = Properties()
    try {
      FileInputStream(legacyEnv).use { input -> legacy.load(input) }
    } catch (_: Exception) {
      return
    }

    var migrated = false
    for ((rawKey, rawValue) in legacy) {
      val match = LEGACY_KEY_PATTERN.matchEntire(rawKey.toString()) ?: continue
      val configKey: String = match.groupValues[1].lowercase()
      if (configKey !in KNOWN_CONFIG_KEYS) continue
      when (match.groupValues[2]) {
        "BASE_URL" -> { root["$configKey.baseUrl"] = rawValue.toString(); migrated = true }
        "API_KEY" -> { root["$configKey.apiKey"] = rawValue.toString(); migrated = true }
        "ALLOW_REMOTE" -> {
          root["$configKey.allowRemote"] = rawValue.toString().toBooleanStrictOrNull() ?: false
          migrated = true
        }
      }
    }
    if (!migrated) return

    try {
      configDir.mkdirs()
      settingsFile.writeText(JsonUtil.encodeMap(root, prettyPrint = true))
      legacyEnv.delete()
    } catch (_: Exception) {
      // Best-effort migration by design — the legacy file survives a failed write.
    }
  }

  /**
   * Read the provider overrides from settings.json as a [Properties] map.
   * Returns an empty [Properties] when the file is missing or unreadable —
   * discovery then falls back to defaults. Keys use the dotted
   * `<configKey>.baseUrl` / `.apiKey` (string values) and
   * `.allowRemote` (`"true"`/`"false"` strings) convention.
   */
  fun load(): Properties {
    migrateLegacyProviderEnv()
    val props = Properties()
    val file: File = configFile
    if (!file.isFile) return props
    try {
      val root: Map<String, Any?> = JsonUtil.decodeMap(file.readText(Charsets.UTF_8))
      for (configKey in KNOWN_CONFIG_KEYS) {
        (root["$configKey.baseUrl"] as? String)
          ?.let { props.setProperty("$configKey.baseUrl", it) }
        (root["$configKey.apiKey"] as? String)
          ?.let { props.setProperty("$configKey.apiKey", it) }
        (root["$configKey.allowRemote"] as? Boolean)
          ?.let { props.setProperty("$configKey.allowRemote", it.toString()) }
      }
    } catch (_: Exception) {
      // ignore malformed content; discovery falls back to defaults
    }
    return props
  }

  /** Dotted settings.json key holding the base URL override for a
   *  provider, or null if the provider has no configKey. */
  fun baseUrlKey(configKey: String?): String? =
    configKey?.let { "$it.baseUrl" }

  /** Dotted settings.json key holding the API key override for a
   *  provider, or null if the provider has no configKey. */
  fun apiKeyKey(configKey: String?): String? =
    configKey?.let { "$it.apiKey" }

  /** Dotted settings.json key holding the "allow remote" flag for a
   *  provider, or null if the provider has no configKey. */
  fun allowRemoteKey(configKey: String?): String? =
    configKey?.let { "$it.allowRemote" }

  /** Reads the provider's "allow remote" flag. Absent/unparseable → `false`,
   *  i.e. only localhost connections are permitted by default. */
  fun isAllowRemote(configKey: String?): Boolean {
    migrateLegacyProviderEnv()
    val key: String = allowRemoteKey(configKey) ?: return false
    val file: File = configFile
    if (!file.isFile) return false
    return try {
      val root: Map<String, Any?> = JsonUtil.decodeMap(file.readText(Charsets.UTF_8))
      root[key]?.toString()?.toBooleanStrictOrNull() ?: false
    } catch (_: Exception) {
      false
    }
  }

  /** Resolves a probe `kind` (`ollama` / `lmstudio`) to its config key. */
  fun configKeyFor(kind: String): String? = when (kind.lowercase()) {
    "ollama" -> "ollama"
    "lmstudio" -> "lmstudio"
    else -> null
  }

  /**
   * Cheap fingerprint of the config file's current content — `mtime:size`.
   * Used to invalidate the server's model-discovery cache when the file
   * changes (plugin settings edits land here), so `/models` never serves a
   * stale snapshot after a provider reconfiguration. Returns the same
   * stable string for a missing file so a transient read hiccup doesn't
   * look like a config change.
   */
  fun fingerprint(): String {
    val file: File = configFile
    if (!file.isFile) return "missing"
    return try {
      val lastModified: Long = file.lastModified()
      val length: Long = file.length()
      "$lastModified:$length"
    } catch (_: Exception) {
      "missing"
    }
  }
}
