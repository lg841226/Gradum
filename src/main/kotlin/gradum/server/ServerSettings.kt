/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerSettings.kt  2026-09-11 16:44:12 Changed by gwy
 */

package gradum.server

import gradum.AgentConfiguration
import gradum.utils.CommandFilterConfig
import gradum.utils.JsonUtil
import gradum.utils.ProtectedPathsConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

private val logger: Logger = LoggerFactory.getLogger("ServerSettingsStore")

/**
 * The fully-resolved server configuration read from `~/.gradum/settings.json`.
 *
 * The config file is the single source of truth for the server's startup
 * parameters — the old `--host/--port/--auto-port/--api-key` CLI flags have
 * been removed (see gradum.server.Main). `server.*` drives the HTTP bind
 * and default credentials; `llm.*` are server-wide defaults applied only
 * when the plugin request doesn't supply its own values.
 */
data class ServerSettings(
  val host: String,
  val port: Int,
  /** Path to a file whose first non-blank line is used as the default API key. */
  val apiKeyFile: String?,
  val defaultBaseUrl: String,
  val autoDetectPort: Boolean,
  val defaultModelName: String,
  val defaultThinkEnabled: Boolean,
  /** Server-wide Ollama keep-alive (minutes) used when the plugin request omits `keepAliveMinutes`. */
  val defaultKeepAliveMinutes: Int,
  /** Resolved command filter rules; equals [CommandFilterConfig.DEFAULT] when the user configures nothing. */
  val commandFilter: CommandFilterConfig,
)

/**
 * Loads and releases the server settings file.
 *
 * Mirrors the `~/.gradum` resolution of [gradum.ProviderConfigStore] but
 * for the richer `settings.json` payload. The default `settings.json` and
 * its companion `settings.schema.json` (editor autocompletion) ship inside
 * the jar and are copied to `~/.gradum/` on first startup so the user gets
 * an editable starting point.
 */
object ServerSettingsStore {

  private const val SETTINGS_FILE_NAME: String = "settings.json"
  private const val SCHEMA_FILE_NAME: String = "settings.schema.json"

  /**
   * Directory holding the server settings. Overridable via the
   * `gradum.server.configDir` system property (useful for tests);
   * re-evaluated on every call so the property can change at runtime.
   */
  fun configDir(): File =
    System.getProperty("gradum.server.configDir")?.let(::File)
      ?: File(System.getProperty("user.home"), ".gradum")

  /**
   * Ensures `~/.gradum/` exists and copies the bundled `settings.json` and
   * `settings.schema.json` into it when they are absent. Returns the dir so
   * callers can locate the released files. Existing files are never
   * overwritten — a user edit survives server restarts.
   */
  fun releaseDefaultsIfMissing(): File {
    val configDirectory: File = configDir()
    configDirectory.mkdirs()
    releaseIfAbsent(configDirectory, SETTINGS_FILE_NAME)
    releaseIfAbsent(configDirectory, SCHEMA_FILE_NAME)
    return configDirectory
  }

  private fun releaseIfAbsent(configDirectory: File, resourceName: String) {
    val target = File(configDirectory, resourceName)
    if (target.exists()) return
    ServerSettingsStore::class.java.getResourceAsStream("/$resourceName")?.use { input ->
      target.writeBytes(input.readBytes())
      logger.info("Released default $resourceName -> ${target.absolutePath}")
    }
  }

