/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownTable.kt  2026-07-06 20:06:03 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.styling.extensions.github.tables.create
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.MarkdownText
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.*
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/** GFM Tables styling for the chat panel. Kept for call-site compatibility. */
@Composable
fun rememberGradumTableStyling(): GfmTableStyling {
  val globalColors: GlobalColors = LocalGlobalColors.current
  val alternateColor = globalColors.borders.normal.copy(alpha = 0.08f)
  return remember(globalColors) {
    GfmTableStyling.create(
      colors = GfmTableColors.create(
        rowBackgroundColor = Color.Transparent,
        alternateRowBackgroundColor = alternateColor,
        rowBackgroundStyle = RowBackgroundStyle.Striped,
      ),
      metrics = GfmTableMetrics.create(
        defaultCellContentAlignment = Alignment.Start,
        headerDefaultCellContentAlignment = Alignment.Start,
        cellPadding = PaddingValues(
          horizontal = GradumSpacing.md,
          vertical = GradumSpacing.sm
        )
      ),
      headerBaseFontWeight = FontWeight.SemiBold
    )
  }
}

/**
 * Kept for call-site compatibility. We do not ship a custom
 * table renderer — GFM tables are extracted from the Markdown
 * text at the call site and rendered by [ScrollableTable]
 * instead. See file header for rationale.
 */
@Suppress("UNUSED_PARAMETER")
fun gradumMarkdownBlockRenderer(
  rootStyling: MarkdownStyling,
  baseRenderer: MarkdownBlockRenderer,
  tableStyling: GfmTableStyling,
): MarkdownBlockRenderer = baseRenderer

/**
 * The [MarkdownProcessor] used across the chat UI. Registers three
 * extensions on top of stock CommonMark:
 * - `GitHubTableProcessorExtension` — parses `| ... |` GFM table syntax
 *   (we strip tables out at the call site in [AssistantChatBubble]
 *   and render them with [ScrollableTable], so this is mostly a
 *   safety net for any stray occurrence in the remaining plain text).
 * - `GitHubStrikethroughProcessorExtension` — adds `~~strike~~` support
 *   both inside table cells (rendered by [MarkdownText]) and in the
 *   surrounding prose.
 * - `AutolinkProcessorExtension` — turns bare `<https://...>` and
 *   plain URLs into clickable links.
 */
val GradumMarkdownProcessor: MarkdownProcessor by lazy {
  MarkdownProcessor(
    extensions = listOf(
      AutolinkProcessorExtension,
      GitHubTableProcessorExtension,
      GitHubStrikethroughProcessorExtension(),
    )
  )
}

/**
 * A piece of Markdown content. Either a plain block of text, a parsed
 * GFM table, or a block that *looked* like a GFM table but couldn't be
 * parsed cleanly. The third case is what we get when a model emits a
 * pipe-delimited block with a separator row but the column counts
 * don't line up, or the header parses empty, etc. — the previous
 * behaviour was to dump the raw pipe syntax back to the user as prose,
 * which was visually noisy. The [Failed] segment lets the renderer
 * show a quiet placeholder instead.
 */
sealed interface MarkdownSegment {
  data class Plain(val text: String) : MarkdownSegment
  data class Table(
    val header: List<String>, val alignments: List<TextAlign>, val rows: List<List<String>>
  ) : MarkdownSegment

  /**
   * The original raw text of the malformed table. Kept around in case
   * the renderer wants to surface it (e.g. a "click to expand" tooltip,
   * a copy button), but the default render path just shows a muted
   * placeholder — the raw pipe syntax is ugly and confusing as prose.
   */
  data class Failed(val raw: String) : MarkdownSegment
}

/**
 * Hard cap on body rows per table. Anything beyond this is treated as
 * prose and falls through to the [MarkdownSegment.Plain] path. Prevents
 * a 10 000-row dump from accidentally being rendered as a wall of cells
 * when the upstream model emits one.
 */
private const val MAX_TABLE_ROWS: Int = 500

