/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumMarkdownTable.kt  2026-07-08 21:10:14 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.copyToClipboard
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock
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
 * The [MarkdownProcessor] used across the chat UI. Registers two
 * extensions on top of stock CommonMark:
 * - `GitHubStrikethroughProcessorExtension` — adds `~~strike~~` support
 *   both inside table cells (rendered by [MarkdownText]) and in the
 *   surrounding prose.
 * - `AutolinkProcessorExtension` — turns bare `<https://...>` and
 *   plain URLs into clickable links.
 *
 * GFM table syntax is *not* registered here: the chat UI strips GFM
 * tables out at the call site in AssistantChatBubble and renders
 * them with [ScrollableTable]. There is no GFM-table input left for
 * the processor to see.
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
 * A piece of Markdown content. Either a plain block of text, or a
 * parsed GFM table. The renderer (see AssistantChatBubble) decides
 * what to do with a [Table] that has no renderable content — it
 * substitutes a muted placeholder instead of letting the table
 * silently render as an empty grid.
 */
sealed interface MarkdownSegment {
  data class Plain(val text: String) : MarkdownSegment
  data class Table(
    val header: List<String>,
    val alignments: List<TextAlign>,
    val rows: List<List<String>>
  ) : MarkdownSegment
}

/**
 * Does this [MarkdownSegment.Table] have any visible body content?
 * The chat bubble uses this as the gate: a `Table` that fails the
 * check is replaced with the parse-failure placeholder instead of
 * being rendered as a header-only / empty grid.
 *
 * Concretely: there must be at least one non-blank cell in at least
 * one body row. The header alone is not enough — a header without
 * any data underneath is just a row of labels, not a table.
 */
fun MarkdownSegment.Table.isRenderable(): Boolean =
  rows.any { row -> row.any { it.isNotBlank() } }

/**
 * Per-line length cap. Lines longer than this are not considered as
 * possible table rows — they are always treated as plain prose. A
 * single huge URL, an embedded code dump, or a misformatted cell can
 * otherwise blow the cell-width measurement up to thousands of pixels
 * and visibly shove adjacent cells off-screen.
 */
private const val MAX_TABLE_LINE_LENGTH: Int = 5_000

/**
 * Splits a Markdown string into alternating [MarkdownSegment.Plain]
 * and [MarkdownSegment.Table] segments. GFM tables are detected line
 * by line: a header line immediately followed by a separator line
 * (`| --- | :---: |`) starts a table; subsequent `|`-delimited lines
 * are body rows until the first non-table line.
 *
 * Every header+separator block becomes a [MarkdownSegment.Table].
 * Whether it ends up rendered as a table or as a parse-failure
 * placeholder is the caller's decision (see
 * [MarkdownSegment.Table.isRenderable]); the parser's only job is
 * structural — it does not pass judgment on whether the data is
 * meaningful. A header+separator with no usable body rows still
 * becomes a `Table` with an empty `rows` list, and the chat bubble
 * will substitute the placeholder for it.
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

      // Collect body lines greedily until the first blank or `|`-free
      // line. Whatever the column-count outcome below, the whole block
      // has to be consumed together — partial output would let stray
      // `|` characters leak into the surrounding prose.
      val bodyLines: MutableList<String> = mutableListOf()
      var bodyLineIndex: Int = lineIndex + 2
      while (bodyLineIndex < lines.size) {
        val currentLine: String = lines[bodyLineIndex]
        val trimmedCurrent: String = currentLine.trim()
        if (trimmedCurrent.isEmpty() || !currentLine.contains('|')) break
        bodyLines.add(currentLine)
        bodyLineIndex++
      }

      val bodyRows: List<List<String>> = bodyLines
        .filter { it.length <= MAX_TABLE_LINE_LENGTH }
        .map { parseTableRow(it) }
        // Drop rows with mismatched column count — these are usually misparsed
        // prose with a stray `|`, not real table data. The caller will later
        // check [isRenderable] to decide whether to render this as a table.
        .filter { it.size == headers.size }

      flushPlain()
      segments.add(MarkdownSegment.Table(headers, alignments, bodyRows))
      lineIndex = bodyLineIndex
      continue
    }
    appendPlain(headerLine); lineIndex++
  }
  flushPlain()
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
private val CellHorizontalPadding: Dp = 10.dp

/**
 * Total vertical cell padding (4.dp on each side).
 */
private val CellVerticalPadding: Dp = 8.dp

