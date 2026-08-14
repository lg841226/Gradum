/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchedRendererTest.kt  2026-08-14 Changed by gwy
 */
package gradum.idea.chat.ui.chat.skill

import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the `parseContent` half of [SearchedRenderer].
 *
 * The `render` composable is harder to unit-test (it needs a full
 * Compose runtime + Jewel theme), but the data-shaping half is what
 * decides whether a row can show a favicon at all. If `parseContent`
 * drops `faviconUrl` or normalises it away, the renderer has no way to
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
    // The renderer reads `faviconUrl` directly from the per-row map at
    // render time; `parseContent` only forwards the whole list. This
    // test documents the contract so a future refactor that strips
    // unknown keys will fail loudly instead of silently breaking
    // favicons.
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
    // Older server payloads (or non-Tavily providers) may not have the
    // `faviconUrl` field. `parseContent` must NOT throw — the renderer
    // is responsible for the `null`/blank fallback to the Web icon.
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
    // Defensive: if the server payload ever omits `results` (network
    // error body, etc.), the parser must default to an empty list
    // rather than throwing a ClassCastException.
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
    // Renderers are looked up in the registry by their alias, and the
    // header icon is the very first thing the user sees. Both must
    // be stable, otherwise existing chat transcripts (which store
    // `aliasName = "Searched"`) would render the wrong UI.
    assertEquals("Searched", renderer.alias())
    assertNotNull(renderer.iconKey())
  }
}
