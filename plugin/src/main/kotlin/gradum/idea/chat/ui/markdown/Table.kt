/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Table.kt  2026-08-07 16:04:18 Changed by gwy
 */
@file:OptIn(ExperimentalJewelApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
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
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.Node
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownText
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * [MarkdownProcessor] used across the chat UI. Adds two extensions on top of
 * stock CommonMark:
 * - `GitHubStrikethroughProcessorExtension` — `~~strike~~` support.
 * - `AutolinkProcessorExtension` — `<https://...>` and bare URLs.
 *
 * GFM table syntax is *not* registered here: the chat UI strips GFM tables out
 * at the call site in AssistantChatBubble and renders them with [ScrollableTable].
 *
 * [LatexBlockExtension] is intentionally **not** registered here — this
 * processor is used by the fenced / indented code block reparse path
 * (`BlockRenderer.parseFencedCodeBlock`), and a `$$…$$` inside a code
 * block must stay as literal text, not become a rendered formula. The
 * LaTeX block extension lives on the inline / block path parsers only.
 */
val GradumMarkdownProcessor: MarkdownProcessor by lazy {
  MarkdownProcessor(
    extensions = listOf(
      AutolinkProcessorExtension,
      GitHubStrikethroughProcessorExtension()
    )
  )
}

/**
 * A piece of Markdown content. The three variants are produced by [splitMarkdown]:
 * - [Plain] — `Paragraph` blocks. Routed to the custom inline chip parser
 *   (`InlineMarkdown`) so backticks render as rounded chips. The text is the
 *   original Markdown source (re-serialized from the commonmark AST).
 * - [Table] — a parsed GFM table. Routed to [ScrollableTable] (or the
 *   placeholder if the body is empty).
 * - [NonProseBlock] — a non-`Paragraph` block (heading, list, blockquote,
 *   fenced code, thematic break, HTML). The caller routes this to
 *   [RenderNonProseBlock] so the block renders normally. The split between
 *   [Plain] and [NonProseBlock] is done by [splitPlainAtBlocks].
 */
sealed interface MarkdownSegment {
  data class Plain(val text: String) : MarkdownSegment
  data class Table(
    val header: List<String>,
    val rows: List<List<String>>,
    val alignments: List<TextAlign>
  ) : MarkdownSegment

  data class NonProseBlock(val text: String) : MarkdownSegment
}

/**
 * Does this [MarkdownSegment.Table] have any visible body content? A
 * `Table` that fails the check is replaced with the parse-failure placeholder
 * instead of being rendered as a header-only / empty grid. There must be
 * at least one non-blank cell in at least one body row.
 */
fun MarkdownSegment.Table.isRenderable(): Boolean = rows.any { row -> row.any { it.isNotBlank() } }

private const val MAX_TABLE_LINE_LENGTH: Int = 5_000

private val minCellWidthDp: Dp = 70.dp
private val cellHorizontalPadding: Dp = 10.dp
private val cellVerticalPadding: Dp = GradumSpacing.md
private val scrollbarReservedSpace: Dp = GradumSpacing.md

/**
 * Splits a Markdown string into alternating [MarkdownSegment.Plain] and
 * [MarkdownSegment.Table] segments. GFM tables are detected line by line: a
 * header line immediately followed by a separator line (`| --- | :---: |`)
 * starts a table; subsequent `|`-delimited lines are body rows until the
 * first non-table line.
 *
 * A header+separator with no usable body rows still becomes a `Table` with an
 * empty `rows` list; the chat bubble substitutes the placeholder for it
 * via [isRenderable]. The parser handles optional leading / trailing `|`,
 * escaped pipes (`\|`) inside cells, and alignment markers in the separator
 * row. It does NOT handle tables nested inside list items, pipes inside
 * inline code spans, or alignment applied to header cells.
 */
fun splitMarkdownAtTables(markdown: String): List<MarkdownSegment> {
  val lines: List<String> = markdown.split('\n')
  val segments: MutableList<MarkdownSegment> = mutableListOf()
  val plainBuffer: StringBuilder = StringBuilder()
  var lineIndex = 0

  fun flushPlain() {
    if (plainBuffer.isNotEmpty()) {
      segments.add(MarkdownSegment.Plain(plainBuffer.toString()))
      plainBuffer.clear()
    }
  }

  fun appendPlain(line: String) {
    if (plainBuffer.isNotEmpty()) plainBuffer.append('\n')
    plainBuffer.append(line)
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
      val alignments: List<TextAlign> =
        parseAlignments(separatorLine, headers.size)
          ?: List(headers.size.coerceAtLeast(1)) { TextAlign.Start }

      val bodyLines: MutableList<String> = mutableListOf()
      var bodyLineIndex: Int = lineIndex + 2
      while (bodyLineIndex < lines.size) {
        val currentLine: String = lines[bodyLineIndex]
        val trimmedCurrent: String = currentLine.trim()
        if (trimmedCurrent.isEmpty() || !currentLine.contains('|')) break
        bodyLines.add(currentLine).also { bodyLineIndex++ }
      }

      val bodyRows: List<List<String>> = bodyLines
        .filter { it.length <= MAX_TABLE_LINE_LENGTH }
        .map { parseTableRow(it) }
        .filter { it.size == headers.size }

      flushPlain()
      segments.add(MarkdownSegment.Table(headers, bodyRows, alignments))
      lineIndex = bodyLineIndex
      continue
    }
    appendPlain(headerLine)
    lineIndex++
  }
  flushPlain()
  return segments
}

