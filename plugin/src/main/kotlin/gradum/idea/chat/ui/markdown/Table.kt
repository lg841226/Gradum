/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Table.kt  2026-08-26 12:30:26 Changed by gwy
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.settings.LocalEnableStickySections
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
fun MarkdownSegment.Table.isRenderable(): Boolean = rows.any { row ->
  row.any { it.isNotBlank() }
}


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
      segments.add(MarkdownSegment.Plain(text = plainBuffer.toString()))
      plainBuffer.clear()
    }
  }

  fun appendPlain(line: String) {
    if (plainBuffer.isNotEmpty()) plainBuffer.append('\n')
    plainBuffer.append(line)
  }

  while (lineIndex < lines.size) {
    val headerLine: String = lines[lineIndex]
    val separatorLine: String? = lines.getOrNull(index = lineIndex + 1)
    val headerIsLikely: Boolean = separatorLine != null
      && headerLine.length <= MarkdownStyle.Table.MAX_LINE_LENGTH
      && separatorLine.length <= MarkdownStyle.Table.MAX_LINE_LENGTH
      && headerLine.contains(char = '|')
      && isTableSeparator(tableRow = separatorLine)

    if (headerIsLikely) {
      val headers: List<String> = parseTableRow(headerLine)
      val alignments: List<TextAlign> =
        parseAlignments(separatorLine, expectedCount = headers.size)
          ?: List(size = headers.size.coerceAtLeast(minimumValue = 1)) { TextAlign.Start }

      val bodyLines: MutableList<String> = mutableListOf()
      var bodyLineIndex: Int = lineIndex + 2
      while (bodyLineIndex < lines.size) {
        val currentLine: String = lines[bodyLineIndex]
        val trimmedCurrent: String = currentLine.trim()
        if (trimmedCurrent.isEmpty() || !currentLine.contains(char = '|')) break
        bodyLines.add(currentLine).also { bodyLineIndex++ }
      }

      val bodyRows: List<List<String>> = bodyLines
        .filter { it.length <= MarkdownStyle.Table.MAX_LINE_LENGTH }
        .map { parseTableRow(it) }
        .map { row ->
          when {
            row.size < headers.size -> row + List(headers.size - row.size) { "" }
            row.size > headers.size -> row.take(n = headers.size)
            else -> row
          }
        }
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
      is MarkdownSegment.Plain -> splitPlainAtBlocks(plainText = segment.text)
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
    if (trimmed.startsWith(prefix = "|")) trimmed.substring(startIndex = 1) else trimmed
  val withoutTrailing: String =
    if (withoutLeading.endsWith(suffix = "|") && !withoutLeading.endsWith(suffix = "\\|"))
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
  if (!tableRow.contains(char = '|')) return false

  val cells: List<String> = parseTableRow(line = tableRow)
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

  return List(size = expectedCount) { cellIndex ->
    val trimmedCell: String = cells[cellIndex].trim()
    when {
      trimmedCell.startsWith(prefix = ":") && trimmedCell.endsWith(suffix = ":") -> TextAlign.Center
      trimmedCell.endsWith(suffix = ":") -> TextAlign.End
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
  val horizontalPaddingPx: Int = with(receiver = density) {
    MarkdownStyle.Table.CELL_HORIZONTAL_PADDING.roundToPx()
  }
  val tableBackground = Color.Transparent
  val markdownStyling = rememberGradumMarkdownStyling()
  val textMeasurer: TextMeasurer = rememberTextMeasurer()
  val baseStyle: TextStyle = markdownStyling.paragraph.inlinesStyling.textStyle
  val headerStyle: TextStyle = baseStyle.copy(fontWeight = FontWeight.Bold)
  val renderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current
  val paragraphStyling: MarkdownStyling.Paragraph = rememberGradumMarkdownStyling().paragraph

  val naturalColumnWidthsPx: IntArray = remember(key1 = table) {
    val widths = IntArray(size = table.header.size.coerceAtLeast(minimumValue = 1))
    val maxCellWidthPx: Int = with(receiver = density) {
      MarkdownStyle.Table.MAX_CELL_WIDTH.roundToPx()
    }

    fun measure(row: List<String>, style: TextStyle) {
      row.forEachIndexed { columnIndex, cell ->
        if (columnIndex >= widths.size) return@forEachIndexed
        val cellWidth: Int = textMeasurer.measure(
          maxLines = 1,
          style = style,
          softWrap = false,
          text = AnnotatedString(text = cell)
        ).size.width.coerceAtMost(maximumValue = maxCellWidthPx)
        if (cellWidth > widths[columnIndex]) widths[columnIndex] = cellWidth
      }
    }
    measure(row = table.header, headerStyle)
    table.rows.forEach { measure(row = it, baseStyle) }
    widths
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.lg)
      .clip(shape = MarkdownStyle.CodeBlock.CORNER_RADIUS)
      .background(color = tableBackground)
  ) {
    val containerWidthPx: Int = with(receiver = density) { maxWidth.roundToPx() }
    val minCellWidthPx: Int = with(receiver = density) { MarkdownStyle.Table.MIN_CELL_WIDTH.roundToPx() }
    val maxCellWidthPx: Int = with(receiver = density) { MarkdownStyle.Table.MAX_CELL_WIDTH.roundToPx() }
    val finalColumnWidthsPx: IntArray = remember(
      naturalColumnWidthsPx, containerWidthPx, minCellWidthPx, maxCellWidthPx, horizontalPaddingPx
    ) {
      distributeTableWidth(
        minCellWidthPx = minCellWidthPx,
        maxCellWidthPx = maxCellWidthPx,
        containerWidthPx = containerWidthPx,
        horizontalPaddingPx = horizontalPaddingPx,
        naturalColumnWidthsPx = naturalColumnWidthsPx
      )
    }
    val scrollState = rememberScrollState()

    val stickyRegistry: StickySectionRegistry = LocalStickySectionRegistry.current
    val enableSticky: Boolean = LocalEnableStickySections.current
    val sectionEntry: StickySectionEntry? =
      if (isSimplified || !enableSticky) null
      else {
        val sectionId: Any = remember { Any() }
        val stickyHeaderProvider:
          @Composable () -> Unit = {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(shape = MarkdownStyle.CodeBlock.STICKY_SECTION_TOP_CORNERS)
              .background(color = tableBackground)
          ) {
            DisableSelection {
              Column {
                TableToolbar(table = table)
                Box(
                  modifier = Modifier
                    .width(with(receiver = density) { containerWidthPx.toDp() })
                    .horizontalScroll(scrollState)
                ) {
                  TableHeaderRow(
                    density = density,
                    renderer = renderer,
                    header = table.header,
                    onUrlClick = onUrlClick,
                    alignments = table.alignments,
                    paragraphStyling = paragraphStyling,
                    columnWidthsPx = finalColumnWidthsPx,
                    horizontalPaddingPx = horizontalPaddingPx,
                  )
                }
              }
            }
          }
        }
        remember { stickyRegistry.register(sectionId, toolbar = stickyHeaderProvider) }
      }

    if (sectionEntry != null) {
      DisposableEffect(key1 = sectionEntry) {
        onDispose { stickyRegistry.unregister(sectionEntry) }
      }
    }

    val sectionBoundsModifier: Modifier =
      if (sectionEntry != null) {
        Modifier.onGloballyPositioned { coordinates ->
          val topLeft: Offset = coordinates.localToWindow(relativeToLocal = Offset.Zero)
          val bottomRight: Offset = coordinates.localToWindow(
            relativeToLocal = Offset(
              x = coordinates.size.width.toFloat(),
              y = coordinates.size.height.toFloat()
            )
          )
          stickyRegistry.updateBounds(
            sectionEntry,
            boundsInWindow = Rect(
              left = topLeft.x, topLeft.y,
              right = bottomRight.x, bottomRight.y
            )
          )
        }
      } else Modifier

    Column(modifier = Modifier.fillMaxWidth().then(sectionBoundsModifier)) {
      if (!isSimplified) DisableSelection {
        TableToolbar(table = table)
      }
      Box(modifier = Modifier.fillMaxWidth()) {
        Box(
          modifier = Modifier
            .width(with(receiver = density) { containerWidthPx.toDp() })
            .padding(bottom = MarkdownStyle.Table.SCROLLBAR_RESERVED_SPACE)
            .horizontalScroll(scrollState)
        ) {
          Column {
            TableHeaderRow(
              density = density,
              renderer = renderer,
              header = table.header,
              onUrlClick = onUrlClick,
              alignments = table.alignments,
              paragraphStyling = paragraphStyling,
              columnWidthsPx = finalColumnWidthsPx,
              horizontalPaddingPx = horizontalPaddingPx
            )
            val dividerColor = JewelTheme.globalColors.borders.normal
            val tableContentWidthPx = finalColumnWidthsPx.sum() +
              horizontalPaddingPx * 2 * finalColumnWidthsPx.size
            val tableContentWidthDp = with(receiver = density) { tableContentWidthPx.toDp() }
            Box(
              modifier = Modifier
                .height(1.dp)
                .background(dividerColor)
                .width(tableContentWidthDp)
            )
            table.rows.forEachIndexed { rowIndex, row ->
              Row {
                row.forEachIndexed { columnIndex, cell ->
                  SafeMarkdownText(
                    text = cell,
                    modifier = Modifier
                      .width(
                        with(receiver = density) {
                          (finalColumnWidthsPx.getOrElse(columnIndex) { 0 } + horizontalPaddingPx * 2)
                            .toDp()
                        }
                      )
                      .padding(
                        horizontal = MarkdownStyle.Table.CELL_HORIZONTAL_PADDING,
                        vertical = MarkdownStyle.Table.CELL_VERTICAL_PADDING
                      ),
                    onUrlClick = onUrlClick,
                    textAlign = table.alignments.getOrNull(columnIndex) ?: TextAlign.Start,
                    blockRenderer = renderer,
                    paragraphStyling = paragraphStyling
                  )
                }
              }
              if (rowIndex < table.rows.lastIndex) {
                Box(
                  modifier = Modifier
                    .width(tableContentWidthDp)
                    .height(1.dp)
                    .background(dividerColor)
                )
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
  density: Density,
  header: List<String>,
  columnWidthsPx: IntArray,
  horizontalPaddingPx: Int,
  alignments: List<TextAlign>,
  onUrlClick: (String) -> Unit,
  renderer: MarkdownBlockRenderer,
  paragraphStyling: MarkdownStyling.Paragraph
) {
  Row(
    modifier = Modifier.fillMaxWidth()
  ) {
    header.forEachIndexed { columnIndex, cell ->
      SafeMarkdownText(
        text = cell,
        modifier = Modifier
          .width(
            with(receiver = density) {
              (columnWidthsPx.getOrElse(columnIndex) { 0 } + horizontalPaddingPx * 2)
                .toDp()
            }
          )
          .padding(
            horizontal = MarkdownStyle.Table.CELL_HORIZONTAL_PADDING,
            vertical = MarkdownStyle.Table.CELL_VERTICAL_PADDING
          ),
        fontWeight = FontWeight.SemiBold,
        onUrlClick = onUrlClick,
        textAlign = alignments.getOrNull(columnIndex) ?: TextAlign.Start,
        blockRenderer = renderer,
        paragraphStyling = paragraphStyling
      )
    }
  }
}

/** Top-row toolbar: "Table" label + copy button. Mirrors the code block toolbar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableToolbar(table: MarkdownSegment.Table) {
  val rows = table.rows.size
  val cols = table.header.size
  val scope = rememberCoroutineScope()
  var isCopied: Boolean by remember { mutableStateOf(value = false) }
  val tableAsMarkdown: String = remember(key1 = table) { tableToMarkdownString(table) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        start = GradumSpacing.md,
        end = GradumSpacing.sm,
        top = GradumSpacing.sm
      ),
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
        color = JewelTheme.globalColors.text.info,
        fontFamily = JewelTheme.editorTextStyle.fontFamily
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
          key =
            if (isCopied) AllIconsKeys.Actions.Checked
            else AllIconsKeys.General.Copy
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
    { row ->
      row.joinToString(separator = " | ", prefix = "| ", postfix = " |") {
        escapeCell(it)
      }
    }
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
 * Picks the final column widths for a table. Clamps every column into
 * `[minCellWidthPx, maxCellWidthPx]`, then if the clamped total is wider
 * than the container, returns the clamped widths and lets `horizontalScroll`
 * take over. Otherwise, scales every column up by the same factor so the
 * new total exactly matches the container width; no column may exceed
 * [maxCellWidthPx] after scaling. The leftover residue is given first to
 * the last column (until it saturates), then spread backward to the
 * other non-saturated columns.
 */
internal fun distributeTableWidth(
  naturalColumnWidthsPx: IntArray, containerWidthPx: Int,
  minCellWidthPx: Int, maxCellWidthPx: Int, horizontalPaddingPx: Int
): IntArray {
  if (naturalColumnWidthsPx.isEmpty()) return naturalColumnWidthsPx

  val paddingPerColumn = horizontalPaddingPx * 2
  val columnCount = naturalColumnWidthsPx.size

  val clampedWidths = IntArray(size = columnCount) { index ->
    naturalColumnWidthsPx[index]
      .coerceIn(minCellWidthPx, maxCellWidthPx)
  }

  val naturalTotal = clampedWidths.sum() + paddingPerColumn * columnCount
  if (naturalTotal >= containerWidthPx) return clampedWidths

  val scaledWidths = containerWidthPx.toFloat() / naturalTotal.toFloat()
  val scalingFactor = IntArray(size = columnCount) { index ->
    (clampedWidths[index] * scaledWidths).toInt().coerceAtMost(maximumValue = maxCellWidthPx)
  }

  val scaledTotal = scalingFactor.sum() + paddingPerColumn * columnCount
  val leftover = containerWidthPx - scaledTotal
  if (leftover != 0) {
    val lastIndex = columnCount - 1
    if (scalingFactor[lastIndex] < maxCellWidthPx) {
      scalingFactor[lastIndex] = (scalingFactor[lastIndex] + leftover).coerceAtMost(maximumValue = maxCellWidthPx)
    } else {
      var remaining = leftover
      for (columnIndex in (columnCount - 1) downTo 0) {
        if (remaining == 0) break
        val headroom = maxCellWidthPx - scalingFactor[columnIndex]
        if (headroom > 0) {
          val pixelsToAdd = headroom.coerceAtMost(maximumValue = remaining)
          scalingFactor[columnIndex] += pixelsToAdd
          remaining -= pixelsToAdd
        }
      }
    }
  }

  return scalingFactor
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
  fontWeight: FontWeight? = null,
  onUrlClick: (String) -> Unit = {},
  textAlign: TextAlign = TextAlign.Unspecified,
  @Suppress("UNUSED_PARAMETER") processor:
  MarkdownProcessor = GradumMarkdownProcessor,
  @Suppress("UNUSED_PARAMETER") blockRenderer: MarkdownBlockRenderer =
    LocalMarkdownBlockRenderer.current,
  @Suppress("UNUSED_PARAMETER") paragraphStyling: MarkdownStyling.Paragraph =
    rememberGradumMarkdownStyling().paragraph
) {
  if (text.isBlank()) {
    Text(
      text = "",
      modifier = modifier,
      textAlign = textAlign,
      style = JewelTheme.typography.regular.copy(fontWeight = fontWeight)
    )
    return
  }
  val baseStyle: TextStyle = rememberGradumMarkdownStyling()
    .paragraph.inlinesStyling.textStyle
    .copy(fontWeight = fontWeight)
  val editorFontSizeSp: Float = JewelTheme.editorTextStyle
    .fontSize.value
    .let { if (it <= 0f) 13f else it }
  val editorFontFamily: FontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
  RenderInlineTextWithChips(
    text = text,
    style = baseStyle,
    modifier = modifier,
    onUrlClick = onUrlClick,
    editorFontFamily = editorFontFamily,
    inlineCodeFontSizeSp = editorFontSizeSp
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
  val textErrorColor = JewelTheme.globalColors.text.error
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    modifier = modifier
      .fillMaxWidth()
      .horizontalScroll(state = rememberScrollState())
  ) {
    Icon(
      contentDescription = null,
      key = AllIconsKeys.Status.FailedInProgress
    )
    Text(
      maxLines = 1,
      color = textErrorColor,
      text = message(key = "gradum.markdown.table.parse.failed"),
      style = JewelTheme.typography.editorTextStyle.copy(
        color = textErrorColor
      )
    )
  }
}

/**
 * Converts a CommonMark [TableBlock] (from the GFM tables extension) into
 * a [MarkdownSegment.Table] that [ScrollableTable] can render. Walks the
 * AST: [TableBlock] → [TableHead]/[TableBody] → [TableRow] → [TableCell],
 * serializing each cell's inline children to plain text.
 */
internal fun TableBlock.toMarkdownSegmentTable(): MarkdownSegment.Table {
  fun Node.childNodes(): List<Node> = buildList {
    val nodeChildren = NodeChildren.of(parent = this@childNodes)
    if (nodeChildren.first != null) add(nodeChildren.first)
    addAll(elements = nodeChildren.rest)
  }

  val headNode: TableHead? = childNodes().filterIsInstance<TableHead>().firstOrNull()
  val bodyNode: TableBody? = childNodes().filterIsInstance<TableBody>().firstOrNull()

  val firstHeadRow: TableRow? = headNode?.childNodes()?.filterIsInstance<TableRow>()?.firstOrNull()

  val headerCells: List<String> = firstHeadRow
    ?.childNodes()?.filterIsInstance<TableCell>()
    ?.map { serializeInlineChildren(containerNode = it) }
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
        .map { serializeInlineChildren(containerNode = it) }
    }
    ?: emptyList()

  return MarkdownSegment.Table(headerCells, bodyRows, alignments)
}
