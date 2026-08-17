/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchedRendererTest.kt  2026-08-16 17:48:39 Changed by gwy
 */
package gradum.idea.chat.ui.chat.skill

import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import org.junit.Assert.*
import org.junit.Test

/**
 * Behavior tests for the `parseContent` half of [SearchedRenderer].
 *
 * The `render` composable is harder to unit-test (it needs a full
 * Compose runtime + Jewel theme), but the data-shaping half is what
 * decides whether a row can show a favicon at all. If `parseContent`
 * drops `faviconUrl` or normalizes it away, the renderer has no way to
 * recover it. These tests guard the contract.
 */
class SearchedRendererTest {

  private val renderer: SearchedRenderer = SearchedRenderer()

  @Test
  fun `parseContent copies query from arguments`() {
    val content: ToolCallContent = renderer.parseContent(
      arguments = mapOf("query" to "Kotlin coroutines"),
      result = mapOf("results" to emptyList<Map<String, Any>>())
    )
    assertEquals("Kotlin coroutines", content.fieldMap["query"])
  }

  @Test
  fun `parseContent counts total results from result list`() {
    val resultRows: List<Map<String, Any>> = listOf(
      mapOf("title" to "a", "snippet" to "s", "url" to "u1", "faviconUrl" to ""),
      mapOf("title" to "b", "snippet" to "s", "url" to "u2", "faviconUrl" to "https://x.test/y.ico"),
    )
    val content: ToolCallContent = renderer.parseContent(
      arguments = mapOf("query" to "x"),
      result = mapOf("results" to resultRows)
    )
    assertEquals(2, content.fieldMap["totalResults"])
  }

  @Test
  fun `parseContent preserves faviconUrl inside the result row payload`() {
    val row: Map<String, Any> = mapOf(
      "title" to "Tavily",
      "snippet" to "AI-powered search",
      "url" to "https://tavily.com",
      "faviconUrl" to "https://cdn.tavily.com/favicon.ico",
    )
    val content: ToolCallContent = renderer.parseContent(
      arguments = mapOf("query" to "tavily"),
      result = mapOf("results" to listOf(row))
    )

    @Suppress("UNCHECKED_CAST")
    val first: Map<String, Any> =
      (content.fieldMap["results"] as List<Map<String, Any>>).first()
    assertEquals("https://cdn.tavily.com/favicon.ico", first["faviconUrl"])
  }

  @Test
  fun `parseContent tolerates missing faviconUrl key`() {
    val row: Map<String, Any> = mapOf(
      "title" to "Example",
      "snippet" to "No favicon",
      "url" to "https://example.com",
    )
    val content: ToolCallContent = renderer.parseContent(
      arguments = mapOf("query" to "x"),
      result = mapOf("results" to listOf(row))
    )

    @Suppress("UNCHECKED_CAST")
    val first: Map<String, Any> =
      (content.fieldMap["results"] as List<Map<String, Any>>).first()
    val faviconUrl: String? = first["faviconUrl"] as? String
    assertTrue(
      "missing faviconUrl must not crash, must not invent a URL",
      faviconUrl.isNullOrEmpty()
    )
  }

  @Test
  fun `parseContent tolerates empty results list`() {
    val content: ToolCallContent = renderer.parseContent(
      arguments = mapOf("query" to "nothing"),
      result = mapOf("results" to emptyList<Map<String, Any>>())
    )
    assertEquals(0, content.fieldMap["totalResults"])
    @Suppress("UNCHECKED_CAST")
    val rows: List<Map<String, Any>> =
      content.fieldMap["results"] as List<Map<String, Any>>
    assertEquals(0, rows.size)
  }

  @Test
  fun `parseContent tolerates results key missing entirely`() {
    val content: ToolCallContent = renderer.parseContent(
      arguments = mapOf("query" to "x"),
      result = emptyMap()
    )

    @Suppress("UNCHECKED_CAST")
    val rows: List<Map<String, Any>> =
      content.fieldMap["results"] as List<Map<String, Any>>
    assertEquals(0, rows.size)
    assertEquals(0, content.fieldMap["totalResults"])
  }

  @Test
  fun `renderer exposes Searched alias and Web icon`() {
    assertEquals("Searched", renderer.alias())
    assertNotNull(renderer.iconKey())
  }
}