/**
 * Splits a Markdown string into [MarkdownSegment.Plain] /
 * [MarkdownSegment.Table] / [MarkdownSegment.NonProseBlock] segments. Two-step:
 * 1. [splitMarkdownAtTables] extracts GFM tables (fast and accurate for pipes).
 * 2. Each `Plain` is reparsed with commonmark and split on top-level block
 *    boundaries via [splitPlainAtBlocks] (heading / list / blockquote / fenced
 *    code / thematic break / HTML → `NonProseBlock`, everything else → `Plain`).
 */
fun splitMarkdown(markdown: String): List<MarkdownSegment> =
  splitMarkdownAtTables(markdown).flatMap { segment: MarkdownSegment ->
    when (segment) {
      is MarkdownSegment.Plain -> splitPlainAtBlocks(segment.text)
      is MarkdownSegment.Table -> listOf(segment)
      is MarkdownSegment.NonProseBlock -> listOf(segment)
    }
  }

/**
 * Parses a single pipe-delimited row into cells. Leading and trailing `|`
 * are optional; escaped `\|` becomes a literal `|` inside the cell.
 */
private fun parseTableRow(line: String): List<String> {
  val trimmed: String = line.trim()
  val withoutLeading: String =
    if (trimmed.startsWith("|")) trimmed.substring(1) else trimmed
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
private fun isTableSeparator(tableRow: String): Boolean {
  if (!tableRow.contains('|')) return false

  val cells: List<String> = parseTableRow(tableRow)
  return cells.all { cellContent ->
    val trimmedCellContent: String = cellContent.trim()
    trimmedCellContent.isNotEmpty() &&
      trimmedCellContent.all { char -> char == '-' || char == ':' } &&
      trimmedCellContent.count { char -> char == '-' } >= 1
  }
}

/** Reads the alignment markers from a separator row. Returns `null` if invalid. */
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
 * Renders a [MarkdownSegment.Table] as a plain Compose layout: one header row
 * plus body rows, each row a horizontal `Row` of [MarkdownText] cells with
 * a fixed per-column width. The whole table is wrapped in a horizontally
 * scrollable [Box] so a wide table shows a horizontal scrollbar instead of
 * being squeezed.
 *
 * **Caller contract**: the caller is expected to have already checked
 * [isRenderable] and substituted a placeholder for any non-renderable
 * `Table`. This function does not bail out for empty / degenerate input.
 *
 * Cell content is fed through [MarkdownText] so inline Markdown renders
 * the same way it does in the surrounding prose. Widths are measured
 * up-front via [rememberTextMeasurer] using the stripped plain text, and
 * the widest cell in each column sets the column width.
 *
 * Background scheme: outer `Box` → `panelBackground`; header row →
 * `panelBackground` (blends with outer); even body rows → `Color.Transparent`;
 * odd body rows → `borders.normal @ 8% alpha` (subtle stripe in both
 * light and dark mode).
 */
@Composable
fun ScrollableTable(
  table: MarkdownSegment.Table,
  modifier: Modifier = Modifier,
  isSimplified: Boolean = false,
  onUrlClick: (String) -> Unit = {}
) {
  val density: Density = LocalDensity.current
  val horizontalPaddingPx: Int = with(density) { cellHorizontalPadding.roundToPx() }
  val textMeasurer: TextMeasurer = rememberTextMeasurer()
  val markdownStyling = rememberGradumMarkdownStyling()
  val baseStyle: TextStyle = markdownStyling.paragraph.inlinesStyling.textStyle
  val headerStyle: TextStyle = baseStyle.copy(fontWeight = FontWeight.Bold)
  val tableBackground = markdownStyling.code.fenced.background
  val paragraphStyling: MarkdownStyling.Paragraph = rememberGradumMarkdownStyling().paragraph
  val renderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current

  val naturalColumnWidthsPx: IntArray = remember(table) {
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

  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.lg)
      .clip(CodeBlockCornerRadius)
      .background(tableBackground)
  ) {
    val containerWidthPx: Int = with(density) { maxWidth.roundToPx() }
    val minCellWidthPx: Int = with(density) { minCellWidthDp.roundToPx() }
    val finalColumnWidthsPx: IntArray = remember(
      naturalColumnWidthsPx, containerWidthPx, minCellWidthPx, horizontalPaddingPx
    ) {
      distributeTableWidth(
        minCellWidthPx = minCellWidthPx,
        containerWidthPx = containerWidthPx,
        horizontalPaddingPx = horizontalPaddingPx,
        naturalColumnWidthsPx = naturalColumnWidthsPx
      )
    }
    val scrollState = rememberScrollState()

    val stickyRegistry: StickySectionRegistry = LocalStickySectionRegistry.current
    val sectionEntry: StickySectionEntry? = if (isSimplified) null else {
      val sectionId: Any = remember { Any() }
      val stickyHeaderProvider: @Composable () -> Unit = {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(StickySectionTopCorners)
            .background(tableBackground)
        ) {
          DisableSelection {
            Column {
              TableToolbar(table = table)
              Box(
                modifier = Modifier
                  .width(with(density) { containerWidthPx.toDp() })
                  .horizontalScroll(scrollState)
              ) {
                TableHeaderRow(
                  header = table.header,
                  columnWidthsPx = finalColumnWidthsPx,
                  alignments = table.alignments,
                  density = density,
                  horizontalPaddingPx = horizontalPaddingPx,
                  onUrlClick = onUrlClick,
                  renderer = renderer,
                  paragraphStyling = paragraphStyling,
                )
              }
            }
          }
        }
      }
      remember { stickyRegistry.register(sectionId, stickyHeaderProvider) }
    }

    if (sectionEntry != null) {
      DisposableEffect(sectionEntry) {
        onDispose { stickyRegistry.unregister(sectionEntry) }
      }
    }

    val sectionBoundsModifier: Modifier =
      if (sectionEntry != null) {
        Modifier.onGloballyPositioned { coordinates ->
          val topLeft: Offset = coordinates.localToWindow(Offset.Zero)
          val bottomRight: Offset = coordinates.localToWindow(
            Offset(
              coordinates.size.width.toFloat(),
              coordinates.size.height.toFloat()
            )
          )
          stickyRegistry.updateBounds(
            sectionEntry,
            Rect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
          )
        }
      } else Modifier

    Column(modifier = Modifier.fillMaxWidth().then(sectionBoundsModifier)) {
      if (!isSimplified) DisableSelection { TableToolbar(table = table) }
      Box(modifier = Modifier.fillMaxWidth()) {
        Box(
          modifier = Modifier
            .width(with(density) { containerWidthPx.toDp() })
            .padding(bottom = scrollbarReservedSpace)
            .horizontalScroll(scrollState)
        ) {
          Column {
            TableHeaderRow(
              header = table.header,
              columnWidthsPx = finalColumnWidthsPx,
              alignments = table.alignments,
              density = density,
              horizontalPaddingPx = horizontalPaddingPx,
              onUrlClick = onUrlClick,
              renderer = renderer,
              paragraphStyling = paragraphStyling,
            )
            table.rows.forEachIndexed { _, row ->
              Row {
                row.forEachIndexed { columnIndex, cell ->
                  SafeMarkdownText(
                    text = cell,
                    modifier = Modifier
                      .width(
                        with(density) {
                          (finalColumnWidthsPx.getOrElse(columnIndex) { 0 } + horizontalPaddingPx * 2)
                            .toDp()
                        }
                      )
                      .padding(
                        horizontal = cellHorizontalPadding,
                        vertical = cellVerticalPadding
                      ),
                    onUrlClick = onUrlClick,
                    blockRenderer = renderer,
                    paragraphStyling = paragraphStyling,
                    textAlign = table.alignments.getOrNull(columnIndex) ?: TextAlign.Start
                  )
                }
              }
            }
          }
        }
        HorizontalScrollbar(
          scrollState = scrollState,
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
        )
      }
    }
  }
}