/**
 * Floor on a column's content width. A column whose widest cell
 * measures narrower than this gets clamped up to it before we
 * start distributing leftover space — without it, a single-character
 * column would stay pathologically narrow and look like a vertical
 * slice in the table.
 */
private val MinCellWidthDp: Dp = 70.dp

/**
 * Vertical space reserved at the bottom of the scrollable table area
 * for the [HorizontalScrollbar] to live in. Pads the inner scrollable
 * [Box] by this much so the bottom row of cells stays above the
 * scrollbar instead of being partially covered by it.
 */
private val ScrollbarReservedSpace: Dp = 8.dp

/**
 * Renders a [MarkdownSegment.Table] as a plain Compose layout: one
 * header row plus body rows, each row a horizontal `Row` of
 * [MarkdownText] cells with a fixed per-column width. The whole
 * table is wrapped in a horizontally scrollable [Box] so a wide
 * table shows a horizontal scrollbar instead of being squeezed.
 *
 * **Caller contract**: the caller is expected to have already
 * checked [MarkdownSegment.Table.isRenderable] and substituted a
 * placeholder for any non-renderable `Table`. This function does not
 * bail out for empty / degenerate input — if you give it a header
 * with nobody, you'll get a header row with nothing underneath it.
 * That visible header-only state is exactly what [isRenderable] is
 * designed to filter out before reaching here.
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
 * - Odd body rows → `borders.normal @ 8% alpha` (subtle stripe that
 *   shows up in both light and dark mode; just repainting
 *   `panelBackground` on the cell would make odd rows visually
 *   identical to even rows since the outer Box is also panel)
 *
 * Padding: 8.dp horizontal × 4.dp vertical, on every cell.
 */
