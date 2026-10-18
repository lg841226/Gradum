package gradum.idea.provider

import gradum.idea.provider.ProviderConfigFile.editConfigFile
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
 * `<configKey>.apiKey` / `<configKey>.allowRemote`; the structured
 * `mcpServers` array is owned by [readMcpServers] / [writeMcpServers];
 * every other key (`server.*`, `llm.*`, `commandFilter.*`, `$schema`,
 * ...) is preserved untouched on write.
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

  /** Absolute path of the shared settings file, for UI actions that open it in the IDE editor. */
  val settingsFile: File get() = configFile

  private val providerDottedKeys: List<String>
    get() = KNOWN_CONFIG_KEYS.flatMap { configKey ->
      listOf("$configKey.baseUrl", "$configKey.apiKey", "$configKey.allowRemote")
    }

  /**
   * Read the provider overrides from settings.json as a [Properties] map
   * (dotted keys, `allowRemote` as `"true"`/`"false"` strings: the same
   * key semantics as the server's gradum.ProviderConfigStore.load).
   * Empty when the file is missing or unreadable.
   */
  fun loadProperties(): Properties {
    val props = Properties()
    val root: JsonObject = readRootJsonObject()
    for ((key, element) in root) {
      if (key !in providerDottedKeys) continue
      val primitive: JsonPrimitive = element as? JsonPrimitive ?: continue
      when (key.substringAfter('.', missingDelimiterValue = "")) {
        "baseUrl", "apiKey" -> if (primitive.isString) props.setProperty(key, primitive.content)
        "allowRemote" -> primitive.content.toBooleanStrictOrNull()
          ?.let { props.setProperty(key, it.toString()) }
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
      root["$configKey.baseUrl"] = JsonPrimitive(baseUrl.trim())
      root["$configKey.apiKey"] = JsonPrimitive(apiKey.trim())
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

  /** Remove every provider dotted key and the `mcpServers` array from
   *  settings.json, keeping all other keys (server/llm/commandFilter/
   *  $schema/plugins) intact. */
  fun clearAll() {
    editConfigFile { root ->
      providerDottedKeys.forEach { root.remove(it) }
    }
    editConfigFile { root -> root.remove("mcpServers") }
  }

  /**
   * Read-modify-write one provider's "allow remote" flag into the shared
   * settings file. The embedded server reads this flag and refuses to dial
   * any non-localhost base URL for that provider while it is `false`.
   * Failures are swallowed so a toggle never breaks the settings panel.
   */
  fun updateAllowRemote(configKey: String, allowRemote: Boolean) {
    editConfigFile { root ->
      root["$configKey.allowRemote"] = JsonPrimitive(allowRemote)
    }
  }

  /**
   * Loads the current settings file as raw [JsonElement]s, applies
   * [transform] to the mutable key to element map, and stores the result
   * back. Values keep their original JSON types byte-exact: no
   * round trip through untyped Kotlin objects: so string arrays and
   * string-valued objects such as `mcpServers` can never drift to
   * numbers. Keys the [transform] does not touch are written back as
   * the original elements. Failures are swallowed by design: see KDoc
   * on the public callers.
   */
  private fun editConfigFile(transform: (MutableMap<String, JsonElement>) -> Unit) {
    editRootJson { root ->
      val mutable: MutableMap<String, JsonElement> = LinkedHashMap<String, JsonElement>().apply {
        for ((key, value) in root) put(key, value)
      }
      transform(mutable)
      buildJsonObject { for ((key, value) in mutable) put(key, value) }
    }
  }

  /**
   * Create-dirs to read to [transform] to write dance shared by every
   * mutator. Failures are swallowed, same contract as [editConfigFile].
   */
  private fun editRootJson(transform: (JsonObject) -> JsonObject) {
    try {
      configDir.mkdirs()
      val root: JsonObject = readRootJsonObject()
      configFile.writeText(prettyJson.encodeToString(JsonElement.serializer(), transform(root)))
    } catch (_: Exception) {
      // Best-effort by design, please see KDoc.
    }
  }

  private fun readRootJsonObject(): JsonObject =
    try {
      if (!configFile.isFile) buildJsonObject { }
      else {
        val element: JsonElement = Json.parseToJsonElement(configFile.readText(Charsets.UTF_8))
        element as? JsonObject ?: buildJsonObject { }
      }
    } catch (_: Exception) {
      buildJsonObject { }
    }

  /** One `mcpServers[]` entry, typed for the settings UI. */
  data class McpServerEntry(
    val name: String,
    val command: List<String>,
    val workingDir: String?,
    val env: Map<String, String> = emptyMap()
  )

  /**
   * Reads the `mcpServers` array from settings.json, preserving entry
   * order and JSON types. Lenient by design: entries without a non-blank
   * string `name` are skipped (same rule as the server's parser);
   * `command` accepts a string array (canonical form) or a single string
   * (hand-edited shorthand, best-effort tokenized); `env` values and
   * `workingDir` are coerced to strings so pre-existing numeric-looking
   * values still display. Empty when the key is missing or unreadable.
   */
  fun readMcpServers(): List<McpServerEntry> {
    val root: JsonObject = readRootJsonObject()
    val array: JsonArray = root["mcpServers"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
      if (element !is JsonObject) return@mapNotNull null
      val name: String =
        (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
          ?: return@mapNotNull null
      val workingDir: String? =
        (element["workingDir"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
      val env: Map<String, String> = buildMap {
        (element["env"] as? JsonObject)?.forEach { (key, value) ->
          if (value is JsonPrimitive && value != JsonNull) put(key, value.content)
        }
      }
      McpServerEntry(
        env = env,
        name = name,
        workingDir = workingDir,
        command = readMcpCommand(element["command"])
      )
    }
  }

  /**
   * Leniently reads one `command` field: the canonical array of strings,
   * or a single string (hand-edited, tokenized best-effort; unparseable
   * input degrades to a single token so the user can still see and fix
   * it). Anything else becomes an empty list.
   */
  private fun readMcpCommand(raw: JsonElement?): List<String> = when (raw) {
    is JsonArray -> raw.filterIsInstance<JsonPrimitive>().filterNot { it == JsonNull }.map { it.content }
    is JsonPrimitive ->
      if (raw.isString) parseCommandText(raw.content).ifEmpty { listOf(raw.content) }
      else if (raw == JsonNull) emptyList()
      else listOf(raw.content)

    else -> emptyList()
  }

  /**
   * Read-modify-write the whole `mcpServers` array in one shot. Entries
   * are written with explicit JSON string primitives so array items and
   * env values can never drift to numbers. Entries beyond the previous
   * array length are created fresh; for indexes that already existed,
   * any unknown keys inside the old entry object are kept so hand-added
   * properties survive a round trip. Failures are swallowed, same
   * contract as the other mutators.
   */
  fun writeMcpServers(servers: List<McpServerEntry>) {
    editConfigFile { root ->
      val previous: List<JsonObject> =
        (root["mcpServers"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
      val knownKeys: Set<String> = setOf("name", "command", "workingDir", "env")
      root["mcpServers"] = buildJsonArray {
        servers.forEachIndexed { index, server ->
          val base: JsonObject = previous.getOrNull(index) ?: buildJsonObject { }
          add(
            buildJsonObject {
              for ((key, value) in base)
                if (key !in knownKeys) put(key, value)

              put("name", JsonPrimitive(server.name))
              put("command", buildJsonArray { server.command.forEach { token -> add(JsonPrimitive(token)) } })

              val workingDir: String? = server.workingDir?.takeIf { it.isNotBlank() }
              if (workingDir != null) put("workingDir", JsonPrimitive(workingDir))
              else put("workingDir", JsonNull)

              put(
                "env",
                buildJsonObject {
                  server.env.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                }
              )
            }
          )
        }
      }
    }
  }

  /**
   * Parses a single-line command into its argv tokens: tokens are split
   * on whitespace, and a double quote starts a quoted span that may
   * contain whitespace (`"a b" c` -> `["a b", "c"]`); `\"` inside a
   * quoted span is a literal quote. Returns an empty list when a quote
   * is left unterminated, so callers can flag the input as invalid
   * instead of silently guessing.
   */
  internal fun parseCommandText(raw: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var tokenStarted = false
    var index = 0

    while (index < raw.length) {
      val char: Char = raw[index]
      when {
        inQuotes && char == '\\' && index + 1 < raw.length && raw[index + 1] == '"' -> {
          current.append('"')
          tokenStarted = true
          index += 2
          continue
        }

        char == '"' -> {
          inQuotes = !inQuotes
          tokenStarted = true
        }

        char.isWhitespace() && !inQuotes -> {
          if (tokenStarted) {
            tokens += current.toString()
            current.setLength(0)
            tokenStarted = false
          }
        }

        else -> {
          current.append(char)
          tokenStarted = true
        }
      }
      index++
    }

    if (inQuotes) return emptyList()
    if (tokenStarted) tokens += current.toString()
    return tokens
  }

  /**
   * Renders argv tokens back into single-line command text, quoting any
   * token that contains whitespace, quotes, or is empty, mirroring the
   * grammar accepted by [parseCommandText].
   */
  internal fun formatCommandText(command: List<String>): String =
    command.joinToString(separator = " ") { token ->
      if (token.isEmpty() || token.any { it.isWhitespace() || it == '"' })
        "\"" + token.replace("\"", "\\\"") + "\""
      else token
    }
}