/**
 * Renders the table's header row. Shared between the in-flow table and the
 * sticky header overlay (registered via [StickySectionRegistry]) so both
 * render identically — same per-column widths, padding and typography.
 */
@Composable
private fun TableHeaderRow(
  header: List<String>,
  columnWidthsPx: IntArray,
  alignments: List<TextAlign>,
  density: Density,
  horizontalPaddingPx: Int,
  onUrlClick: (String) -> Unit,
  renderer: MarkdownBlockRenderer,
  paragraphStyling: MarkdownStyling.Paragraph,
) {
  Row(
    modifier = Modifier.fillMaxWidth()
  ) {
    header.forEachIndexed { columnIndex, cell ->
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
            horizontal = cellHorizontalPadding,
            vertical = cellVerticalPadding
          ),
        onUrlClick = onUrlClick,
        blockRenderer = renderer,
        fontWeight = FontWeight.SemiBold,
        paragraphStyling = paragraphStyling,
        textAlign = alignments.getOrNull(columnIndex) ?: TextAlign.Start
      )
    }
  }
}

/** Top-row toolbar: "Table" label + copy button. Mirrors the code block toolbar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableToolbar(table: MarkdownSegment.Table) {
  val cols = table.header.size
  val rows = table.rows.size
  val scope = rememberCoroutineScope()
  var isCopied: Boolean by remember { mutableStateOf(false) }
  val tableAsMarkdown: String = remember(table) { tableToMarkdownString(table) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = GradumSpacing.md, end = GradumSpacing.sm, top = GradumSpacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    Icon(
      key = GradumIcons.Table,
      contentDescription = message("gradum.table"),
    )
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Text(
        text = message("gradum.table"),
        fontFamily = JewelTheme.editorTextStyle.fontFamily,
      )
      Text(
        text = "${cols}x${rows}",
        fontFamily = JewelTheme.editorTextStyle.fontFamily,
        color = JewelTheme.globalColors.text.info,
      )
    }
    Tooltip(tooltip = { Text(text = message("gradum.copy.table.tooltip")) }) {
      IconButton(
        onClick = {
          copyToClipboard(
            scope = scope,
            text = tableAsMarkdown,
            onCopied = { isCopied = true },
            onReset = { isCopied = false }
          )
        }
      ) {
        Icon(
          contentDescription = message("gradum.copy.table"),
          key = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy
        )
      }
    }
  }
}

/**
 * Serializes a [MarkdownSegment.Table] back to a GFM-flavored Markdown string.
 * Pipes inside cell content are re-escaped to `\|` so the output round-trips
 * through [parseTableRow]. Alignment markers are dropped.
 */