/**
 * Per-line length cap. Lines longer than this are not considered as
 * possible table rows — they are always treated as plain prose. A
 * single huge URL, an embedded code dump, or a misformatted cell can
 * otherwise blow the cell-width measurement up to thousands of pixels
 * and visibly shove adjacent cells off-screen.
 */
private const val MAX_TABLE_LINE_LENGTH: Int = 5_000

/**
 * Splits a Markdown string into alternating [MarkdownSegment.Plain],
 * [MarkdownSegment.Table], and [MarkdownSegment.Failed] segments. GFM
 * tables are detected line by line: a header line immediately followed
 * by a separator line (`| --- | :---: |`) starts a table; subsequent
 * `|`-delimited lines are body rows until the first non-table line.
 *
 * Invariant: if a block *looks* like a table (header + separator row
 * both present and the separator is a real `---` / `:---:` / `---:` row),
 * this function always emits **either** [MarkdownSegment.Table] **or**
 * [MarkdownSegment.Failed] for it — never [MarkdownSegment.Plain]. We
 * don't want raw pipe syntax leaking into the surrounding prose. The
 * only thing that distinguishes the two outcomes is whether at least
 * one body row survived the column-count check; everything else (empty
 * header, misaligned separator, every row too long, all rows column-
 * mismatched) is just "no body rows" in disguise.
 *
 * The parser handles:
 * - optional leading / trailing `|`
 * - escaped pipes (`\|`) inside cells
 * - alignment markers in the separator row (`:---`, `---:`, `:---:`)
 *
 * It does NOT handle:
 * - tables nested inside list items (would require a real Markdown parser)
 * - pipes inside inline code spans
 * - alignment applied to header cells (we trust the separator row)
 */
fun splitMarkdownAtTables(markdown: String): List<MarkdownSegment> {
  val lines: List<String> = markdown.split('\n')
  val segments: MutableList<MarkdownSegment> = mutableListOf()
  val plainBuffer: StringBuilder = StringBuilder()
  val failedBuffer: StringBuilder = StringBuilder()
  var lineIndex = 0

  fun flushPlain() {
    if (plainBuffer.isNotEmpty()) {
      segments.add(MarkdownSegment.Plain(plainBuffer.toString()))
      plainBuffer.clear()
    }
  }

  fun flushFailed() {
    if (failedBuffer.isNotEmpty()) {
      segments.add(MarkdownSegment.Failed(failedBuffer.toString()))
      failedBuffer.clear()
    }
  }

  // Whenever we cross a Plain ↔ Failed boundary, flush the buffer
  // that's being left behind. Without this, a successful parse *after*
  // a failed attempt would emit a Plain block first, then a Plain
  // block, then the previously-buffered Failed — out of order. By
  // flushing at the transition, the order in [segments] matches the
  // visual order in the source markdown.
  fun appendPlain(line: String) {
    if (failedBuffer.isNotEmpty()) flushFailed()
    if (plainBuffer.isNotEmpty()) plainBuffer.append('\n')
    plainBuffer.append(line)
  }

  fun appendFailed(line: String) {
    if (plainBuffer.isNotEmpty()) flushPlain()
    if (failedBuffer.isNotEmpty()) failedBuffer.append('\n')
    failedBuffer.append(line)
  }

  while (lineIndex < lines.size) {
    val headerLine: String = lines[lineIndex]
    val separatorLine: String? = lines.getOrNull(lineIndex + 1)
    val headerIsLikely: Boolean = separatorLine != null
      && headerLine.length <= MAX_TABLE_LINE_LENGTH
      && separatorLine.length <= MAX_TABLE_LINE_LENGTH
      && headerLine.contains('|')
      && isTableSeparator(separatorLine)

    if (headerIsLikely) {
      val headers: List<String> = parseTableRow(headerLine)
      val alignments: List<TextAlign> = parseAlignments(separatorLine, headers.size)
        ?: List(headers.size.coerceAtLeast(1)) { TextAlign.Start }

      // Collect every body line we touch, even ones we later reject.
      // Whatever the outcome (Table or Failed), the whole block has
      // to be consumed together — partial output would let stray `|`
      // characters leak into the surrounding prose.
      val bodyLines: MutableList<String> = mutableListOf()
      var bodyLineIndex: Int = lineIndex + 2
      while (bodyLineIndex < lines.size && bodyLines.size < MAX_TABLE_ROWS) {
        val currentLine: String = lines[bodyLineIndex]
        val trimmedCurrent: String = currentLine.trim()
        // A blank line or a line without any `|` ends the table.
        if (trimmedCurrent.isEmpty() || !currentLine.contains('|')) break
        bodyLines.add(currentLine)
        bodyLineIndex++
      }

      val bodyRows: List<List<String>> = bodyLines
        .filter { it.length <= MAX_TABLE_LINE_LENGTH }
        .map { parseTableRow(it) }
        // Drop rows whose column count doesn't match the header — these
        // are almost always misparsed prose with a stray `|`, not real
        // table data.
        .filter { it.size == headers.size }

      if (bodyRows.isNotEmpty()) {
        flushPlain(); flushFailed()
        segments.add(MarkdownSegment.Table(headers, alignments, bodyRows))
      } else {
        // Single rule: "I tried to render a table and nothing came out."
        // The original block (header + separator + every body line we
        // scanned) goes into Failed, the renderer turns that into a
        // muted placeholder.
        flushPlain()
        appendFailed(headerLine)
        appendFailed(separatorLine)
        bodyLines.forEach { appendFailed(it) }
      }
      lineIndex = bodyLineIndex
      continue
    }
    appendPlain(headerLine); lineIndex++
  }
  flushPlain(); flushFailed()
  return segments
}

