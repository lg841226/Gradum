/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownTableTest.kt  2026-07-06 Changed by gwy
 */

package gradum.idea.chat.ui

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradumMarkdownTableTest {

  @Test
  fun `plain text without pipes passes through unchanged`() {
    val md = "Hello world.\nThis is prose."
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    assertEquals(MarkdownSegment.Plain(md), segments[0])
  }

  @Test
  fun `minimal table splits into one Table segment`() {
    val md = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    val table = segments[0] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(listOf(listOf("a", "b")), table.rows)
    assertEquals(listOf(TextAlign.Start, TextAlign.Start), table.alignments)
  }

  @Test
  fun `surrounding prose yields alternating Plain and Table segments`() {
    val md = """
      Intro paragraph.

      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      | c  | d  |

      Outro paragraph.
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertTrue(segments[1] is MarkdownSegment.Table)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    val table = segments[1] as MarkdownSegment.Table
    assertEquals(listOf("a", "b"), table.rows[0])
    assertEquals(listOf("c", "d"), table.rows[1])
  }

  @Test
  fun `alignment markers are decoded from the separator row`() {
    val md = """
      | Left | Center | Right |
      | :--- | :----: | ----: |
      | a    | b      | c     |
    """.trimIndent()
    val table = splitMarkdownAtTables(md).single() as MarkdownSegment.Table
    assertEquals(
      listOf(TextAlign.Start, TextAlign.Center, TextAlign.End),
      table.alignments
    )
  }

  @Test
  fun `escaped pipe inside a cell is preserved`() {
    val md = """
      | H1 | H2 |
      | -- | -- |
      | a  | x\|y |
    """.trimIndent()
    val table = splitMarkdownAtTables(md).single() as MarkdownSegment.Table
    assertEquals(listOf("a", "x|y"), table.rows.single())
  }

  @Test
  fun `table without leading or trailing pipe still parses`() {
    val md = """
      H1 | H2
      -- | --
      a  | b
    """.trimIndent()
    val table = splitMarkdownAtTables(md).single() as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(listOf(listOf("a", "b")), table.rows)
  }

  @Test
  fun `separator row without a header line is not a table`() {
    // `| -- | -- |` alone (no preceding header line) is prose, not a table.
    val md = "Prose line.\n| -- | -- |\nMore prose."
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `consecutive tables with no prose between them stay separate`() {
    val md = """
      | A | B |
      | - | - |
      | 1 | 2 |

      | C | D |
      | - | - |
      | 3 | 4 |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    val tables = segments.filterIsInstance<MarkdownSegment.Table>()
    assertEquals(2, tables.size)
    assertEquals(listOf("A", "B"), tables[0].header)
    assertEquals(listOf("C", "D"), tables[1].header)
  }

  // ----- Robustness: a malformed table must not eat surrounding prose. -----

  @Test
  fun `header line with no pipe and a valid separator falls back to plain`() {
    // Looks like a separator on its own but no real header above it.
    val md = "Some intro prose.\n| --- | --- |\nMore prose."
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `body row with mismatched column count is dropped, not the whole table`() {
    val md = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      | only-one-cell |
      | c  | d  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    val table = segments[0] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), table.rows)
  }

  @Test
  fun `a table with zero body rows is treated as plain prose`() {
    val md = "Prose.\n| H1 | H2 |\n| -- | -- |\nMore prose."
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `a pathologically long line is dropped, not promoted to a single cell`() {
    val longLine = "x".repeat(10_000)
    val md = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      | $longLine | $longLine |
      | c  | d  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    val table = segments.single() as MarkdownSegment.Table
    // The two well-formed short rows stay; the over-length row is skipped.
    assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), table.rows)
  }

  @Test
  fun `a table past the row cap is truncated and the rest falls through to plain`() {
    val rows = (1..600).joinToString("\n") { "| r$it |" }
    val md = """
      | H1 |
      | -- |
      $rows
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    val tables: List<MarkdownSegment.Table> = segments.filterIsInstance<MarkdownSegment.Table>()
    val plains: List<MarkdownSegment.Plain> = segments.filterIsInstance<MarkdownSegment.Plain>()
    // First 500 rows become a table; the trailing 100 rows are dumped as
    // plain prose so the user still sees the data instead of a silent cutoff.
    assertEquals(1, tables.size)
    assertEquals(500, tables[0].rows.size)
    assertEquals(1, plains.size)
    val plainText: String = plains[0].text
    assertTrue(plainText.contains("r501"))
    assertTrue(plainText.contains("r600"))
    assertTrue(!plainText.contains("r1 |"))
  }

  @Test
  fun `prose with stray pipes is not mis-parsed as a table`() {
    val md = """
      First sentence with a | in the middle.
      Second sentence with | another | pipe.
      Third sentence | final.
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `a line ending in the middle of a malformed table stops collection cleanly`() {
    val md = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      not a table row
      | c  | d  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    // The first table picks up `a | b`, the prose "not a table row" goes to
    // plain, and `c | d` becomes a new table.
    val tables = segments.filterIsInstance<MarkdownSegment.Table>()
    assertEquals(1, tables.size)
    assertEquals(listOf(listOf("a", "b")), tables[0].rows)
    val plain = segments.filterIsInstance<MarkdownSegment.Plain>()
    assertEquals(1, plain.size)
    assertTrue(plain[0].text.contains("not a table row"))
  }
}
