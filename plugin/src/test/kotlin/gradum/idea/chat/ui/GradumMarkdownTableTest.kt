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
  fun `a table with zero body rows is reported as a Failed segment`() {
    // The header + separator look like a real table, but no body
    // row follows. Previously we dumped the raw header / separator
    // back as prose, which was visually noisy. Now the whole block
    // becomes a single Failed segment that the renderer turns into
    // a muted placeholder.
    val md = "Prose.\n| H1 | H2 |\n| -- | -- |\nMore prose."
    val segments = splitMarkdownAtTables(md)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertEquals("Prose.", (segments[0] as MarkdownSegment.Plain).text)
    val failed = segments[1] as MarkdownSegment.Failed
    assertTrue(failed.raw.contains("H1"))
    assertTrue(failed.raw.contains("--"))
    assertTrue(segments[2] is MarkdownSegment.Plain)
    assertEquals("More prose.", (segments[2] as MarkdownSegment.Plain).text)
  }

  @Test
  fun `a table where every body row mismatches the header is reported as Failed`() {
    // The header has 2 columns, every body row is 1 column. Real-world
    // models often emit a separator that's wider than the data rows.
    val md = """
      | H1 | H2 |
      | -- | -- |
      | only-one-cell |
      | another-one  |
    """.trimIndent()
    val segments = splitMarkdownAtTables(md)
    assertEquals(1, segments.size)
    val failed = segments[0] as MarkdownSegment.Failed
    assertTrue(failed.raw.contains("H1"))
    assertTrue(failed.raw.contains("only-one-cell"))
  }

  @Test
  fun `a Failed segment keeps the surrounding prose on the right side`() {
    // Prose before and after the malformed block must still be Plain —
    // the raw pipe syntax of the failed block must not leak into them.
    val md = "Lead.\n| A | B | C |\n| - | - | - |\nTrailing."
    val segments = splitMarkdownAtTables(md)
    assertEquals(3, segments.size)
    assertTrue(segments[0] is MarkdownSegment.Plain)
    assertEquals("Lead.", (segments[0] as MarkdownSegment.Plain).text)
    assertTrue(segments[1] is MarkdownSegment.Failed)
    assertTrue(segments[2] is MarkdownSegment.Plain)
    assertEquals("Trailing.", (segments[2] as MarkdownSegment.Plain).text)
  }

  @Test
  fun `every failure mode collapses to the same single rule - no Table means Failed`() {
    // The whole point of the simpler rule: it doesn't matter *why* a
    // table-shaped block failed to render. No body rows, every body
    // row column-mismatched — any of these is just "no Table segment
    // came out" → Failed. The only thing that decides Table vs Failed
    // is whether at least one body row survived.
    val noBody = "| H1 | H2 |\n| -- | -- |\n\nnext prose"
    val allMismatched = "| H1 | H2 |\n| -- | -- |\n| a |\n| b |"
    val separatorButTrailingProse = "| H1 | H2 |\n| -- | -- |\nstray line"

    listOf(noBody, allMismatched, separatorButTrailingProse).forEach { md ->
      val tables = splitMarkdownAtTables(md).filterIsInstance<MarkdownSegment.Table>()
      val failed = splitMarkdownAtTables(md).filterIsInstance<MarkdownSegment.Failed>()
      assertEquals("expected no Table in: $md", 0, tables.size)
      assertEquals("expected exactly one Failed in: $md", 1, failed.size)
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
      val failed = splitMarkdownAtTables(md).filterIsInstance<MarkdownSegment.Failed>()
      assertEquals("expected exactly one Table in: $md", 1, tables.size)
      assertEquals("expected no Failed in: $md", 0, failed.size)
    }
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