/**
 * Parses a single pipe-delimited row into cells. The caller is expected
 * to have already established that the line is non-empty and contains
 * `|`, so this function does not return `null`. Leading and trailing
 * `|` are optional; escaped `\|` becomes a literal `|` inside the cell.
 */
private fun parseTableRow(line: String): List<String> {
  val trimmed: String = line.trim()
  // Optional leading `|`.
  val withoutLeading: String =
    if (trimmed.startsWith("|")) trimmed.substring(1) else trimmed
  // Optional trailing `|`, but not when it's part of an escaped `\|`.
  val withoutTrailing: String =
    if (withoutLeading.endsWith("|") && !withoutLeading.endsWith("\\|"))
      withoutLeading.substring(0, withoutLeading.length - 1)
    else withoutLeading

  val cells: MutableList<String> = mutableListOf()
  val currentCell: StringBuilder = StringBuilder()
  var charIndex = 0
  while (charIndex < withoutTrailing.length) {
    when (val currentChar: Char = withoutTrailing[charIndex]) {
      '\\' if charIndex + 1 < withoutTrailing.length &&
        withoutTrailing[charIndex + 1] == '|' -> {
        currentCell.append('|')
        charIndex += 2
      }

      '|' -> {
        cells.add(currentCell.toString().trim())
        currentCell.clear()
        charIndex++
      }

      else -> {
        currentCell.append(currentChar)
        charIndex++
      }
    }
  }
  cells.add(currentCell.toString().trim())
  return cells
}

/** A separator row is a pipe row whose every cell is `---` / `:---` / `---:` / `:---:`. */
private fun isTableSeparator(line: String): Boolean {
  if (!line.contains('|')) return false
  if (parseTableRow(line).isEmpty()) return false

  return parseTableRow(line).all { cell ->
    val trimmedCell: String = cell.trim()
    trimmedCell.isNotEmpty() &&
      trimmedCell.all { it == '-' || it == ':' } &&
      trimmedCell.count { it == '-' } >= 1
  }
}

/**
 * Reads the alignment markers from a separator row. Returns `null` if
 * the row is not a valid separator for the expected column count.
 */
private fun parseAlignments(line: String?, expectedCount: Int): List<TextAlign>? {
  if (line == null || expectedCount <= 0) return null
  val cells: List<String> = parseTableRow(line)
  if (cells.size < expectedCount) return null

  return List(expectedCount) { cellIndex ->
    val trimmedCell: String = cells[cellIndex].trim()
    when {
      trimmedCell.startsWith(":") && trimmedCell.endsWith(":") -> TextAlign.Center
      trimmedCell.endsWith(":") -> TextAlign.End
      else -> TextAlign.Start
    }
  }
}