@Composable
fun ScrollableTable(
  table: MarkdownSegment.Table,
  modifier: Modifier = Modifier,
  isSimplified: Boolean = false,
  onUrlClick: (String) -> Unit = {}
) {
  val density: Density = LocalDensity.current
  val horizontalPaddingPx: Int = with(density) { CellHorizontalPadding.roundToPx() }
  val textMeasurer: TextMeasurer = rememberTextMeasurer()
  val baseStyle: TextStyle = JewelTheme.typography.regular
  val headerStyle: TextStyle = baseStyle.copy(fontWeight = FontWeight.Bold)
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
      .clip(RoundedCornerShape(8.dp))
    // horizontalScroll goes on the *inner* Box below, not here.
    // If it were on this modifier chain, BoxWithConstraints would
    // read the post-scroll maxWidth — which is Constraints.Infinity
    // — and feed it to distributeTableWidth as a sane-looking
    // pixel count. That would then propagate into Modifier.width()
    // on every cell, and Layout would refuse to pack a billion-pixel constraint into the constraint record. Keeping the
    // scrollable modifier on the inner Box leaves the outer
    // BoxWithConstraints' maxWidth pinned to the chat panel's real
    // available width.
  ) {
    val containerWidthPx: Int = with(density) { maxWidth.roundToPx() }
    val minCellWidthPx: Int = with(density) { MinCellWidthDp.roundToPx() }
    val finalColumnWidthsPx: IntArray = remember(
      naturalColumnWidthsPx, containerWidthPx, minCellWidthPx, horizontalPaddingPx
    ) {
      distributeTableWidth(
        naturalColumnWidthsPx = naturalColumnWidthsPx,
        containerWidthPx = containerWidthPx,
        minCellWidthPx = minCellWidthPx,
        horizontalPaddingPx = horizontalPaddingPx
      )
    }
    val scrollState = rememberScrollState()

    // Column stack: `TableToolbar` sits above the scrollable table
    // area. Both children live inside the BoxWithConstraints' clip,
    // so the toolbar's top corners follow the panel's rounded
    // shape — same as the code block's `CodeBlockToolbar` above its
    // highlighted content. In `isSimplified` mode (used by the
    // ThinkingIndicator) the toolbar is dropped: the surrounding
    // reasoning text is already greyed and a copy button would
    // duplicate the affordance, so the table renders as a
    // bare panel.
    Column(modifier = Modifier.fillMaxWidth()) {
      if (!isSimplified) TableToolbar(table = table)
      // Outer Box hosts both the scrollable table area and the
      // `HorizontalScrollbar` overlay. The inner Box (with the
      // horizontalScroll modifier) is the actual scroll target;
      // the scrollbar sits in the reserved bottom padding and gets
      // clipped to the rounded corners by the outer BoxWithConstraints.
      Box(modifier = Modifier.fillMaxWidth()) {
        Box(
          modifier = Modifier
            .width(with(density) { containerWidthPx.toDp() })
            .padding(bottom = ScrollbarReservedSpace)
            .horizontalScroll(scrollState)
        ) {
          Column {
            Row {
              table.header.forEachIndexed { columnIndex, cell ->
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
                      horizontal = CellHorizontalPadding,
                      vertical = CellVerticalPadding
                    ),
                  onUrlClick = onUrlClick,
                  blockRenderer = renderer,
                  fontWeight = FontWeight.SemiBold,
                  paragraphStyling = paragraphStyling,
                  textAlign = table.alignments.getOrNull(columnIndex) ?: TextAlign.Start
                )
              }
            }
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
                        horizontal = CellHorizontalPadding,
                        vertical = CellVerticalPadding
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
            .fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Top-row toolbar above the rendered table. Mirrors
 * [gradum.idea.chat.ui.GradumCodeBlockRenderer.CodeBlockToolbar] so
 * the table's action area reads as a sibling of the code block's:
 *
 *  1. **Table label** — small muted editor-style text on the left
 *     (the code block shows a `kotlin`/`text` language tag in the
 *     same slot; a table has no language, so a static "Table" label
 *     stands in).
 *  2. **Copy button** — serializes the table back to a GFM-flavored
 *     Markdown source string (header row, `---` separator, body
 *     rows) and pushes it to the system clipboard. Briefly swaps
 *     icon to `Checked` for 1 s as click feedback, same as the code
 *     block's copy button.
 *
 * The toolbar is laid out with the same padding/arrangement/alignment
 * as [CodeBlockToolbar] (8/4/4/0 horizontal-end-top-bottom, items
 * spaced by [GradumSpacing.sm], vertically centred) so the two read
 * as the same component family.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TableToolbar(table: MarkdownSegment.Table) {
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
    Text(
      text = message("gradum.table"),
      fontWeight = FontWeight.Medium,
      style = JewelTheme.editorTextStyle
    )
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
 * Serializes a [MarkdownSegment.Table] back to a GFM-flavored
 * Markdown source string suitable for pasting into another
 * Markdown-aware editor:
 *
 *     | H1 | H2 |
 *     | --- | --- |
 *     | a | b |
 *
 * Pipes (`|`) inside cell content are re-escaped to `\|` so the
 * output round-trips through [parseTableRow] unchanged. Alignment
 * markers are dropped — a plain `---` separator is emitted for
 * every column. The header cell strings come back from
 * [splitMarkdownAtTables] as their visible form (escapes resolved),
 * so escaping on the way out is required for fidelity.
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
 * Picks the final column widths for a table.
 *
 * Three steps:
 * 1. Clamp every column to at least [minCellWidthPx]. A 2-character
 *    cell shouldn't render as a 24.dp sliver.
 * 2. Compute the natural total (`Σ clamped + Σ padding`). If the
 *    natural total is wider than the container, the table would
 *    overflow — but [Modifier.horizontalScroll] on the outer Box
 *    handles that case, so we just return the clamped natural widths
 *    and let the user scroll.
 * 3. Otherwise, scale every column up by the same factor so the new
 *    total exactly matches the container width. The last column
 *    absorbs the round-down residue so the widths sum precisely.
 *
 * Pure arithmetic, no Compose / no density — easy to unit-test.
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
 * `Text` (from `org.jetbrains.jewel.ui.component.Text`, already in
 * scope via the wildcard import above) when the parse isn't going
 * to play nicely with MarkdownText.
 *
 * Trade-off: the dry-run doubles the Markdown parsing work for the
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
  processor: MarkdownProcessor = GradumMarkdownProcessor,
  blockRenderer: MarkdownBlockRenderer = LocalMarkdownBlockRenderer.current,
  paragraphStyling: MarkdownStyling.Paragraph = rememberGradumMarkdownStyling().paragraph
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
      style = JewelTheme.typography.regular.copy(fontWeight = fontWeight)
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
      textAlign = textAlign
    )
  } else {
    // MarkdownText can't render this safely (rare, but real: hostile
    // Markdown input that the processor can't produce a single
    // Paragraph block for). Fall back to plain Text so the user still
    // sees the cell's content as raw text instead of a crash dialog.
    Text(
      text = text,
      modifier = modifier,
      textAlign = textAlign,
      style = JewelTheme.typography.regular.copy(fontWeight = fontWeight)
    )
  }
}