private fun tableToMarkdownString(table: MarkdownSegment.Table): String {
  val escapeCell: (String) -> String = { it.replace("|", "\\|") }
  val joinRow: (List<String>) -> String =
    { row -> row.joinToString(separator = " | ", prefix = "| ", postfix = " |") { escapeCell(it) } }
  val separatorRow: String =
    "| " + table.header.joinToString(separator = " | ", postfix = " |") { "---" }
  return buildString {
    append(joinRow(table.header))
    append('\n')
    append(separatorRow)
    for (row in table.rows) {
      append('\n')
      append(joinRow(row))
    }
  }
}

/**
 * Picks the final column widths for a table. Clamps every column to at
 * least [minCellWidthPx], then if the natural total is wider than the
 * container, returns the clamped natural widths and lets `horizontalScroll`
 * take over. Otherwise, scales every column up by the same factor so the
 * new total exactly matches the container width; the last column absorbs
 * the round-down residue.
 */
internal fun distributeTableWidth(
  naturalColumnWidthsPx: IntArray, containerWidthPx: Int,
  minCellWidthPx: Int, horizontalPaddingPx: Int
): IntArray {
  if (naturalColumnWidthsPx.isEmpty()) return naturalColumnWidthsPx

  val paddingPerColumn = horizontalPaddingPx * 2
  val columnCount = naturalColumnWidthsPx.size

  val clamped = IntArray(columnCount) { index ->
    maxOf(naturalColumnWidthsPx[index], minCellWidthPx)
  }

  val naturalTotal = clamped.sum() + paddingPerColumn * columnCount
  if (naturalTotal >= containerWidthPx) return clamped

  val scale = containerWidthPx.toFloat() / naturalTotal.toFloat()
  val scaled = IntArray(columnCount) { index -> (clamped[index] * scale).toInt() }

  val scaledTotal = scaled.sum() + paddingPerColumn * columnCount
  val leftover = containerWidthPx - scaledTotal
  if (leftover != 0) scaled[columnCount - 1] += leftover

  return scaled
}

