/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WebSearchSkillTest.kt  2026-08-26 11:20:58 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSearchSkillTest {

  private val skill = WebSearchSkill()

  @Suppress("UNCHECKED_CAST")
  private fun functionOf(schema: Map<String, Any>): Map<String, Any> =
    schema["function"] as Map<String, Any>

  @Suppress("UNCHECKED_CAST")
  private fun paramsOf(schema: Map<String, Any>): Map<String, Any> =
    functionOf(schema)["parameters"] as Map<String, Any>

  private fun ctx() = SkillContext(
    toolMode = ToolMode.AGENT,
    projectRoot = System.getProperty("java.io.tmpdir"),
    modelName = "qwen2.5:7b",
  )

  @Test
  fun `schema function name matches skillName`() {
    assertEquals(
      "search_web",
      functionOf(skill.getSchema(ctx()))["name"]
    )
  }


  @Test
  fun `schema required contains query`() {
    @Suppress("UNCHECKED_CAST")
    val required = paramsOf(schema = skill.getSchema(context = ctx()))["required"] as List<String>
    assertTrue("query" in required)
  }

  @Test
  fun `schema properties contain query max_results search_depth`() {
    @Suppress("UNCHECKED_CAST")
    val props = paramsOf(schema = skill.getSchema(context = ctx()))["properties"] as Map<String, Any>
    assertTrue("query" in props)
    assertTrue("max_results" in props)
    assertTrue("search_depth" in props)
  }

  @Test
  fun `schema search_depth enum has basic and advanced`() {
    @Suppress("UNCHECKED_CAST")
    val props = paramsOf(schema = skill.getSchema(context = ctx()))["properties"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    val depthSchema = props["search_depth"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    val enumValues = depthSchema["enum"] as List<String>
    assertTrue("basic" in enumValues)
    assertTrue("advanced" in enumValues)
  }

  @Test
  fun `simple model schema has query`() {
    @Suppress("UNCHECKED_CAST")
    val props = paramsOf(schema = skill.getSchema(context = ctx()))["properties"] as Map<String, Any>
    assertTrue("query" in props)
  }

  @Test
  fun `empty query returns INVALID_PARAMETER`() {
    val result = skill.execute(arguments = mapOf("query" to ""), context = ctx())
    assertTrue(result is SkillResult.Failure)
    assertEquals(
      "INVALID_PARAMETER",
      result.code
    )
  }

  @Test
  fun `blank query returns INVALID_PARAMETER`() {
    val result = skill.execute(arguments = mapOf("query" to "   "), context = ctx())
    assertTrue(result is SkillResult.Failure)
    assertEquals(
      "INVALID_PARAMETER",
      result.code
    )
  }

  @Test
  fun `missing query returns INVALID_PARAMETER`() {
    val result = skill.execute(arguments = emptyMap(), context = ctx())
    assertTrue(result is SkillResult.Failure)
    assertEquals(
      "INVALID_PARAMETER",
      result.code
    )
  }

  @Test
  fun `query exceeding max length returns INVALID_PARAMETER`() {
    val longQuery = "a".repeat(n = 5001)
    val result = skill.execute(arguments = mapOf("query" to longQuery), context = ctx())
    assertTrue(result is SkillResult.Failure)
    assertEquals(
      "INVALID_PARAMETER",
      result.code
    )
  }


  @Test
  fun `skill name and alias`() {
    assertEquals(
      "search_web",
      skill.skillName
    )
    assertEquals(
      "Searched",
      skill.alias
    )
  }

  @Test
  fun `search_web is available in all tool modes`() {
    val modes = skill.allowedToolModes
    assertTrue(ToolMode.AGENT in modes)
    assertTrue(ToolMode.EDIT in modes)
    assertTrue(ToolMode.READ_ONLY in modes)
  }

  @Test
  fun `schema does not expose include_favicon to the LLM`() {
    @Suppress("UNCHECKED_CAST")
    val props = paramsOf(schema = skill.getSchema(context = ctx()))["properties"] as Map<String, Any>
    assertTrue(
      "include_favicon" !in props,
      "include_favicon must be hardcoded server-side, never exposed to the LLM"
    )
  }

  @Test
  fun `schema only requires query`() {
    @Suppress("UNCHECKED_CAST")
    val required = paramsOf(schema = skill.getSchema(context = ctx()))["required"] as List<String>
    assertEquals(
      listOf("query"),
      required
    )
  }
}