  /**
   * Parses `~/.gradum/settings.json` into [ServerSettings]. Tolerant of a
   * missing file, malformed JSON, or absent keys — every field falls back
   * to its built-in default.
   */
  fun load(): ServerSettings {
    val configFile = File(configDir(), SETTINGS_FILE_NAME)
    val rawContent: String =
      try {
        if (configFile.isFile) configFile.readText(Charsets.UTF_8) else ""
      } catch (readException: Exception) {
        logger.warn("Failed to read $configFile: ${readException.message}"); ""
      }

    val root: Map<String, Any?> =
      try {
        if (rawContent.isBlank()) emptyMap() else JsonUtil.decodeMap(rawContent)
      } catch (parseException: Exception) {
        logger.error("Failed to parse $SETTINGS_FILE_NAME: ${parseException.message}"); emptyMap()
      }

    val serverGroup: Map<*, *> = root["server"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val llmGroup: Map<*, *> = root["llm"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

    val issues: MutableList<ValidationIssue> = mutableListOf()
    warnUnknownKeys(root, TOP_LEVEL_KEYS, "", issues)
    warnUnknownKeys(serverGroup, SERVER_KEYS, "server", issues)
    warnUnknownKeys(llmGroup, LLM_KEYS, "llm", issues)

    var host: String = ServerConfiguration.DEFAULT_HOST_ADDRESS
    val rawHost: Any? = serverGroup["host"]
    if (rawHost != null) {
      if (rawHost is String && rawHost.isNotBlank()) host = rawHost
      else issues += ValidationIssue("server.host", Severity.ERROR, "expected a non-empty host string; got ${describe(rawHost)}")
    }

    var port: Int = ServerConfiguration.DEFAULT_PORT_NUMBER
    val rawPort: Any? = serverGroup["port"]
    if (rawPort != null) {
      when (val parsedPort: Int? = integerValue(rawPort)) {
        null ->
          issues += ValidationIssue("server.port", Severity.ERROR, "expected ${PORT_RANGE.first}..${PORT_RANGE.last}; got ${describe(rawPort)}")

        !in PORT_RANGE ->
          issues += ValidationIssue("server.port", Severity.ERROR, "expected ${PORT_RANGE.first}..${PORT_RANGE.last}; got $parsedPort")

        else -> port = parsedPort
      }
    }

    var autoDetectPort = false
    val rawAutoDetectPort: Any? = serverGroup["autoDetectPort"]
    if (rawAutoDetectPort != null) {
      if (rawAutoDetectPort is Boolean) autoDetectPort = rawAutoDetectPort
      else issues += ValidationIssue("server.autoDetectPort", Severity.ERROR, "expected a boolean (true/false); got ${describe(rawAutoDetectPort)}")
    }

    var apiKeyFile: String? = null
    val rawApiKeyFile: Any? = serverGroup["apiKeyFile"]
    if (rawApiKeyFile != null) {
      when {
        rawApiKeyFile !is String ->
          issues += ValidationIssue("server.apiKeyFile", Severity.WARN, "expected a string (absolute path) or null; got ${describe(rawApiKeyFile)}")

        rawApiKeyFile.isBlank() ->
          issues += ValidationIssue("server.apiKeyFile", Severity.WARN, "expected a non-blank absolute path")

        !rawApiKeyFile.startsWith("/") ->
          issues += ValidationIssue("server.apiKeyFile", Severity.WARN, "expected an absolute path; relative or \"~\" shorthand is not allowed: \"$rawApiKeyFile\"")

        else -> apiKeyFile = rawApiKeyFile
      }
    }

    var defaultBaseUrl: String = AgentConfiguration.DEFAULT_OLLAMA_BASE_URL
    val rawBaseUrl: Any? = llmGroup["baseUrl"]
    if (rawBaseUrl != null) {
      if (rawBaseUrl is String && rawBaseUrl.isNotBlank()) defaultBaseUrl = rawBaseUrl
      else issues += ValidationIssue("llm.baseUrl", Severity.WARN, "expected a non-empty URL string; got ${describe(rawBaseUrl)}")
    }

    var defaultModelName = ""
    val rawModel: Any? = llmGroup["model"]
    if (rawModel != null) {
      if (rawModel is String) defaultModelName = rawModel
      else issues += ValidationIssue("llm.model", Severity.WARN, "expected a string; got ${describe(rawModel)}")
    }

    var defaultThinkEnabled: Boolean = AgentConfiguration.DEFAULT_ENABLE_THINKING
    val rawThink: Any? = llmGroup["think"]
    if (rawThink != null) {
      if (rawThink is Boolean) defaultThinkEnabled = rawThink
      else issues += ValidationIssue("llm.think", Severity.WARN, "expected a boolean (true/false); got ${describe(rawThink)}")
    }

    var defaultKeepAliveMinutes: Int = AgentConfiguration.DEFAULT_KEEP_ALIVE_MINUTES
    val rawKeepAlive: Any? = llmGroup["keepAliveMinutes"]
    if (rawKeepAlive != null) {
      if (rawKeepAlive is Number)
        defaultKeepAliveMinutes = rawKeepAlive.toInt()
      else issues += ValidationIssue("llm.keepAliveMinutes", Severity.WARN, "expected an integer (minutes); got ${describe(rawKeepAlive)}")
    }

    val commandFilter: CommandFilterConfig = parseCommandFilter(sectionRaw = root["commandFilter"], issues = issues)

    logIssues(issues)
    if (issues.isEmpty()) {
      logger.info("Settings OK (host=$host, port=$port)")
    } else {
      val errorCount: Int = issues.count { it.level == Severity.ERROR }
      val warnCount: Int = issues.count { it.level == Severity.WARN }
      logger.info("Settings fallback ($errorCount error, $warnCount warning); host=$host port=$port")
    }

    return ServerSettings(
      host = host,
      port = port,
      apiKeyFile = apiKeyFile,
      defaultBaseUrl = defaultBaseUrl,
      autoDetectPort = autoDetectPort,
      defaultModelName = defaultModelName,
      defaultThinkEnabled = defaultThinkEnabled,
      defaultKeepAliveMinutes = defaultKeepAliveMinutes,
      commandFilter = commandFilter,
    )
  }

  /**
   * Reads the API key from the absolute path referenced by `apiKeyFile`,
   * using that file's first non-blank line (trimmed). Returns null when the
   * path is blank or the file is unreadable — callers then fall back to env.
   */
  fun resolveApiKeyFromFile(apiKeyFile: String?): String? {
    val rawPath: String = apiKeyFile?.trim() ?: return null
    if (rawPath.isEmpty()) return null
    val keyFile = File(rawPath)
    if (!keyFile.isFile) {
      logger.warn("apiKeyFile not found: ${keyFile.absolutePath}")
      return null
    }
    return try {
      keyFile.readLines().firstOrNull { it.isNotBlank() }?.trim()
    } catch (readException: Exception) {
      logger.warn("Failed to read apiKeyFile ${keyFile.absolutePath}: ${readException.message}")
      null
    }
  }

  private enum class Severity { WARN, ERROR }

  private data class ValidationIssue(
    val key: String,
    val level: Severity,
    val reason: String,
  )

  private val PORT_RANGE: IntRange = 1024..65535
  private val TOP_LEVEL_KEYS: Set<String> = setOf(
    $$"$schema", "server", "llm", "commandFilter",
    // Provider overrides live at the top level as VS Code style dotted
    // keys (see gradum.ProviderConfigStore); they must not warn as unknown.
    "ollama.baseUrl", "ollama.apiKey", "ollama.allowRemote",
    "lmstudio.baseUrl", "lmstudio.apiKey", "lmstudio.allowRemote",
    "zhipu.baseUrl", "zhipu.apiKey", "zhipu.allowRemote",
    "deepseek.baseUrl", "deepseek.apiKey", "deepseek.allowRemote",
    "minimax.baseUrl", "minimax.apiKey", "minimax.allowRemote",
  )
  private val SERVER_KEYS: Set<String> = setOf("host", "port", "autoDetectPort", "apiKeyFile")
  private val LLM_KEYS: Set<String> = setOf("baseUrl", "model", "think")

  private fun logIssues(issues: List<ValidationIssue>) {
    if (issues.isEmpty()) return
    val rootLine = "\u250c\u2500 Invalid gradum.settings (fix ~/.gradum/settings.json)"
    if (issues.any { it.level == Severity.ERROR }) logger.error(rootLine) else logger.warn(rootLine)
    issues.forEachIndexed { index, issue ->
      val connector = if (index == issues.lastIndex) "\u2514\u2500" else "\u251c\u2500"
      val line = "$connector ${issue.key}: ${issue.reason}"
      when (issue.level) {
        Severity.ERROR -> logger.error(line)
        Severity.WARN -> logger.warn(line)
      }
    }
  }

  private fun warnUnknownKeys(group: Map<*, *>, allowed: Set<String>, groupLabel: String, issues: MutableList<ValidationIssue>) {
    group.keys.filterIsInstance<String>()
      .filter { it !in allowed }
      .forEach { key ->
        val qualifiedKey: String = if (groupLabel.isEmpty()) key else "$groupLabel.$key"
        issues += ValidationIssue(qualifiedKey, Severity.WARN, "unknown property; ignored")
      }
  }

  private fun describe(value: Any?): String =
    when (value) {
      null -> "null"
      is String -> "\"$value\""
      else -> value.toString()
    }

  private fun integerValue(value: Any?): Int? =
    when (value) {
      is Int -> value
      is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
      is Short, is Byte -> value.toInt()
      is Double -> if (value.isFinite() && value == value.toInt().toDouble()) value.toInt() else null
      is Float -> if (value.isFinite() && value == value.toInt().toFloat()) value.toInt() else null
      else -> null
    }

  /**
   * Resolves the user's optional `commandFilter` section into a
   * [CommandFilterConfig]. Starts from [CommandFilterConfig.DEFAULT] and
   * replaces each field that the user supplied (replace-whole semantics);
   * an absent or malformed field keeps the DEFAULT. Non-object values for
   * the section itself are ignored and fall back to [CommandFilterConfig.DEFAULT].
   */
  private fun parseCommandFilter(
    sectionRaw: Any?, issues: MutableList<ValidationIssue>
  ): CommandFilterConfig {
    if (sectionRaw == null) return CommandFilterConfig.DEFAULT
    if (sectionRaw !is Map<*, *>) {
      issues += ValidationIssue(key = "commandFilter", level = Severity.WARN, reason = "expected an object; got ${describe(sectionRaw)}")
      return CommandFilterConfig.DEFAULT
    }

    val defaultConfig: CommandFilterConfig = CommandFilterConfig.DEFAULT
    var resolvedProtectedPaths: ProtectedPathsConfig = defaultConfig.protectedPaths

    warnUnknownKeys(sectionRaw, COMMAND_FILTER_KEYS, "commandFilter", issues)

    val protectedSectionRaw: Any? = sectionRaw["protectedPaths"]
    if (protectedSectionRaw != null) {
      if (protectedSectionRaw is Map<*, *>) resolvedProtectedPaths = parseProtectedPaths(protectedSectionRaw, issues)
      else issues += ValidationIssue(
        key = "commandFilter.protectedPaths",
        level = Severity.WARN,
        reason = "expected an object; got ${describe(protectedSectionRaw)}"
      )
    }

    return CommandFilterConfig(
      blockedExecutables = stringListOrNull(sectionRaw["blockedExecutables"])?.toSet() ?: defaultConfig.blockedExecutables,
      protectedPaths = resolvedProtectedPaths,
    )
  }

  private fun parseProtectedPaths(
    sectionRaw: Map<*, *>, issues: MutableList<ValidationIssue>
  ): ProtectedPathsConfig {
    val defaultProtectedPaths: ProtectedPathsConfig = CommandFilterConfig.DEFAULT.protectedPaths
    warnUnknownKeys(sectionRaw, PROTECTED_PATHS_KEYS, "commandFilter.protectedPaths", issues)
    return ProtectedPathsConfig(
      systemPrefixes = stringListOrNull(sectionRaw["systemPrefixes"]) ?: defaultProtectedPaths.systemPrefixes,
      protectedHomeSubdirectories = stringListOrNull(sectionRaw["protectedHomeSubdirectories"]) ?: defaultProtectedPaths.protectedHomeSubdirectories,
      safePathPrefixes = stringListOrNull(sectionRaw["safePathPrefixes"]) ?: defaultProtectedPaths.safePathPrefixes,
      exactProtectedPaths = stringListOrNull(sectionRaw["exactProtectedPaths"]) ?: defaultProtectedPaths.exactProtectedPaths,
    )
  }

  /** Returns the strings of a JSON array, or null when [jsonValue] is not an all-string list. */
  private fun stringListOrNull(jsonValue: Any?): List<String>? =
    if (jsonValue is List<*>) jsonValue.filterIsInstance<String>().takeIf { it.size == jsonValue.size } else null

  private val COMMAND_FILTER_KEYS: Set<String> = setOf("blockedExecutables", "protectedPaths")
  private val PROTECTED_PATHS_KEYS: Set<String> =
    setOf("systemPrefixes", "protectedHomeSubdirectories", "safePathPrefixes", "exactProtectedPaths")
}
