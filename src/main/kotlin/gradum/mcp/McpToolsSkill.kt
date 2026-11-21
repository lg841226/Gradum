package gradum.mcp

import gradum.SkillResult
import gradum.makeSuccess
import gradum.skill.Skill
import gradum.skill.SkillContext
import gradum.skill.dsl.SchemaBuilder
import gradum.skill.dsl.string

internal class McpToolsSkill : Skill() {

  override val skillName: String = "mcp_tools"
  override val alias: String = "MCP Tools"
  override val description: String =
    "Discover MCP tools. Call with no arguments to list all tools grouped by " +
      "function. Call with `query` (a tool name, keyword, or group) to search: " +
      "every matching tool is exposed with its full schema and becomes directly " +
      "callable in the same session."


  override val simpleDescription: String =
    "List or search MCP tools by group. Matched tools become directly callable."

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "query",
      description = "Search text: a tool name, keyword, or functional group. Blank lists all tools.",
    )
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val query: String = (arguments["query"] as? String)?.trim() ?: ""
    return if (query.isEmpty()) {
      buildDirectoryListing()
    } else {
      materializeMatches(query, context)
    }
  }

  private fun buildDirectoryListing(): SkillResult {
    val groups: List<Map<String, Any>> = McpToolCatalog.groupedListing().map { group ->
      mapOf(
        "group" to group.group,
        "tools" to group.tools.map { tool ->
          mapOf("name" to tool.name, "description" to tool.description)
        },
      )
    }
    return makeSuccess(
      mapOf(
        "exposed_tools" to McpToolCatalog.toolCount(),
        "groups" to groups,
        "hint" to "Call mcp_tools again with a query (tool name, keyword, or group) " +
          "to materialize a tool's full schema and make it directly callable.",
      )
    )
  }

  private fun materializeMatches(query: String, context: SkillContext): SkillResult {
    val matches: List<McpToolBrief> = McpToolCatalog.search(query)
    if (matches.isEmpty()) {
      return makeSuccess(
        mapOf(
          "query" to query,
          "matched" to 0,
          "available_groups" to McpToolCatalog.groupedListing().map { it.group },
          "hint" to "No MCP tool matched the query. Refine it, or call mcp_tools with " +
            "no arguments to browse the full group listing."
        )
      )
    }

    context.materializedMcpTools.newRound()
    matches.forEach { match -> context.materializedMcpTools.add(match.name) }

    val detailed: List<Map<String, Any>> = matches.map { match ->
      val schema: Map<String, Any>? = McpToolCatalog.schemaOf(match.name)
      mapOf(
        "name" to match.name,
        "description" to match.description,
        "schema" to (schema ?: emptyMap<Any, Any>()),
        "group" to McpToolCatalog.groupOf(match.name)
      )
    }
    return makeSuccess(
      mapOf(
        "query" to query,
        "matched" to matches.size,
        "materialized" to matches.map { it.name },
        "tools" to detailed,
        "hint" to "These tools are now directly callable for the rest of this session " +
          "with their full schema exposed."
      )
    )
  }
}
