package gradum.mode

import gradum.server.setup.ServerSettingsStore
import gradum.utils.JsonUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

private val logger: Logger = LoggerFactory.getLogger("ModeRegistry")

object ModeRegistry {

  private val BUILTIN_IDS: List<String> = listOf("agent", "edit", "read_only", "yolo")
  private const val DEFAULT_MODE_ID: String = "agent"
  private val MODE_ID_PATTERN: Regex = Regex("[a-z0-9_]{1,64}")

  private val MODE_KEYS: Set<String> =
    setOf("id", "label", "description", "aliases", "tools", "gates", "prompt")
  private val TOOLS_KEYS: Set<String> = setOf("allow", "deny")
  private val GATES_KEYS: Set<String> = setOf("approvals", "commandFilter", "protectedPaths")

  private class Snapshot(val ordered: List<AgentMode>, val byKey: Map<String, AgentMode>)

  @Volatile
  private var snapshot: Snapshot? = null

  /** Every mode in display order: built-ins first, then user additions. */
  fun list(): List<AgentMode> = current().ordered

  /**
   * Resolves a free-form mode reference (id or alias, any case) to a mode.
   * Unknown, blank or null input falls back to [defaultId]; a registry
   * missing its default falls back to a compiled-in agent mode so the
   * server can never end up with no usable mode.
   */
  fun resolve(rawValue: String?, defaultId: String = DEFAULT_MODE_ID): AgentMode {
    val normalized: String = rawValue?.trim()?.lowercase().orEmpty()
    val modes: Snapshot = current()
    if (normalized.isNotEmpty()) modes.byKey[normalized]?.let { return it }
    return modes.byKey[defaultId] ?: FALLBACK_AGENT_MODE
  }

  fun resolveId(rawValue: String?, defaultId: String = DEFAULT_MODE_ID): String =
    resolve(rawValue, defaultId).id

  fun reload() {
    snapshot = buildSnapshot()
  }

  fun userModesDir(): File = File(ServerSettingsStore.configDir(), "modes")

  private fun current(): Snapshot =
    snapshot ?: synchronized(this) { snapshot ?: buildSnapshot().also { snapshot = it } }

  private fun buildSnapshot(): Snapshot {
    val issues: MutableList<ValidationIssue> = mutableListOf()
    val byId: LinkedHashMap<String, AgentMode> = LinkedHashMap()

    for (builtinId: String in BUILTIN_IDS) {
      val mode: AgentMode? = parseMode(
        text = readBuiltinResource(builtinId),
        expectedId = builtinId,
        source = "modes/$builtinId.json",
        issues = issues
      )
      if (mode != null) byId[mode.id] = mode
    }
    if (DEFAULT_MODE_ID !in byId) {
      issues += ValidationIssue(
        "modes/$DEFAULT_MODE_ID.json", Severity.ERROR,
        "the default mode is unavailable; using the compiled fallback"
      )
      byId[DEFAULT_MODE_ID] = FALLBACK_AGENT_MODE
    }

    val userDefinedIds: MutableSet<String> = mutableSetOf()
    val userFiles: List<File> =
      userModesDir().listFiles { file: File -> file.isFile && file.name.endsWith(".json") }
        ?.sortedBy { it.name } ?: emptyList()
    for (file: File in userFiles) {
      val expectedId: String = file.name.removeSuffix(".json")
      if (expectedId in userDefinedIds) {
        issues += ValidationIssue(
          file.name, Severity.ERROR,
          "duplicate id \"$expectedId\" across user mode files; this file is ignored"
        )
        continue
      }
      val mode = parseMode(readFileText(file), expectedId, file.name, issues) ?: continue
      userDefinedIds += expectedId
      byId[mode.id] = mode
    }

    val byKey: LinkedHashMap<String, AgentMode> = LinkedHashMap()
    for (mode: AgentMode in byId.values) byKey.putIfAbsent(mode.id, mode)
    for (mode: AgentMode in byId.values) {
      for (alias: String in mode.aliases) {
        val clash: AgentMode? = byKey[alias]
        if (clash != null && clash !== mode) {
          issues += ValidationIssue("${mode.id}:aliases", Severity.ERROR, "alias \"$alias\" belongs to \"${clash.id}\"; dropped")
          continue
        }
        byKey.putIfAbsent(alias, mode)
      }
    }

    logIssues(issues)
    return Snapshot(byId.values.toList(), byKey)
  }

  private fun parseMode(
    text: String?, expectedId: String, source: String, issues: MutableList<ValidationIssue>
  ): AgentMode? {
    if (text.isNullOrBlank()) {
      issues += ValidationIssue(source, Severity.ERROR, "unreadable or empty file; ignored")
      return null
    }
    val root: Map<String, Any?> = try {
      JsonUtil.decodeMap(text)
    } catch (parseException: Exception) {
      issues += ValidationIssue(source, Severity.ERROR, "invalid JSON (${parseException.message}); ignored")
      return null
    }

    warnUnknownKeys(root, MODE_KEYS, source, issues)

    val rawId: Any? = root["id"]
    if (rawId !is String || !MODE_ID_PATTERN.matches(rawId)) {
      issues += ValidationIssue("$source:id", Severity.ERROR, "expected an id matching [a-z0-9_]{1,64}; got ${describe(rawId)}")
      return null
    }
    if (rawId != expectedId) {
      issues += ValidationIssue("$source:id", Severity.ERROR, "id \"$rawId\" does not match file name \"$expectedId\"; ignored")
      return null
    }

    var label: String = rawId
    val rawLabel: Any? = root["label"]
    when {
      rawLabel is String && rawLabel.isNotBlank() -> label = rawLabel
      rawLabel != null -> issues += ValidationIssue("$source.label", Severity.WARN, "expected a non-blank string; got ${describe(rawLabel)}")
    }

    val description: String = (root["description"] as? String) ?: ""
    val aliases: List<String> = parseAliases(root["aliases"], source, issues)
    val (allow, deny) = parseTools(root["tools"], source, issues)
    val gates: ModeGates = parseGates(root["gates"], source, issues)

    var prompt: String = rawId
    val rawPrompt: Any? = root["prompt"]
    when {
      rawPrompt is String && rawPrompt.isNotBlank() -> prompt = rawPrompt
      rawPrompt != null -> issues += ValidationIssue("$source.prompt", Severity.WARN, "expected a non-blank string; got ${describe(rawPrompt)}")
    }

    return AgentMode(
      id = rawId,
      label = label,
      description = description,
      aliases = aliases,
      allow = allow,
      deny = deny,
      gates = gates,
      prompt = prompt
    )
  }

