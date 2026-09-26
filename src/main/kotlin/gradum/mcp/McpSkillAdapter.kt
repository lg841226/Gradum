/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpSkillAdapter.kt  2026-09-26 00:06:57 Changed by gwy
 */

package gradum.mcp

import gradum.SkillResult
import gradum.ToolMode
import gradum.makeFailure
import gradum.makeSuccess
import gradum.mcp.jsonrpc.RpcException
import gradum.skill.*
import gradum.utils.JsonUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * Exposes a single MCP server [McpTool] to Gradum's skill system as a [Skill],
 * so the model can call it directly in one step (e.g. `browser_navigate(url=...)`).
 *
 * Unlike an ordinary [gradum.skill.Skill], the schema is NOT trimmed: [getSchema]
 * emits the tool's full native JSON Schema (every property plus `required`), so
 * the model sees the complete parameter surface instead of only required fields.
 * This adapter is used for tools materialized on demand via [McpToolCatalog] —
 * it is never registered for all tools at startup. [execute] forwards the
 * caller's arguments to the server via [McpClient.callTool].
 *
 * Because [Skill.execute] is synchronous while the transport is coroutine-based,
 * [execute] bridges with [runBlocking]; the response is handled on the
 * transport's own IO scope, so blocking the calling thread only waits on the
 * deferred — it never deadlocks.
 */
internal class McpSkillAdapter(
  private val tool: McpTool,
  private val client: McpClient,
) : Skill() {

  override val skillName: String get() = tool.name
  override val description: String get() = tool.description ?: "MCP tool ${tool.name}"
  override val alias: String get() = tool.name

  /** External MCP tools may mutate arbitrary state; exclude READ_ONLY. */
  override val allowedToolModes: Set<ToolMode> = setOf(ToolMode.AGENT, ToolMode.EDIT)

  /** Full native schema; the [SchemaBuilder] DSL cannot faithfully express arbitrary JSON Schema. */
  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val requiredNames: List<String> = tool.inputSchema["required"]
      ?.jsonArray
      ?.mapNotNull { (it as? JsonPrimitive)?.content }
      ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val propertyMap: Map<String, Any> =
      (JsonUtil.fromJsonElement(tool.inputSchema["properties"] ?: buildJsonObject {}) as? Map<String, Any>)
        ?: emptyMap()
    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to description,
        "parameters" to mapOf(
          "type" to "object",
          "required" to requiredNames,
          "properties" to propertyMap,
        ),
      ),
    )
  }

  /** Unused because [getSchema] is overridden; kept to satisfy the abstract [Skill.schemaProperties]. */
  override val schemaProperties: SchemaBuilder.() -> Unit = {}

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val jsonArguments: JsonObject = JsonUtil.toJsonElement(arguments).jsonObject
    if (!isAuthorized(jsonArguments.toString(), context)) {
      return makeFailure(
        code = "TOOL_REJECTED",
        message = "MCP tool '${tool.name}' was not approved by the user.",
      )
    }
    return try {
      val resultElement = runBlocking { client.callTool(tool.name, jsonArguments) }
      makeSuccess(mapOf("content" to renderContent(resultElement)))
    } catch (rpcException: RpcException) {
      makeFailure(
        code = "MCP_ERROR",
        message = "MCP tool '${tool.name}' failed: ${rpcException.message}",
        context = mapOf("code" to rpcException.code),
      )
    } catch (exception: Exception) {
      makeFailure(
        code = "MCP_ERROR",
        message = "MCP tool '${tool.name}' failed: ${exception.message}",
      )
    }
  }

  /**
   * Resolves user consent to run this tool. Returns true when the tool was
   * already approved "always" this session, the user allowed a single run,
   * or the user chose "always" (remembered by name). Returns true without a
   * prompt when no ask capability is wired (tests / sub-agents that must not
   * block), and false when the user rejected or canceled.
   */
  private fun isAuthorized(argumentsText: String, context: SkillContext): Boolean {
    if (tool.name in context.authorizedMcpTools) return true
    val askScope: AskScope = context.scope ?: return true
    val decision: AskResult = askScope.askInteraction {
      title = l10n.key("gradum.ask.mcp_tool.title")
      details = l10n.raw("${tool.name}$argumentsText", source = Lang.EN)
      choices {
        item("once", Choice.Meaning.ALLOW_ONCE, labelKey = "gradum.ask.mcp_tool.choice.once")
        item("always", Choice.Meaning.ALLOW_ALWAYS, labelKey = "gradum.ask.mcp_tool.choice.always")
        item("no", Choice.Meaning.REJECT, labelKey = "gradum.ask.mcp_tool.choice.reject")
      }
      default = "no"
    }
    return when (decision) {
      is AskResult.Case ->
        when (decision.meaning) {
          Choice.Meaning.ALLOW_ONCE -> true
          Choice.Meaning.ALLOW_ALWAYS -> true.also {
            context.authorizedMcpTools.add(tool.name)
          }

          Choice.Meaning.REJECT -> false
        }

      else -> false
    }
  }

  /**
   * Renders a `tools/call` result element to the plain text the LLM should
   * see. Joins every `text` field found in the `content` array (MCP's
   * standard text blocks); falls back to the compact JSON when the result
   * carries no such blocks.
   */
  private fun renderContent(resultElement: JsonElement): String {
    val contentArray = resultElement.jsonObject["content"]?.jsonArray
    if (contentArray != null) {
      val textBlocks = contentArray.mapNotNull { item ->
        (item.jsonObject["text"] as? JsonPrimitive)?.content
      }
      if (textBlocks.isNotEmpty()) return textBlocks.joinToString(separator = "\n")
    }
    return resultElement.toString()
  }
}
