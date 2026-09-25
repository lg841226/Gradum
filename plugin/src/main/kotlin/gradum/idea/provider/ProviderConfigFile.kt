/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfigFile.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.idea.provider

import kotlinx.serialization.json.*
import java.io.File
import java.util.*

/**
 * Reads and edits the shared local settings file `~/.gradum/settings.json`.
 *
 * This file is the single source of truth between the plugin (read /
 * write) and the embedded Gradum server (read). The server re-reads it
 * on every model discovery, so plugin edits are picked up without a
 * restart. Provider overrides are stored at the top level of the JSON
 * object using VS Code style dotted keys `<configKey>.baseUrl` /
 * `<configKey>.apiKey` / `<configKey>.allowRemote`; every other key
 * (`server.*`, `llm.*`, `commandFilter.*`, `$schema`, ...) is preserved
 * untouched on write.
 */
object ProviderConfigFile {

  private val prettyJson: Json = Json { prettyPrint = true }

  private val KNOWN_CONFIG_KEYS: List<String> =
    listOf("ollama", "lmstudio", "zhipu", "deepseek", "minimax")

  private val configDir: File
    get() = System.getProperty("gradum.provider.configDir")?.let { File(it) }
      ?: File(System.getProperty("user.home"), ".gradum")

  private val configFile: File
    get() = File(configDir, "settings.json")

  private val providerDottedKeys: List<String>
    get() = KNOWN_CONFIG_KEYS.flatMap { configKey ->
      listOf("$configKey.baseUrl", "$configKey.apiKey", "$configKey.allowRemote")
    }

  /**
   * Read the provider overrides from settings.json as a [Properties] map
   * (dotted keys, `allowRemote` as `"true"`/`"false"` strings — the same
   * key semantics as the server's [gradum.ProviderConfigStore.load]).
   * Empty when the file is missing or unreadable.
   */
  fun loadProperties(): Properties {
    val props = Properties()
    val root: Map<String, Any?> = readRoot()
    for ((key, value) in root) {
      if (key !in providerDottedKeys) continue
      when (key.substringAfter('.', missingDelimiterValue = "")) {
        "baseUrl", "apiKey" -> if (value is String) props.setProperty(key, value)
        "allowRemote" -> if (value is Boolean) props.setProperty(key, value.toString())
      }
    }
    return props
  }

  /**
   * Read-modify-write one provider's base URL / API key into the shared
   * settings file, creating the directory as needed. Other providers'
   * entries and every non-provider key are preserved. Failures are
   * swallowed so a write never breaks the settings panel.
   */
  fun updateProviderConfig(configKey: String, baseUrl: String, apiKey: String) {
    editConfigFile { root ->
      root["$configKey.baseUrl"] = baseUrl.trim()
      root["$configKey.apiKey"] = apiKey.trim()
    }
  }

  /**
   * Read-modify-write one provider's entries out of the shared settings
   * file. Removing a cloud provider from the settings page deletes its
   * URL / API key (and any allow-remote flag) so the embedded server
   * stops probing it. Failures are swallowed by design.
   */
  fun removeProviderConfig(configKey: String) {
    editConfigFile { root ->
      root.remove("$configKey.baseUrl")
      root.remove("$configKey.apiKey")
      root.remove("$configKey.allowRemote")
    }
  }

  /** Remove every provider dotted key from settings.json, keeping all
   *  non-provider keys (server/llm/commandFilter/$schema) intact. */
  fun clearAll() {
    editConfigFile { root ->
      providerDottedKeys.forEach { root.remove(it) }
    }
  }

  /**
   * Read-modify-write one provider's "allow remote" flag into the shared
   * settings file. The embedded server reads this flag and refuses to dial
   * any non-localhost base URL for that provider while it is `false`.
   * Failures are swallowed so a toggle never breaks the settings panel.
   */
  fun updateAllowRemote(configKey: String, allowRemote: Boolean) {
    editConfigFile { root ->
      root["$configKey.allowRemote"] = allowRemote
    }
  }

  /**
   * Loads the current settings.json, applies [transform], and stores the
   * result back. Shared by every read-modify-write entry point so the
   * "create dirs → load → mutate → store" dance lives in one place.
   * Failures are swallowed by design — see KDoc on the public callers.
   */
  private fun editConfigFile(transform: (MutableMap<String, Any?>) -> Unit) {
    try {
      configDir.mkdirs()
      val root: MutableMap<String, Any?> = readRoot()
      transform(root)
      configFile.writeText(encodeJson(root))
    } catch (_: Exception) {
      // Best-effort by design, please see KDoc.
    }
  }

  private fun readRoot(): MutableMap<String, Any?> {
    val targetFile: File = configFile
    if (!targetFile.isFile) return mutableMapOf()
    return try {
      val element: JsonElement = Json.parseToJsonElement(targetFile.readText(Charsets.UTF_8))
      if (element is JsonObject) {
        element.entries.associate { (key, value) -> key to fromJsonElement(value) }.toMutableMap()
      } else {
        mutableMapOf()
      }
    } catch (_: Exception) {
      mutableMapOf()
    }
  }

  private fun encodeJson(root: Map<String, Any?>): String {
    val objectBuilder = buildJsonObject {
      for ((key, value) in root) put(key, toJsonElement(value))
    }
    return prettyJson.encodeToString(JsonElement.serializer(), objectBuilder)
  }

  private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
      for ((rawKey, rawValue) in value) {
        val key: String = rawKey?.toString() ?: continue
        put(key, toJsonElement(rawValue))
      }
    }

    is Iterable<*> -> buildJsonArray { for (element in value) add(toJsonElement(element)) }
    is Array<*> -> buildJsonArray { for (element in value) add(toJsonElement(element)) }
    else -> JsonPrimitive(value.toString())
  }

  private fun fromJsonElement(value: JsonElement): Any? = when (value) {
    is JsonNull -> null
    is JsonPrimitive -> when {
      value.isString -> value.content
      value.content.toBooleanStrictOrNull() != null -> value.content.toBooleanStrict()
      value.content.toLongOrNull() != null -> value.content.toLong()
      value.content.toDoubleOrNull() != null -> value.content.toDouble()
      else -> value.content
    }

    is JsonObject -> value.entries.associate { (key, element) -> key to fromJsonElement(element) }
    is JsonArray -> value.map { fromJsonElement(it) }
  }
}