/**
 * MarkdownText that can't crash the chat panel.
 *
 * Jewel 0.37's [MarkdownText] internally does `block as MarkdownBlock.Paragraph`,
 * which throws `NoSuchElementException` for empty / whitespace-only input and
 * `ClassCastException` for non-`Paragraph` parsed blocks — both propagate as
 * unhandled Compose exceptions and take the chat panel down. Composable calls
 * can't be wrapped in try/catch (exceptions escape the try block and land in
 * the coroutine exception handler), so we use [RenderInlineTextWithChips]
 * (which already handles the empty-text case and never crashes on non-`Paragraph`
 * input) instead.
 */
@Composable
fun SafeMarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  onUrlClick: (String) -> Unit = {},
  fontWeight: FontWeight? = null,
  textAlign: TextAlign = TextAlign.Unspecified,
  @Suppress("UNUSED_PARAMETER") processor: MarkdownProcessor = GradumMarkdownProcessor,
  @Suppress("UNUSED_PARAMETER") blockRenderer: MarkdownBlockRenderer =
    LocalMarkdownBlockRenderer.current,
  @Suppress("UNUSED_PARAMETER") paragraphStyling: MarkdownStyling.Paragraph =
    rememberGradumMarkdownStyling().paragraph,
) {
  // Cell rendering now goes through the chip-aware inline text renderer
  // ([RenderInlineTextWithChips]) so inline code (`` `update()` `` etc.)
  // renders as a rounded `InlineCodeChip` — the same chip used in the
  // message body. The previous `MarkdownText` path used Jewel's
  // `SpanStyle` for inline code (monospace text + background) which the
  // user reported as "default styling" in 2026-07-14. The
  // `processor` / `blockRenderer` / `paragraphStyling` parameters are
  // kept for source-compat (the call site in ScrollableTable passes
  // them) but are no longer used.
  if (text.isBlank()) {
    Text(
      text = "",
      modifier = modifier,
      textAlign = textAlign,
      style = JewelTheme.typography.regular.copy(fontWeight = fontWeight)
    )
    return
  }
  val baseStyle: TextStyle = rememberGradumMarkdownStyling().paragraph.inlinesStyling.textStyle
    .copy(fontWeight = fontWeight)
  val editorFontSizeSp: Float = JewelTheme.editorTextStyle.fontSize.value
    .let { if (it <= 0f) 13f else it }
  RenderInlineTextWithChips(
    text = text,
    style = baseStyle,
    modifier = modifier,
    onUrlClick = onUrlClick,
    inlineCodeFontSizeSp = editorFontSizeSp,
  )
}