/**
 * Total horizontal cell padding (8.dp on each side).
 */
private val CellHorizontalPadding: Dp = 8.dp

/**
 * Total vertical cell padding (4.dp on each side).
 */
private val CellVerticalPadding: Dp = 4.dp

/**
 * Renders a [MarkdownSegment.Table] as a plain Compose layout: one
 * header row plus body rows, each row a horizontal `Row` of
 * [MarkdownText] cells with a fixed per-column width. The whole
 * table is wrapped in a horizontally scrollable [Box] so a wide
 * table shows a horizontal scrollbar instead of being squeezed.
 *
 * Cell content is fed through [MarkdownText] so inline Markdown
 * (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`, `[link](url)`,
 * `<br>` hard breaks, bare URLs) renders the same way it does in
 * the surrounding prose. Widths are measured up-front via
 * [rememberTextMeasurer] using the stripped plain text (markdown
 * markers like `**` are dropped by the parser, so the rendered
 * width ≈ the measured plain-text width), and the widest cell in
 * each column sets the column width, so all rows stay vertically
 * aligned.
 *
 * Background scheme:
 * - Outer `Box` → `panelBackground` (so the table is a visible panel)
 * - Header row → `panelBackground` (same as outer, so it blends)
 * - Even body rows → `Color.Transparent` (shows the outer panel)
 * - Odd body rows → `panelBackground` (re-painted on the cell)
 *
 * Padding: 8.dp horizontal × 4.dp vertical, on every cell.
 */
@Composable
fun ScrollableTable(
  table: MarkdownSegment.Table,
  onUrlClick: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val globalColors: GlobalColors = LocalGlobalColors.current
  val panelBackground: Color = globalColors.panelBackground
  val density: Density = LocalDensity.current
  val horizontalPaddingPx: Int = with(density) { CellHorizontalPadding.roundToPx() }
  val textMeasurer: TextMeasurer = rememberTextMeasurer()
  val baseStyle: TextStyle = JewelTheme.typography.regular
  val headerStyle: TextStyle = baseStyle.copy(fontWeight = FontWeight.SemiBold)
  val paragraphStyling: MarkdownStyling.Paragraph = rememberGradumMarkdownStyling().paragraph
  val renderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current

  val columnWidthsPx: IntArray = remember(table) {
    val widths = IntArray(table.header.size.coerceAtLeast(1))

    fun measure(row: List<String>, style: TextStyle) {
      row.forEachIndexed { columnIndex, cell ->
        if (columnIndex >= widths.size) return@forEachIndexed
        val cellWidth: Int = textMeasurer.measure(
          maxLines = 1,
          style = style,
          softWrap = false,
          text = AnnotatedString(cell)
        ).size.width
        if (cellWidth > widths[columnIndex]) widths[columnIndex] = cellWidth
      }
    }
    measure(table.header, headerStyle)
    table.rows.forEach { measure(it, baseStyle) }
    widths
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.lg)
      .clip(RoundedCornerShape(8.dp))
      .horizontalScroll(rememberScrollState())
  ) {
    Column {
      Row(modifier = Modifier.background(panelBackground)) {
        table.header.forEachIndexed { columnIndex, cell ->
          SafeMarkdownText(
            text = cell,
            modifier = Modifier
              .width(
                with(density) {
                  (columnWidthsPx.getOrElse(columnIndex) { 0 } + horizontalPaddingPx * 2)
                    .toDp()
                }
              )
              .padding(
                horizontal = CellHorizontalPadding,
                vertical = CellVerticalPadding
              ),
            onUrlClick = onUrlClick,
            fontWeight = FontWeight.SemiBold,
            textAlign = table.alignments.getOrNull(columnIndex) ?: TextAlign.Start,
            blockRenderer = renderer,
            paragraphStyling = paragraphStyling,
          )
        }
      }
      table.rows.forEachIndexed { rowIndex, row ->
        val rowBackground: Color =
          if (rowIndex % 2 == 0) Color.Transparent else panelBackground
        Row(modifier = Modifier.background(rowBackground)) {
          row.forEachIndexed { columnIndex, cell ->
            SafeMarkdownText(
              text = cell,
              modifier = Modifier
                .width(
                  with(density) {
                    (columnWidthsPx.getOrElse(columnIndex) { 0 } + horizontalPaddingPx * 2)
                      .toDp()
                  }
                )
                .padding(
                  horizontal = CellHorizontalPadding,
                  vertical = CellVerticalPadding
                ),
              textAlign = table.alignments.getOrNull(columnIndex) ?: TextAlign.Start,
              onUrlClick = onUrlClick,
              blockRenderer = renderer,
              paragraphStyling = paragraphStyling,
            )
          }
        }
      }
    }
  }
}