  private fun parseAliases(raw: Any?, source: String, issues: MutableList<ValidationIssue>): List<String> {
    if (raw == null) return emptyList()
    if (raw !is List<*>) {
      issues += ValidationIssue("$source.aliases", Severity.WARN, "expected an array of strings; got ${describe(raw)}")
      return emptyList()
    }
    return raw.mapNotNull { entry: Any? ->
      when {
        entry is String && MODE_ID_PATTERN.matches(entry) -> entry
        entry is String -> {
          issues += ValidationIssue("$source.aliases", Severity.WARN, "alias \"$entry\" does not match [a-z0-9_]{1,64}; dropped")
          null
        }

        else -> {
          issues += ValidationIssue("$source.aliases", Severity.WARN, "expected a string; got ${describe(entry)}; dropped")
          null
        }
      }
    }
  }

  private fun parseTools(raw: Any?, source: String, issues: MutableList<ValidationIssue>): Pair<List<String>, List<String>> {
    if (raw == null) return listOf(AgentMode.SELECTOR_ALL) to emptyList()
    if (raw !is Map<*, *>) {
      issues += ValidationIssue("$source.tools", Severity.WARN, "expected an object; got ${describe(raw)}")
      return listOf(AgentMode.SELECTOR_ALL) to emptyList()
    }
    warnUnknownKeys(raw, TOOLS_KEYS, "$source.tools", issues)
    val allow: List<String> = stringList(raw["allow"], "$source.tools.allow", issues) ?: listOf(AgentMode.SELECTOR_ALL)
    val deny: List<String> = stringList(raw["deny"], "$source.tools.deny", issues) ?: emptyList()
    return allow to deny
  }

  private fun parseGates(raw: Any?, source: String, issues: MutableList<ValidationIssue>): ModeGates {
    if (raw == null) return ModeGates()
    if (raw !is Map<*, *>) {
      issues += ValidationIssue("$source.gates", Severity.WARN, "expected an object; got ${describe(raw)}")
      return ModeGates()
    }
    warnUnknownKeys(raw, GATES_KEYS, "$source.gates", issues)
    return ModeGates(
      approvals = booleanGate(raw["approvals"], "$source.gates.approvals", issues),
      commandFilter = booleanGate(raw["commandFilter"], "$source.gates.commandFilter", issues),
      protectedPaths = booleanGate(raw["protectedPaths"], "$source.gates.protectedPaths", issues),
    )
  }

  private fun booleanGate(raw: Any?, key: String, issues: MutableList<ValidationIssue>): Boolean {
    if (raw == null) return true
    if (raw is Boolean) return raw
    issues += ValidationIssue(key, Severity.WARN, "expected a boolean (true/false); got ${describe(raw)}")
    return true
  }

  private fun stringList(raw: Any?, key: String, issues: MutableList<ValidationIssue>): List<String>? {
    if (raw == null) return null
    if (raw !is List<*>) {
      issues += ValidationIssue(key, Severity.WARN, "expected an array of strings; got ${describe(raw)}")
      return null
    }
    val entries: List<String> = raw.mapNotNull { entry: Any? ->
      when {
        entry is String && entry.isNotBlank() -> entry
        entry == null -> {
          issues += ValidationIssue(key, Severity.WARN, "expected a string; got null; dropped")
          null
        }

        else -> {
          issues += ValidationIssue(key, Severity.WARN, "expected a string; got ${describe(entry)}; dropped")
          null
        }
      }
    }
    return entries
  }

  private fun readBuiltinResource(id: String): String? =
    ModeRegistry::class.java.getResourceAsStream("/modes/$id.json")?.use { input ->
      String(input.readBytes(), Charsets.UTF_8)
    }

  private fun readFileText(file: File): String? =
    try {
      if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (readException: Exception) {
      logger.warn("Failed to read ${file.absolutePath}: ${readException.message}")
      null
    }

  private enum class Severity { WARN, ERROR }

  private data class ValidationIssue(val key: String, val level: Severity, val reason: String)

  private fun logIssues(issues: List<ValidationIssue>) {
    if (issues.isEmpty()) return
    val rootLine = "┌─ Invalid gradum modes (fix ~/.gradum/modes/)"
    if (issues.any { it.level == Severity.ERROR }) logger.error(rootLine) else logger.warn(rootLine)
    issues.forEachIndexed { index, issue ->
      val connector = if (index == issues.lastIndex) "└─" else "├─"
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
        issues += ValidationIssue("$groupLabel.$key", Severity.WARN, "unknown property; ignored")
      }
  }

  private fun describe(value: Any?): String =
    value?.let { if (it is String) "\"$it\"" else it.toString() } ?: "null"

  private val FALLBACK_AGENT_MODE: AgentMode = AgentMode(
    id = DEFAULT_MODE_ID,
    label = "Full Agent Mode",
    description = "Reads, writes, executes complex tasks",
    aliases = listOf("write"),
  )
}
