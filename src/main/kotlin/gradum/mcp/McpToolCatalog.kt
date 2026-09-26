/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpToolCatalog.kt  2026-09-26 Changed by gwy
 */

package gradum.mcp

import gradum.ToolMode
import java.util.concurrent.ConcurrentHashMap

/**
 * A one-line summary of an MCP tool for the directory listing: its callable
 * name and a short description.
 */
data class McpToolBrief(
  val name: String,
  val description: String,
)

/** A functional group of MCP tools, each with a one-line summary. */
data class McpToolGroup(
  val group: String,
  val tools: List<McpToolBrief>,
)

/**
 * Global registry of every connected MCP tool, keyed by tool name, together
 * with the functional grouping and keyword search the `mcp_tools` directory
 * skill exposes.
 *
 * This catalog is the single source of truth for MCP tool definitions; it is
 * filled once at startup by [McpConnectionManager] and read by:
 *  - [McpToolsSkill], to list groups and search tools;
 *  - [gradum.agent.Agent], to inject a session's materialized tools' schemas
 *    into the model's per-turn tool list;
 *  - [gradum.agent.ToolExecutor], to dispatch calls to materialized tools.
 *
 * Exposure is deliberately session-scoped: a tool only becomes visible and
 * callable once the directory skill adds its name to the session's
 * [gradum.skill.SkillContext.materializedMcpTools] set. Definitions live here
 * (server-lifetime), but visibility lives in the per-session set.
 */
object McpToolCatalog {

  private val adaptersByTool: MutableMap<String, McpSkillAdapter> = ConcurrentHashMap()

  internal fun register(tool: McpTool, client: McpClient) {
    adaptersByTool[tool.name] = McpSkillAdapter(tool, client)
  }

  /** Drops every registered adapter; paired with [McpConnectionManager.close]. */
  fun clear() {
    adaptersByTool.clear()
  }

  /** Number of registered MCP tools. */
  fun toolCount(): Int = adaptersByTool.size

  /** Returns the full-schema adapter for [name], or null when not registered. */
  internal fun adapterFor(name: String): McpSkillAdapter? = adaptersByTool[name]

  /**
   * Returns the full OpenAI function schema for every name in [names] that
   * permits [toolMode], in a stable (sorted by tool name) order. Used by
   * [gradum.agent.Agent] to add a session's materialized tools to the model's
   * tool list.
   */
  fun fullSchemas(names: Set<String>, toolMode: ToolMode): List<Map<String, Any>> =
    names.mapNotNull { adaptersByTool[it] }
      .filter { adapter -> adapter.allows(toolMode) }
      .sortedBy { adapter -> adapter.skillName }
      .map { adapter -> adapter.getSchema() }

  /** Functional group assigned to [toolName] by the keyword rules. */
  fun groupOf(toolName: String): String =
    GROUP_RULES.firstOrNull { (_, keywords) -> keywords.any { toolName.contains(it) } }
      ?.first
      ?: OTHER_GROUP

  /**
   * The full directory grouped by function, in rule order with unmatched tools
   * falling back to [OTHER_GROUP] last.
   */
  fun groupedListing(): List<McpToolGroup> {
    val grouped: LinkedHashMap<String, MutableList<McpToolBrief>> = linkedMapOf()
    allAdapters().forEach { adapter ->
      val group: String = groupOf(adapter.skillName)
      grouped.getOrPut(group) { mutableListOf() }.add(brief(adapter))
    }
    return grouped
      .map { (group, tools) -> McpToolGroup(group = group, tools = tools) }
      .sortedBy { entry -> if (entry.group == OTHER_GROUP) 1 else 0 }
  }

  /**
   * Matches [query] (case-insensitive) against tool names, descriptions, and
   * group labels. Blank query returns every tool. Matches are returned in
   * tool-name order.
   */
  fun search(query: String): List<McpToolBrief> {
    val normalized: String = query.trim().lowercase()
    val source = allAdapters()
    if (normalized.isEmpty()) return source.map { brief(it) }
    return source
      .filter { adapter ->
        val group: String = groupOf(adapter.skillName)
        adapter.skillName.contains(normalized, ignoreCase = true) ||
          adapter.description.contains(normalized, ignoreCase = true) ||
          group.contains(normalized, ignoreCase = true)
      }
      .map { brief(it) }
  }

  /** Full OpenAI schema for a single tool, or null when unknown. */
  fun schemaOf(name: String): Map<String, Any>? = adaptersByTool[name]?.getSchema()

  private fun allAdapters(): List<McpSkillAdapter> =
    adaptersByTool.values.sortedBy { it.skillName }

  private fun brief(adapter: McpSkillAdapter): McpToolBrief =
    McpToolBrief(
      name = adapter.skillName,
      description = if (adapter.description.length > BRIEF_LENGTH) {
        adapter.description.take(BRIEF_LENGTH) + "..."
      } else {
        adapter.description
      },
    )

  private const val OTHER_GROUP = "Other"
  private const val BRIEF_LENGTH = 90

  /**
   * Ordered (group, keyword) rules. The first rule whose keyword appears in a
   * tool name assigns its group; tools matching none fall back to [OTHER_GROUP].
   * Rule order matters, so broad keywords (e.g. `fill`) appear after narrower
   * ones to avoid hijacking tools that belong to a more specific group.
   */
  private val GROUP_RULES: List<Pair<String, List<String>>> = listOf(
    "Navigation" to listOf("navigate", "new_page", "go_back", "reload", "wait_for", "close", "install"),
    "Interaction" to listOf("click", "hover", "drag", "press_key", "check", "uncheck", "handle_dialog"),
    "Forms" to listOf("fill", "select_option", "option", "form", "input"),
    "Typing" to listOf("type", "insert_text"),
    "Snapshot & Visual" to listOf("snapshot", "screenshot", "aria", "visual"),
    "Network & Requests" to listOf("network", "request", "response"),
    "Browser & Tabs" to listOf("tab", "launch", "pages", "browser"),
    "Console & Logs" to listOf("console", "log", "performance", "metrics"),
    OTHER_GROUP to emptyList(),
  )
}