/**
 * MarkdownText that can't crash the chat panel.
 *
 * Jewel 0.37's [MarkdownText] internally does
 *
 *     val block = processor.processMarkdown(text).first()
 *     block as MarkdownBlock.Paragraph
 *
 * (we confirmed this by decompiling MarkdownTextKt.class around
 * line 86-88 with javap). That throws NoSuchElementException for
 * any input the processor parses to zero blocks — in practice `""`,
 * `" "`, `"\t"`, `"\n"`, any whitespace-only string — and
 * ClassCastException for inputs that parse to a non-Paragraph block
 * type (heading, list, code block, ...). Either of those propagates
 * out as an unhandled Compose exception and takes the whole chat
 * panel down.
 *
 * We can't try/catch a Composable call directly — exceptions thrown
 * from inside Composable functions escape the try block and land in
 * the coroutine exception handler. So we dry-run the parse
 * ourselves, with [runCatching] around it, and fall back to a plain
 * [Text] when the parse isn't going to play nicely with MarkdownText.
 *
 * Trade-off: the dry-run doubles the markdown parsing work for the
 * common case (where MarkdownText is happy). For short cell-sized
 * strings that's negligible. We cache the result in [remember] so it
 * only fires once per `text` value.
 */
@Composable
fun SafeMarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  onUrlClick: (String) -> Unit = {},
  fontWeight: FontWeight? = null,
  textAlign: TextAlign = TextAlign.Unspecified,
  blockRenderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current,
  paragraphStyling: MarkdownStyling.Paragraph = rememberGradumMarkdownStyling().paragraph,
  processor: MarkdownProcessor = GradumMarkdownProcessor,
) {
  if (text.isBlank()) {
    // Whitespace-only input. Skip the dry-run; MarkdownText would
    // crash on this, and rendering as an empty Text in the caller's
    // style is the closest thing to "show nothing" we can do without
    // losing the cell.
    Text(
      text = "",
      modifier = modifier,
      textAlign = textAlign,
      style = JewelTheme.typography.regular.copy(fontWeight = fontWeight),
    )
    return
  }

  val canRenderAsMarkdown: Boolean = remember(text) {
    runCatching { processor.processMarkdownDocument(text) }
      .map { blocks -> blocks.isNotEmpty() && blocks.first() is MarkdownBlock.Paragraph }
      .getOrDefault(false)
  }

  if (canRenderAsMarkdown) {
    MarkdownText(
      text = text,
      modifier = modifier,
      onUrlClick = onUrlClick,
      blockRenderer = blockRenderer,
      styling = paragraphStyling,
      processor = processor,
      fontWeight = fontWeight,
      textAlign = textAlign,
    )
  } else {
    // MarkdownText can't render this safely (rare, but real: hostile
    // markdown input that the processor can't produce a single
    // Paragraph block for). Fall back to plain Text so the user still
    // sees the cell's content as raw text instead of a crash dialog.
    Text(
      text = text,
      modifier = modifier,
      textAlign = textAlign,
      style = JewelTheme.typography.regular.copy(fontWeight = fontWeight),
    )
  }
}
