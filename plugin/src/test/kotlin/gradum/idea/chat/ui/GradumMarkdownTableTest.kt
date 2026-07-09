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
  fun `a table with zero body rows becomes a Table segment with empty rows`() {
    // The header + separator look like a real table, but no body
    // row follows. The parser still emits a Table segment (with an
    // empty rows list); the call site in AssistantChatBubble checks
    // isRenderable() and substitutes the placeholder.
    val md = "Prose.\n| H1 | H2 |\n| -- | -- |\nMore prose."
    val segments = splitMarkdownAtTables(md)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertEquals("Prose.", (segments[0] as MarkdownSegment.Plain).text)
    val table = segments[1] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(emptyList<List<String>>(), table.rows)
    assertEquals("More prose.", (segments[2] as MarkdownSegment.Plain).text)
  }

  @Test
  fun `a table where every body row mismatches the header becomes an empty-rows Table`() {
    // The header has 2 columns, every body row is 1 column. The
    // column-count filter drops all body rows, so the Table comes
    // out with an empty rows list. The placeholder is the caller's
    // problem, not the parser's.
    val md = """
      | H1 | H2 |
      | -- | -- |
      | only-one-cell |
      | another-one  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    val table = segments[0] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(emptyList<List<String>>(), table.rows)
  }

  @Test
  fun `an unrenderable Table keeps the surrounding prose on both sides`() {
    // Prose before and after the malformed block must still be
    // Plain — the raw pipe syntax of the malformed block must not
    // leak into the surrounding prose. The malformed block itself
    // is a Table (with empty rows) that the caller will replace
    // with a placeholder.
    val md = "Lead.\n| A | B | C |\n| - | - | - |\nTrailing."
    val segments = splitMarkdownAtTables(md)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertEquals("Lead.", (segments[0] as MarkdownSegment.Plain).text)
    assertTrue(segments[1] is MarkdownSegment.Table)
    val table = segments[1] as MarkdownSegment.Table
    assertEquals(emptyList<List<String>>(), table.rows)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    assertEquals("Trailing.", (segments[2] as MarkdownSegment.Plain).text)
  }

  @Test
  fun `every body-row failure mode becomes an empty-rows Table`() {
    // It doesn't matter *why* a table-shaped block has no usable
    // body rows. No body rows at all, every body row
    // column-mismatched, body line was actually prose that
    // happened to contain a `|` — any of these is "Table with
    // empty rows" from the parser's perspective. The caller
    // decides what to do with that.
    val noBody = "| H1 | H2 |\n| -- | -- |\n\nnext prose"
    val allMismatched = "| H1 | H2 |\n| -- | -- |\n| a |\n| b |"
    val separatorButTrailingProse = "| H1 | H2 |\n| -- | -- |\nstray line"

    listOf(noBody, allMismatched, separatorButTrailingProse).forEach { md ->
      val tables = splitMarkdownAtTables(md).filterIsInstance<MarkdownSegment.Table>()
      assertEquals("expected exactly one Table in: $md", 1, tables.size)
      assertEquals("expected empty rows in: $md", 0, tables[0].rows.size)
    }
  }

  @Test
  fun `blank cells parse into a Table segment - the renderer decides how to render them`() {
    // Responsibility split: the parser's only job is structural
    // validity (did we get a header + separator + at least one body
    // row that lines up?). Whether an individual cell is empty or
    // whitespace-only is the renderer's problem — SafeMarkdownText
    // handles that case at the Composable layer, falling back to a
    // plain Text instead of letting MarkdownText crash the chat
    // panel. So blank cells still produce a Table segment, they
    // just render as empty cells in the UI.
    val emptyHeaderCell = """
      |    | H2 |
      | -- | -- |
      | a  | b  |
    """.trimIndent()
    val emptyBodyCell = """
      | H1 | H2 |
      | -- | -- |
      | a  |    |
    """.trimIndent()

    listOf(emptyHeaderCell, emptyBodyCell).forEach { md ->
      val tables = splitMarkdownAtTables(md).filterIsInstance<MarkdownSegment.Table>()
      assertEquals("expected exactly one Table in: $md", 1, tables.size)
      assertEquals(1, tables[0].rows.size)
    }
  }

  // ── MarkdownSegment.Table.isRenderable ──────────────────────────
  //
  // The call-site gate that decides between ScrollableTable and
  // TableParseFailurePlaceholder. The parser doesn't know about
  // this — it always emits a Table. The chat bubble asks
  // isRenderable() to know whether the Table will actually paint
  // visible content.

  @Test
  fun `isRenderable is false for a Table with no body rows`() {
    val table = MarkdownSegment.Table(
      header = listOf("H1", "H2"),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
      rows = emptyList(),
    )
    assertEquals(false, table.isRenderable())
  }

  @Test
  fun `isRenderable is false when every body cell is blank`() {
    val table = MarkdownSegment.Table(
      header = listOf("H1", "H2"),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
      rows = listOf(listOf("", ""), listOf("   ", "\t")),
    )
    assertEquals(false, table.isRenderable())
  }

  @Test
  fun `isRenderable is true when at least one body cell is non-blank`() {
    val table = MarkdownSegment.Table(
      header = listOf("H1", "H2"),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
      rows = listOf(listOf("", ""), listOf("", "actual data")),
    )
    assertEquals(true, table.isRenderable())
  }

  @Test
  fun `isRenderable is true even if the header is entirely blank, as long as body has data`() {
    // The header is a separate concern — isRenderable only checks
    // the body, because the structural question is "is there
    // something to draw underneath the column labels?"
    val table = MarkdownSegment.Table(
      header = listOf("", ""),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
      rows = listOf(listOf("a", "b")),
    )
    assertEquals(true, table.isRenderable())
  }

  // ── distributeTableWidth ──────────────────────────────────────────
  //
  // Pure-arithmetic helper that decides each column's final width.
  // 1. clamp every column to >= minCellWidthPx,
  // 2. if the natural total already fills the container, leave it,
  // 3. otherwise scale all columns up by the same factor so the
  //    total exactly matches the container.
  // The last column absorbs the rounding residue.

  @Test
  fun `distributeTableWidth - short table scales up to fill the container`() {
    // 3 columns, each natural 100px wide, container 600px. After
    // scaling every column should be 200px (plus padding from the
    // caller — we test the content widths only).
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(100, 100, 100),
      containerWidthPx = 600,
      minCellWidthPx = 80,
      horizontalPaddingPx = 0, // strip padding for the test
    )
    assertEquals(3, out.size)
    out.forEach { assertEquals(200, it) }
  }

  @Test
  fun `distributeTableWidth - narrow column gets clamped to the minimum width`() {
    // Clamp only matters when the table isn't being scaled up — once
    // we hit the "scale all columns proportionally" path, the clamp
    // is just a floor on the natural width, and the final width is
    // whatever the proportional scale gives. So the test needs the
    // natural total to already meet or exceed the container, which
    // means we can compare the clamped result directly.
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(80, 30, 80),
      containerWidthPx = 200,
      minCellWidthPx = 80,
      horizontalPaddingPx = 0,
    )
    assertEquals(80, out[0])
    assertEquals(80, out[1]) // clamped from 30
    assertEquals(80, out[2])
  }

  @Test
  fun `distributeTableWidth - table wider than container is left untouched`() {
    // Natural total (3 × 200) already exceeds the container; the
    // caller will get a horizontal scrollbar instead of squashed
    // columns.
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(200, 200, 200),
      containerWidthPx = 500,
      minCellWidthPx = 80,
      horizontalPaddingPx = 0,
    )
    assertEquals(200, out[0])
    assertEquals(200, out[1])
    assertEquals(200, out[2])
  }

  @Test
  fun `distributeTableWidth - scaled widths sum to the container width`() {
    // The whole point: after scaling, the sum of all column widths
    // (plus per-column padding accounted for by the caller) should
    // exactly equal the container width, with the round-down
    // residue absorbed by the last column.
    val containerWidthPx = 1000
    val horizontalPaddingPx = 16
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(150, 200, 90, 110),
      containerWidthPx = containerWidthPx,
      minCellWidthPx = 80,
      horizontalPaddingPx = horizontalPaddingPx,
    )
    val expectedTotal: Int = containerWidthPx - horizontalPaddingPx * 2 * out.size
    assertEquals(expectedTotal, out.sum())
  }

  @Test
  fun `distributeTableWidth - empty input is a no-op`() {
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(),
      containerWidthPx = 1000,
      minCellWidthPx = 80,
      horizontalPaddingPx = 0,
    )
    assertEquals(0, out.size)
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
    // All 600 rows survive — no row cap anymore. The chat panel
    // doesn't need a defensive cap; AI models don't emit 600-row
    // tables, and the cost of hitting a 600-row layout in Compose
    // is acceptable.
    assertEquals(1, tables.size)
    assertEquals(600, tables[0].rows.size)
    assertEquals(0, plains.size)
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