/**
 * Renders the parse-failure placeholder for a [MarkdownSegment.Table] that
 * the caller has determined to be unrenderable
 * ([MarkdownSegment.Table.isRenderable] is `false`). The chat bubble
 * substitutes this for any `Table` whose body is empty / blank — the raw
 * pipe syntax of the original Markdown block is not surfaced here, since
 * it's visually noisy and uninformative.
 */
@Composable
fun TableParseFailurePlaceholder(modifier: Modifier = Modifier) {
  val globalColors = org.jetbrains.jewel.foundation.LocalGlobalColors.current
  Text(
    text = message("gradum.markdown.table.parse.failed"),
    style = JewelTheme.typography.regular,
    color = globalColors.text.disabled,
    modifier = modifier.padding(vertical = GradumSpacing.sm)
  )
}

/**
 * Converts a CommonMark [TableBlock] (from the GFM tables extension) into
 * a [MarkdownSegment.Table] that [ScrollableTable] can render. Walks the
 * AST: [TableBlock] → [TableHead]/[TableBody] → [TableRow] → [TableCell],
 * serializing each cell's inline children to plain text.
 */
internal fun TableBlock.toMarkdownSegmentTable(): MarkdownSegment.Table {
  fun Node.childNodes(): List<Node> = buildList {
    val nc = NodeChildren.of(this@childNodes)
    if (nc.first != null) add(nc.first)
    addAll(nc.rest)
  }

  val headNode: TableHead? = childNodes().filterIsInstance<TableHead>().firstOrNull()
  val bodyNode: TableBody? = childNodes().filterIsInstance<TableBody>().firstOrNull()

  val firstHeadRow: TableRow? = headNode?.childNodes()?.filterIsInstance<TableRow>()?.firstOrNull()

  val headerCells: List<String> = firstHeadRow
    ?.childNodes()?.filterIsInstance<TableCell>()
    ?.map { serializeInlineChildren(it) }
    ?: emptyList()

  val alignments: List<TextAlign> = firstHeadRow
    ?.childNodes()?.filterIsInstance<TableCell>()
    ?.map { cell ->
      when (cell.alignment) {
        TableCell.Alignment.CENTER -> TextAlign.Center
        TableCell.Alignment.RIGHT -> TextAlign.End
        else -> TextAlign.Start
      }
    }
    ?: emptyList()

  val bodyRows: List<List<String>> = bodyNode
    ?.childNodes()?.filterIsInstance<TableRow>()
    ?.map { row ->
      row.childNodes().filterIsInstance<TableCell>()
        .map { serializeInlineChildren(it) }
    }
    ?: emptyList()

  return MarkdownSegment.Table(headerCells, bodyRows, alignments)
}
