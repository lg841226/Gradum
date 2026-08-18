/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownTableTest.kt  2026-07-17 23:06:55 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradumMarkdownTableTest {

  @Test
  fun `plain text without pipes passes through unchanged`() {
    val markdown = "Hello world.\nThis is prose."
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    assertEquals(MarkdownSegment.Plain(markdown), segments[0])
  }

  @Test
  fun `minimal table splits into one Table segment`() {
    val markdown: String = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    val table = segments[0] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(listOf(listOf("a", "b")), table.rows)
    assertEquals(listOf(TextAlign.Start, TextAlign.Start), table.alignments)
  }

  @Test
  fun `surrounding prose yields alternating Plain and Table segments`() {
    val markdown: String = """
      Intro paragraph.

      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      | c  | d  |

      Outro paragraph.
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
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
    val markdown: String = """
      | Left | Center | Right |
      | :--- | :----: | ----: |
      | a    | b      | c     |
    """.trimIndent()
    val table = splitMarkdownAtTables(markdown).single() as MarkdownSegment.Table
    assertEquals(
      listOf(TextAlign.Start, TextAlign.Center, TextAlign.End),
      table.alignments
    )
  }

  @Test
  fun `escaped pipe inside a cell is preserved`() {
    val markdown: String = """
      | H1 | H2 |
      | -- | -- |
      | a  | x\|y |
    """.trimIndent()
    val table = splitMarkdownAtTables(markdown).single() as MarkdownSegment.Table
    assertEquals(listOf("a", "x|y"), table.rows.single())
  }

  @Test
  fun `table without leading or trailing pipe still parses`() {
    val markdown: String = """
      H1 | H2
      -- | --
      a  | b
    """.trimIndent()
    val table = splitMarkdownAtTables(markdown).single() as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(listOf(listOf("a", "b")), table.rows)
  }

  @Test
  fun `separator row without a header line is not a table`() {
    // `| -- | -- |` alone (no preceding header line) is prose, not a table.
    val markdown = "Prose line.\n| -- | -- |\nMore prose."
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `consecutive tables with no prose between them stay separate`() {
    val markdown: String = """
      | A | B |
      | - | - |
      | 1 | 2 |

      | C | D |
      | - | - |
      | 3 | 4 |
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    val tables = segments.filterIsInstance<MarkdownSegment.Table>()
    assertEquals(2, tables.size)
    assertEquals(listOf("A", "B"), tables[0].header)
    assertEquals(listOf("C", "D"), tables[1].header)
  }

  // A malformed table must not eat surrounding prose — these tests pin
  // that the parser never promotes a mis-shaped block into a Table that
  // would replace the surrounding Plain segments.

  @Test
  fun `header line with no pipe and a valid separator falls back to plain`() {
    // Looks like a separator on its own but no real header above it.
    val markdown = "Some intro prose.\n| --- | --- |\nMore prose."
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `body row with mismatched column count is padded, not dropped`() {
    val markdown: String = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      | only-one-cell |
      | c  | d  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    val table = segments[0] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    // GFM pads the short row with an empty trailing cell — the row is
    // preserved, not silently deleted.
    assertEquals(
      listOf(listOf("a", "b"), listOf("only-one-cell", ""), listOf("c", "d")),
      table.rows
    )
  }

  @Test
  fun `a table with zero body rows becomes a Table with empty rows`() {
    // The header + separator look like a real table, but no body
    // row follows. The parser still emits a Table segment (with an
    // empty rows list); the call site in AssistantChatBubble checks
    // isRenderable() and substitutes the placeholder.
    val markdown = "Prose.\n| H1 | H2 |\n| -- | -- |\nMore prose."
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertEquals("Prose.", (segments[0] as MarkdownSegment.Plain).text)
    val table = segments[1] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(emptyList<List<String>>(), table.rows)
    assertEquals("More prose.", (segments[2] as MarkdownSegment.Plain).text)
  }

  @Test
  fun `every body row mismatching the header is padded to the header width`() {
    // The header has 2 columns, every body row has 1. GFM pads them
    // with an empty trailing cell, so the rows survive instead of being
    // dropped into an empty-rows Table / placeholder.
    val markdown: String = """
      | H1 | H2 |
      | -- | -- |
      | only-one-cell |
      | another-one  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    val table = segments[0] as MarkdownSegment.Table
    assertEquals(listOf("H1", "H2"), table.header)
    assertEquals(
      listOf(listOf("only-one-cell", ""), listOf("another-one", "")),
      table.rows
    )
  }

  @Test
  fun `an unrenderable Table keeps the surrounding prose on both sides`() {
    // Prose before and after the malformed block must still be
    // Plain — the raw pipe syntax of the malformed block must not
    // leak into the surrounding prose. The malformed block itself
    // is a Table (with empty rows) that the caller will replace
    // with a placeholder.
    val markdown = "Lead.\n| A | B | C |\n| - | - | - |\nTrailing."
    val segments = splitMarkdownAtTables(markdown)
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
    // body rows: no body rows at all, or a body line that was
    // actually prose containing a `|` — any of these is "Table
    // with empty rows" from the parser's perspective. (A body row
    // with a mismatched column count is now padded, NOT dropped, so
    // it produces rows rather than an empty-rows Table.)
    val noBody = "| H1 | H2 |\n| -- | -- |\n\nnext prose"
    val separatorButTrailingProse = "| H1 | H2 |\n| -- | -- |\nstray line"

    listOf(noBody, separatorButTrailingProse).forEach { markdown ->
      val tables = splitMarkdownAtTables(markdown).filterIsInstance<MarkdownSegment.Table>()
      assertEquals("expected exactly one Table in: $markdown", 1, tables.size)
      assertEquals("expected empty rows in: $markdown", 0, tables[0].rows.size)
    }
  }

  @Test
  fun `blank cells still parse into a Table segment`() {
    // The parser's only job is structural validity (header +
    // separator + at least one body row that lines up?). Whether
    // an individual cell is empty is the renderer's problem —
    // SafeMarkdownText handles that at the Composable layer,
    // falling back to a plain Text instead of letting MarkdownText
    // crash the chat panel. So blank cells still produce a Table
    // segment, they just render as empty cells in the UI.
    val emptyHeaderCell: String = """
      |    | H2 |
      | -- | -- |
      | a  | b  |
    """.trimIndent()
    val emptyBodyCell: String = """
      | H1 | H2 |
      | -- | -- |
      | a  |    |
    """.trimIndent()

    listOf(emptyHeaderCell, emptyBodyCell).forEach { markdown ->
      val tables = splitMarkdownAtTables(markdown).filterIsInstance<MarkdownSegment.Table>()
      assertEquals("expected exactly one Table in: $markdown", 1, tables.size)
      assertEquals(1, tables[0].rows.size)
    }
  }

  // MarkdownSegment.Table.isRenderable — the call-site gate that
  // decides between ScrollableTable and TableParseFailurePlaceholder.
  // The parser doesn't know about this; it always emits a Table. The
  // chat bubble asks isRenderable() to know whether the Table will
  // actually paint visible content.

  @Test
  fun `isRenderable is false for a Table with no body rows`() {
    val table = MarkdownSegment.Table(
      header = listOf("H1", "H2"),
      rows = emptyList(),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
    )
    assertEquals(false, table.isRenderable())
  }

  @Test
  fun `isRenderable is false when every body cell is blank`() {
    val table = MarkdownSegment.Table(
      header = listOf("H1", "H2"),
      rows = listOf(listOf("", ""), listOf("   ", "\t")),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
    )
    assertEquals(false, table.isRenderable())
  }

  @Test
  fun `isRenderable is true when at least one body cell is non-blank`() {
    val table = MarkdownSegment.Table(
      header = listOf("H1", "H2"),
      rows = listOf(listOf("", ""), listOf("", "actual data")),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
    )
    assertEquals(true, table.isRenderable())
  }

  @Test
  fun `isRenderable is true with blank header but real body data`() {
    // The header is a separate concern — isRenderable only checks
    // the body, because the structural question is "is there
    // something to draw underneath the column labels?"
    val table = MarkdownSegment.Table(
      header = listOf("", ""),
      rows = listOf(listOf("a", "b")),
      alignments = listOf(TextAlign.Start, TextAlign.Start),
    )
    assertEquals(true, table.isRenderable())
  }

  // distributeTableWidth — pure-arithmetic helper that decides each
  // column's final width.
  //   1. clamp every column to >= minCellWidthPx,
  //   2. if the natural total already fills the container, leave it,
  //   3. otherwise scale all columns up by the same factor so the
  //      total exactly matches the container.
  // The last column absorbs the rounding residue.

  @Test
  fun `distributeTableWidth - short table scales up to container`() {
    // 3 columns, each natural 100px wide, container 600px. After
    // scaling every column should be 200px (plus padding from the
    // caller — we test the content widths only).
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(100, 100, 100),
      containerWidthPx = 600,
      minCellWidthPx = 80,
      maxCellWidthPx = 400,
      horizontalPaddingPx = 0, // strip padding for the test
    )
    assertEquals(3, out.size)
    out.forEach { assertEquals(200, it) }
  }

  @Test
  fun `distributeTableWidth - narrow column clamps to minimum width`() {
    // Clamp only matters when the table isn't being scaled up —
    // once we hit the "scale all columns proportionally" path, the
    // clamp is just a floor on the natural width, and the final
    // width is whatever the proportional scale gives. So the test
    // needs the natural total to already meet or exceed the
    // container, which means we can compare the clamped result
    // directly.
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(80, 30, 80),
      containerWidthPx = 200,
      minCellWidthPx = 80,
      maxCellWidthPx = 400,
      horizontalPaddingPx = 0,
    )
    assertEquals(80, out[0])
    assertEquals(80, out[1]) // clamped from 30
    assertEquals(80, out[2])
  }

  @Test
  fun `distributeTableWidth - wide table left untouched`() {
    // Natural total (3 × 200) already exceeds the container; the
    // caller will get a horizontal scrollbar instead of squashed
    // columns.
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(200, 200, 200),
      containerWidthPx = 500,
      minCellWidthPx = 80,
      maxCellWidthPx = 400,
      horizontalPaddingPx = 0,
    )
    assertEquals(200, out[0])
    assertEquals(200, out[1])
    assertEquals(200, out[2])
  }

  @Test
  fun `distributeTableWidth - scaled widths sum to container width`() {
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
      maxCellWidthPx = 600,
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
      maxCellWidthPx = 400,
      horizontalPaddingPx = 0,
    )
    assertEquals(0, out.size)
  }

  @Test
  fun `distributeTableWidth - columns exceeding max are clamped before scaling`() {
    // One very wide column (500px) would normally push the others
    // narrow during the scale-up step. The max clamp should kick in
    // so no column ends up wider than `maxCellWidthPx` (200px) after
    // scaling, and the remaining width is redistributed to the
    // other non-saturated columns.
    val out = distributeTableWidth(
      naturalColumnWidthsPx = intArrayOf(500, 50, 50),
      containerWidthPx = 400,
      minCellWidthPx = 40,
      maxCellWidthPx = 200,
      horizontalPaddingPx = 0,
    )
    out.forEach {
      assertTrue(
        "column width $it must not exceed maxCellWidthPx=200 after scaling",
        it <= 200,
      )
    }
  }

  @Test
  fun `a pathologically long line is dropped, not a single cell`() {
    val longLine: String = "x".repeat(10_000)
    val markdown: String = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      | $longLine | $longLine |
      | c  | d  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    val table = segments.single() as MarkdownSegment.Table
    // The two well-formed short rows stay; the over-length row is skipped.
    assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), table.rows)
  }

  @Test
  fun `a very long table keeps all rows, no defensive cap`() {
    val rows: String = (1..600).joinToString("\n") { "| r$it |" }
    val markdown: String = """
      | H1 |
      | -- |
      $rows
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
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
    val markdown: String = """
      First sentence with a | in the middle.
      Second sentence with | another | pipe.
      Third sentence | final.
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    assertEquals(1, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
  }

  @Test
  fun `a malformed line mid-table stops collection cleanly`() {
    val markdown: String = """
      | H1 | H2 |
      | -- | -- |
      | a  | b  |
      not a table row
      | c  | d  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(markdown)
    // The first table picks up `a | b`, the prose "not a table row"
    // goes to plain, and `c | d` becomes a new table.
    val tables = segments.filterIsInstance<MarkdownSegment.Table>()
    assertEquals(1, tables.size)
    assertEquals(listOf(listOf("a", "b")), tables[0].rows)
    val plain = segments.filterIsInstance<MarkdownSegment.Plain>()
    assertEquals(1, plain.size)
    assertTrue(plain[0].text.contains("not a table row"))
  }

  @Test
  fun `short body rows are padded to the header width, not dropped`() {
    val markdown: String = """
      | A | B | C |
      | - | - | - |
      | 1 | 2 |
      | 3 | 4 | 5 | 6 |
    """.trimIndent()
    val table = splitMarkdownAtTables(markdown).single() as MarkdownSegment.Table
    // Previously the two irregular rows were filtered out entirely,
    // leaving an empty body → non-renderable → placeholder.
    assertEquals(2, table.rows.size)
    assertEquals(listOf("1", "2", ""), table.rows[0])
    assertEquals(listOf("3", "4", "5"), table.rows[1])
    assertTrue(table.isRenderable())
  }
}
