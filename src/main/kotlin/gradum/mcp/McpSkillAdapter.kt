/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * McpSkillAdapter.kt  2026-09-25 Changed by gwy
 */

package gradum.mcp

import gradum.SkillResult
import gradum.ToolMode
import gradum.makeFailure
import gradum.makeSuccess
import gradum.mcp.jsonrpc.RpcException
import gradum.skill.SchemaBuilder
import gradum.skill.Skill
import gradum.skill.SkillContext
import gradum.skill.boolean
import gradum.skill.integer
import gradum.skill.string
import gradum.skill.stringArray
import gradum.utils.JsonUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Exposes a single MCP server [McpTool] to Gradum's skill system as a
 * [Skill]. Its schema is rendered from the tool's advertised JSON schema
 * ([McpTool.inputSchema]), and [execute] forwards the caller's arguments to
 * the server via [McpClient.callTool].
 *
 * Because [Skill.execute] is synchronous while the transport is
 * coroutine-based, [execute] bridges with [runBlocking]; the response is
 * handled on the transport's own IO scope, so blocking the calling thread
 * only waits on the deferred — it never deadlocks.
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

  private val requiredNames: Set<String> = tool.inputSchema["required"]
    ?.jsonArray
    ?.mapNotNull { (it as? JsonPrimitive)?.content }
    ?.toSet()
    ?: emptySet()

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    val properties: JsonObject = tool.inputSchema["properties"]?.jsonObject ?: buildJsonObject {}
    for ((propertyName, propertySchema) in properties) {
      val property = propertySchema.jsonObject
      val propertyDescription: String =
        (property["description"] as? JsonPrimitive)?.content ?: ""
      val propertyRequired: Boolean = propertyName in requiredNames

      when ((property["type"] as? JsonPrimitive)?.content) {
        "integer", "number" -> integer(propertyName, propertyDescription, propertyRequired)
        "boolean" -> boolean(propertyName, propertyDescription, propertyRequired)
        "array" -> stringArray(propertyName, propertyDescription, propertyRequired)
        else -> string(propertyName, propertyDescription, propertyRequired)
      }
    }
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val jsonArguments: JsonObject = JsonUtil.toJsonElement(arguments).jsonObject
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
