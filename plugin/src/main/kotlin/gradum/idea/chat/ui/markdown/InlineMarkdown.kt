/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.ui.GradumSpacing
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.linkStyle


private val log: Logger = Logger.getInstance("gradum.idea.chat.ui.markdown.InlineMarkdown")


private const val INLINE_CODE_PLACEHOLDER: Char = '\uE000'
private const val INLINE_CODE_TEXT_TAG: String = "INLINE_CODE_TEXT"

/** Compose's internal tag for inline-content lookups in `Text(annotated, inlineContent = ...)`. */
internal const val INLINE_CONTENT_TAG: String = "androidx.compose.foundation.text.inlineContent"

private const val INLINE_URL_TAG: String = "INLINE_URL"
private const val IMAGE_ALT_PLACEHOLDER_BASE: Char = '\uE001'

internal const val FOOTNOTE_TEXT_TAG: String = "FOOTNOTE_TEXT"
private const val FOOTNOTE_PLACEHOLDER_BASE: Char = '\uE002'

/**
 * PUA base for inline LaTeX placeholders. Distinct from the
 * code / image / footnote bases so the four placeholder
 * tables never collide in the surrounding `Text(annotated,
 * inlineContent = ...)` map.
 */
private const val LATEX_PLACEHOLDER_BASE: Char = '\uE003'

/**
 * PUA range reserved for pre-processing `\(…\)`-form LaTeX
 * formulas out of the raw text BEFORE CommonMark sees them.
 *
 * Why a pre-processing pass is necessary: CommonMark treats
 * `\(` as a backslash-escape sequence (the leading backslash
 * is dropped, the `(` is left as a literal paren). The
 * LaTeX-original `\(…\)` form many models emit gets chewed
 * up by that rule — by the time the AST walker sees the Text
 * node, the backslashes are gone and the formula has bled
 * into the surrounding prose as a plain `(…)`.
 *
 * The pre-processing pass [preprocessParenLatexFormulas]
 * scans the raw text for `\(…\)` and replaces each match
 * with a single PUA char from the `U+E100`–`U+E1FF` range,
 * keeping the formula text in a side table. The replacement
 * char survives CommonMark parsing verbatim (PUA is not
 * processed by the parser), and the walker scans for those
 * PUA chars with [INLINE_LATEX_PAREN_MARKER_REGEX] and looks
 * up the formula in the side table.
 *
 * The range is disjoint from the chip placeholder bases
 * (U+E000...U+E003) and supports up to 256 paren-form formulas
 * in a single message — more than enough for chat.
 */
private const val PAREN_LATEX_MARKER_RANGE_START: Int = 0xE100
private const val PAREN_LATEX_MARKER_RANGE_END: Int = 0xE1FF
private const val PAREN_LATEX_MARKER_RANGE_SIZE: Int =
  PAREN_LATEX_MARKER_RANGE_END - PAREN_LATEX_MARKER_RANGE_START + 1

private const val IMAGE_ALT_ICON_EM_SCALE: Float = 1.4f

// Chip/footnote horizontal padding (inside the rounded background). Generous
// enough that long inline code (e.g. `explore_project`) doesn't visually
// press against the right edge of the chip.
private val inlineCodePaddingHorizontal: Dp = GradumSpacing.sm

// Chip/footnote vertical padding. Was 0.dp — that meant the descenders of
// letters like 'p' / 'g' / 'y' sat right at the bottom of the rounded
// background and were clipped by the 4-dp rounded corners. 2.dp is enough
// room for the descenders to clear the corner while keeping the chip
// visually compact.
private val inlineCodePaddingVertical: Dp = GradumSpacing.xs


private val inlineCodeCornerRadius: Dp = GradumSpacing.sm
internal const val INLINE_CODE_BACKGROUND_ALPHA: Float = 0.12f
private const val MONOSPACE_LATIN_RATIO: Float = 0.6f
private const val MONOSPACE_CJK_RATIO: Float = 1.0f
private const val PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 1.0f

/**
 * Line-height multiplier for inline LaTeX placeholders.
 * Tall formulas (fractions, radicals) need extra height to avoid overflowing the line box and covering text below.
 * Default 2.5× handles 95%+ of cases; trivial formulas waste ~1-2sp which is absorbed into line rhythm.
 * Future: per-formula heuristic based on LaTeX command counts.
 */
private const val INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 2.5f

/**
 * Placeholder padding for chips/footnotes vs. LaTeX formulas.
 * Chips need 10sp: descenders need vertical room, and the 0.6 Latin ratio underestimates monospace advance width.
 * LaTeX needs only 2sp: math glyphs are sparse and the library draws tight bounding boxes.
 */
private const val INLINE_CODE_PLACEHOLDER_PADDING_SP: Float = 10f

/**
 * LaTeX placeholder padding. 2sp is sufficient — the renderer draws tight bounding boxes, unlike chip text which needs 10sp.
 * Reduced from 8sp after user feedback that 8sp left visible gaps on the right of formulas.
 */
private const val PLACEHOLDER_WIDTH_PADDING_SP: Float = 2f

/**
 * Inline-LaTeX placeholder padding. Alias of 2sp, but named distinctly so call sites read clearly.
 * The renderer draws tight bounding boxes; 2sp is sufficient. Increasing would re-introduce the right-side gap users reported.
 */
private const val INLINE_LATEX_PLACEHOLDER_PADDING_SP: Float = PLACEHOLDER_WIDTH_PADDING_SP
private const val FALLBACK_FONT_SIZE_SP: Float = 14f
private const val FALLBACK_EM_FONT_SIZE_SP: Float = 14f
private const val MIN_OPAQUE_TINT_ALPHA: Float = 0.1f
private const val BAIL_REASON_LOG_PREVIEW_CHARS: Int = 80
private const val PARSE_FAILURE_LOG_PREVIEW_CHARS: Int = 120
private const val WALK_FAILURE_LOG_PREVIEW_CHARS: Int = 120


/** Returns `true` for CJK ideographs / full-width punctuation (~1.0 font size vs ~0.6 Latin). */
@Suppress("MagicNumber")
private fun isCjkChar(char: Char): Boolean = when (char.code) {
  in 0x4E00..0x9FFF,
  in 0x3400..0x4DBF,
  in 0xF900..0xFAFF,
  in 0x3000..0x303F,
  in 0xFF00..0xFFEF -> true

  else -> false
}


/** Stateless CommonMark parser with the GFM Strikethrough extension enabled. */
private val commonmarkParser: Parser = Parser.builder()
  .extensions(listOf(StrikethroughExtension.create()))
  .build()


/**
 * One renderable inline representation: PUA-placeholder `AnnotatedString` +
 * the chip `InlineTextContent` map + collected URL annotations for click
 * handling. Returned from [rememberInlineMarkdownRender].
 */
data class InlineMarkdownRender(
  val paragraphCount: Int,
  val annotated: AnnotatedString,
  val urlAnnotations: List<UrlAnnotation>,
  val inlineContent: Map<String, InlineTextContent>,
)

/** A (text-offset range, URL) pair attached to a link's text range. */
data class UrlAnnotation(val start: Int, val end: Int, val url: String)

/**
 * One chunk of a rendered inline line — either prose (rendered via
 * `androidx.compose.foundation.text.Text` with `inlineContent` for
 * code chips) or a clickable link (rendered via Jewel's
 * [org.jetbrains.jewel.ui.component.ExternalLink]). [splitIntoInlineSegments]
 * slices the walker output at link boundaries so the link can be a
 * standalone `Composable` (with the external-link icon) instead of a
 * `UrlAnnotation` tapped via `pointerInput` on the surrounding text.
 */
sealed interface InlineSegment {
  /** A prose chunk. May contain PUA placeholders for code chips. */
  data class TextSegment(
    val annotated: AnnotatedString, val inlineContent: Map<String, InlineTextContent>
  ) : InlineSegment

  /** A link chunk. [text] is the visible label, [url] the click target. */
  data class LinkSegment(val text: String, val url: String) : InlineSegment
}

/** Outcome of an inline parse. `render == null` → caller falls back to native `Markdown(...)`. */
data class InlineMarkdownRenderResult(val render: InlineMarkdownRender?, val bailReason: String?)

/**
 * Parse a Plain segment of chat text into an [InlineMarkdownRenderResult].
 * Bail cases: blank input, non-`Paragraph` blocks, parse failure.
 */
@Composable
fun rememberInlineMarkdownRender(plainText: String): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return InlineMarkdownRenderResult(render = null, bailReason = null)
  val chipTint: Color = resolveInlineCodeTint()
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val imageAltColor: Color = resolveImageAltColor()
  val fontSizeSp: Float = resolveEditorFontSizeSp()
  val editorFontFamily: FontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
  return remember(plainText, chipTint, linkColor, imageAltColor, fontSizeSp, editorFontFamily) {
    parseInlineMarkdown(
      chipTint = chipTint,
      linkColor = linkColor,
      plainText = plainText,
      fontSizeSp = fontSizeSp,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily
    )
  }
}


/**
 * Walk a parent node's inline children directly into an
 * [InlineMarkdownRenderResult]. Used for blocks whose
 * serialize-then-re-parse round-trip changes the block structure —
 * the canonical example is a heading whose inline content starts
 * with a list marker, e.g. `### 2. **bold**`. Serializing strips
 * the `### ` prefix, leaving `"2. **bold**"`, and the reparse in
 * [parseInlineMarkdown] then produces an `OrderedList` (not a
 * `Paragraph`), which bails and falls back to plain text — losing
 * the bold. Walking the original AST skips that round-trip and
 * keeps the formatting. Bail cases: walker exception.
 */
@Composable
fun rememberInlineMarkdownRenderFromNode(parentNode: Node): InlineMarkdownRenderResult {
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val imageAltColor: Color = resolveImageAltColor()
  val fontSizeSp: Float = resolveEditorFontSizeSp()
  val editorFontFamily: FontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
  return remember(parentNode, linkColor, imageAltColor, fontSizeSp, editorFontFamily) {
    parseInlineNodes(
      linkColor = linkColor,
      parentNode = parentNode,
      fontSizeSp = fontSizeSp,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily
    )
  }
}


/**
 * Regex to detect inline footnotes in three flavors:
 * - Pandoc-style: `^[footnote text]`  (group 2 = content)
 * - CommonMark-style: `[^reference]`  (group 3 = ref)
 * - Plain bracket-number: `[1]` `[2]`  (group 4 = digit)
 *
 * Used by [renderTextInline] to split text into prose and footnote segments.
 */
internal val INLINE_FOOTNOTE_REGEX: Regex = Regex("""(\^\[([^]]*)]|\[\^([^]]*)]|\[(\d+)])""")


/**
 * Regex to detect inline LaTeX formulas: `$…$. Non-greedy,
 * no newlines, requires at least 1 char between the two $
 * markers. Used by [renderTextInline] to split text into prose
 * and LaTeX segments; matched ranges are rendered through the
 * huarangmeng/latex library.
 *
 * The block-level $$…$$ (each $$ on its own line) is
 * handled separately by the CommonMark [LatexBlockExtension] —
 * this regex is intentionally only matching the single-$
 * inline form so the two don't fight.
 *
 * ---
 *
 * Implementation detail: matches both inline LaTeX dollar forms:
 *   1) $$…$$ — what the LLM writes when it produces block-style
 *      delimiters inside a paragraph (e.g. on the same line as
 *      surrounding prose instead of on its own line per the
 *      block-parser rule). The $  form MUST be the first
 *      alternative so the engine tries it before $…$ — otherwise
 *      the regex would match the inner $x$ of $$x^2$$ and leave
 *      one stray $ on each side, leaking into the UI.
 *   2) $…$ — the standard inline-LaTeX form.
 *
 * [^$\n]+? excludes both $ and \n. Newline exclusion means
 * $\nx^2$ (the dollar pair split across lines) never matches —
 * CommonMark would have moved that onto a new line as prose.
 *
 * Capture-group shape: TWO groups. The first alt's body is in
 * `groupValues[1]`; the second alt's body is in `groupValues[2]`.
 * The previous shape used a non-capturing group for the second
 * alt, which left `groupValues[1]` empty for $x^2$ matches — a
 * latent bug that the production walker used to mask with a
 * substring-slicing fallback. Both alternatives are now
 * capturing, so the production walker (and the tests) can read
 * `groupValues[1].ifEmpty { groupValues[2] }` cleanly.
 */
internal val INLINE_LATEX_REGEX: Regex = Regex("""\$\$([^$\n]+?)\$\$|\$([^$\n]+?)\$""")


/**
 * Regex to detect inline LaTeX in the LaTeX-original \(…\) form
 * (used by many models — including the one the user tested — when
 * emitting math inside a Markdown paragraph). The single
 * INLINE_LATEX_REGEX above is kept narrow to $…$ / $$…$$ only
 * so it doesn't accidentally eat the \( and \) of a non-math
 * command.
 *
 * Body exclusions: the inner content is [^()\n]+? — no nested
 * parens, no newlines. That rejects \(\frac{(a)}{(b)}\)-style
 * nested-paren formulas; the LaTeX library does not need them for
 * the formulas the user actually sends, and accepting them would
 * require a balanced-paren matcher that the standard regex syntax
 * doesn't support. If the model ever emits a nested-paren inline
 * formula, the user will see the raw \(…\) text — a known and
 * documented limitation.
 *
 * Important: this regex is applied to the RAW input text in
 * preprocessParenLatexFormulas BEFORE CommonMark sees the
 * text. CommonMark's inline parser would otherwise treat
 * \( and \) as backslash-escape sequences and strip the
 * backslashes, after which the formula has bled into the
 * surrounding prose as plain (...) and the original intent
 * is unrecoverable. After pre-processing, the matched
 * \(…\) source is replaced by a PUA marker char from the
 * U+E100–U+E1FF range (see PAREN_LATEX_MARKER_RANGE_START)
 * which CommonMark passes through unchanged. The walker then
 * scans for those marker chars with INLINE_LATEX_PAREN_MARKER_REGEX
 * and looks up the formula in the side table.
 */
internal val INLINE_LATEX_PAREN_REGEX: Regex = Regex("""\\\(([^()\n]+?)\\\)""")


/**
 * Regex matching any single PUA char in the range reserved for
 * pre-processed paren-form LaTeX markers (U+E100–U+E1FF). The
 * walker uses this to locate the markers that
 * [preprocessParenLatexFormulas] planted in the text; the
 * formula text itself is fetched from the [RenderState]'s
 * side table using the matched char as the key.
 */
internal val INLINE_LATEX_PAREN_MARKER_REGEX: Regex = Regex("""[\uE100-\uE1FF]""")


/** Pure CommonMark → `AnnotatedString` walk. Theme values passed in by the caller. */
internal fun parseInlineMarkdown(
  plainText: String, fontSizeSp: Float, chipTint: Color, linkColor: Color, imageAltColor: Color,
  editorFontFamily: FontFamily = FontFamily.Default
): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return InlineMarkdownRenderResult(render = null, bailReason = null)

  val preprocessed: PreprocessedParenLatex =
    preprocessParenLatexFormulas(plainText)

  val document: Document = parseCommonmarkDocument(preprocessed.text)
    ?: return bailWithReason(
      plainText = plainText,
      reason = "CommonMark parse failed (see IDE log for details)"
    )
  val children: NodeChildren = NodeChildren.of(document)
  val topBlocks: List<Node> = buildList {
    if (children.first != null) add(children.first)
    addAll(children.rest)
  }
  if (topBlocks.isEmpty()) return bailWithReason("Empty paragraph (no blocks)", plainText)
  val nonParagraphTypes: String? = collectNonParagraphTypes(topBlocks)
  if (nonParagraphTypes != null) {
    return bailWithReason("Paragraph contains non-prose blocks: $nonParagraphTypes", plainText)
  }
  return buildInlineRender(
    chipTint = chipTint,
    linkColor = linkColor,
    topBlocks = topBlocks,
    plainText = plainText,
    fontSizeSp = fontSizeSp,
    imageAltColor = imageAltColor,
    editorFontFamily = editorFontFamily,
    parenLatexFormulas = preprocessed.formulaByMarker
  )
}


/**
 * Result of preprocessParenLatexFormulas:
 *   text — the input with each \(…\) span replaced by a single PUA marker char.
 *          Safe to feed to the CommonMark parser.
 *   formulaByMarker — map from marker char (the literal PUA string) to the original formula text.
 *                     The walker uses this to recover the formula when it encounters a marker.
 */
internal data class PreprocessedParenLatex(
  val text: String,
  val formulaByMarker: Map<String, String>,
)


/**
 * Scan rawText for \(…\)-form LaTeX formulas and replace each match with a single PUA marker char
 * from the U+E100–U+E1FF range. Returns the rewritten text plus a side table mapping each marker
 * to the formula text it replaced.
 *
 * Why pre-processing is necessary: CommonMark's inline parser treats \( and \) as backslash-escape
 * sequences and strips the backslashes. By the time the AST walker sees the Text node, the formula
 * has bled into the surrounding prose as plain (...) and the original boundary is gone. Running this
 * pass BEFORE CommonMark preserves the boundary by encoding it in a single PUA char that CommonMark
 * doesn't touch.
 *
 * If a match exceeded the 256-marker capacity, overflow matches are left as-is (they fall through
 * to CommonMark and become plain (...) in the rendered output). Expected workload is well under 256
 * formulas per message.
 */
internal fun preprocessParenLatexFormulas(rawText: String): PreprocessedParenLatex {
  val matches: List<MatchResult> = INLINE_LATEX_PAREN_REGEX.findAll(rawText).toList()
  if (matches.isEmpty()) {
    return PreprocessedParenLatex(text = rawText, formulaByMarker = emptyMap())
  }
  val formulaByMarker: MutableMap<String, String> = LinkedHashMap(matches.size)
  val rewritten: StringBuilder = StringBuilder(rawText.length)
  var lastIndex = 0
  for ((matchIndex, match) in matches.withIndex()) {
    if (matchIndex >= PAREN_LATEX_MARKER_RANGE_SIZE) continue
    if (match.range.first < lastIndex) continue

    rewritten.append(rawText, lastIndex, match.range.first)
    val markerCode: Int = PAREN_LATEX_MARKER_RANGE_START + matchIndex
    val marker = String(Character.toChars(markerCode))
    formulaByMarker[marker] = match.groupValues[1]
    rewritten.append(marker)
    lastIndex = match.range.last + 1
  }
  rewritten.append(rawText, lastIndex, rawText.length)
  return PreprocessedParenLatex(text = rewritten.toString(), formulaByMarker = formulaByMarker)
}


private fun parseCommonmarkDocument(plainText: String): Document? {
  @Suppress("TooGenericExceptionCaught")
  return try {
    commonmarkParser.parse(plainText) as Document
  } catch (parseException: Exception) {
    val preview: String = plainText.take(PARSE_FAILURE_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.warn("Inline markdown parse failed for text: $preview", parseException)
    null
  }
}


/**
 * Walk a parent node's inline children directly into an
 * [InlineMarkdownRenderResult]. Companion to [parseInlineMarkdown] for
 * blocks where the serialize-then-re-parse round-trip is lossy
 * (see [rememberInlineMarkdownRenderFromNode] for the rationale).
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal fun parseInlineNodes(
  parentNode: Node, fontSizeSp: Float, linkColor: Color, imageAltColor: Color,
  editorFontFamily: FontFamily = FontFamily.Default
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      fontSizeSp = fontSizeSp,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily,
    )
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      renderInlineChildren(parentNode, builder, renderState)
    }
    InlineMarkdownRenderResult(
      render = InlineMarkdownRender(
        paragraphCount = 1,
        annotated = annotatedString,
        urlAnnotations = renderState.urlAnnotations.toList(),
        inlineContent = renderState.inlineContent.toMap()
      ),
      bailReason = null,
    )
  } catch (exception: Exception) {
    val reason = "AST walker failed: ${exception.javaClass.simpleName}: ${exception.message}"
    log.warn("Inline markdown AST walk failed for node: ${parentNode.javaClass.simpleName}", exception)
    InlineMarkdownRenderResult(render = null, bailReason = reason)
  }
}


/** Sorted, distinct, "+"-joined simple class names of every non-`Paragraph` block, or `null`. */
private fun collectNonParagraphTypes(topBlocks: List<Node>): String? {
  val blockTypeNames: List<String> = topBlocks.asSequence()
    .filter { it !is Paragraph }
    .map { it.javaClass.simpleName }
    .distinct()
    .sorted()
    .toList()
  return if (blockTypeNames.isEmpty()) null else blockTypeNames.joinToString(separator = " + ")
}


private fun bailWithReason(reason: String, plainText: String): InlineMarkdownRenderResult {
  val preview: String = plainText.take(BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
  log.warn("Inline markdown bailed: $reason (text=$preview)")
  return InlineMarkdownRenderResult(render = null, bailReason = reason)
}


/** Walk a paragraph-only AST and build the [InlineMarkdownRender]. Catches walker exceptions as bail. */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
private fun buildInlineRender(
  plainText: String, topBlocks: List<Node>, fontSizeSp: Float,
  chipTint: Color, linkColor: Color, imageAltColor: Color,
  editorFontFamily: FontFamily = FontFamily.Default,
  parenLatexFormulas: Map<String, String> = emptyMap()
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      linkColor = linkColor,
      fontSizeSp = fontSizeSp,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily,
      parenLatexFormulas = parenLatexFormulas,
    )
    val preview: String = plainText.take(BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.debug("InlineMarkdown: parse start — text.length=${plainText.length}, fontSizeSp=$fontSizeSp, chipTint=$chipTint, text=$preview")
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      topBlocks.forEachIndexed { blockIndex, blockNode ->
        if (blockIndex > 0) builder.append("\n\n")
        renderInlineChildren(blockNode as Paragraph, builder, renderState)
      }
    }
    log.debug("InlineMarkdown: parse done — chipCounter=${renderState.chipCounter}, inlineContent.keys=${renderState.inlineContent.keys}")
    InlineMarkdownRenderResult(
      render = InlineMarkdownRender(
        paragraphCount = topBlocks.size,
        annotated = annotatedString,
        urlAnnotations = renderState.urlAnnotations.toList(),
        inlineContent = renderState.inlineContent.toMap()
      ),
      bailReason = null,
    )
  } catch (exception: Exception) {
    val reason = "AST walker failed: ${exception.javaClass.simpleName}: ${exception.message}"
    val preview: String = plainText.take(WALK_FAILURE_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.warn("Inline markdown AST walk failed for text: $preview", exception)
    InlineMarkdownRenderResult(render = null, bailReason = reason)
  }
}


/** Mutable state threaded through the recursive walker. UI-thread-only by design (Compose `remember`). */
@Suppress("LongParameterList")
private class RenderState(
  val fontSizeSp: Float,
  val linkColor: Color,
  val imageAltColor: Color,
  val editorFontFamily: FontFamily = FontFamily.Default,
  var chipCounter: Int = 0,
  var imageAltCounter: Int = 0,
  var footnoteCounter: Int = 0,
  var latexCounter: Int = 0,
  var currentStyle: SpanStyle = SpanStyle(),
  val urlAnnotations: MutableList<UrlAnnotation> = mutableListOf(),
  val inlineContent: MutableMap<String, InlineTextContent> = mutableMapOf(),
  /**
   * Side table produced by [preprocessParenLatexFormulas]: maps
   * each pre-processed PUA marker char to the formula text it
   * replaced. The walker reads this table when it encounters a
   * marker (a PUA char in the U+E100–U+E1FF range) inside a Text
   * node literal. Empty for inputs that have no \(…\) matches.
   */
  val parenLatexFormulas: Map<String, String> = emptyMap(),
) {
  // Snapshot + restore helper for the recursive walker.
  fun <T> withStyle(replacementStyle: SpanStyle, block: () -> T): T {
    val savedStyle: SpanStyle = currentStyle
    currentStyle = replacementStyle
    return try {
      block()
    } finally {
      currentStyle = savedStyle
    }
  }

  /** Consume a fresh chip placeholder + register the chip in `inlineContent`. */
  fun allocateChip(codeText: String) {
    val placeholderKey: String = makePlaceholder(chipCounter)
    chipCounter += 1
    val chipWidth: Float = fontSizeSp * cjkAwareWidthRatio(codeText) + INLINE_CODE_PLACEHOLDER_PADDING_SP
    val chipHeight: Float = fontSizeSp * PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + INLINE_CODE_PLACEHOLDER_PADDING_SP
    val placeholderShape = Placeholder(
      width = chipWidth.sp,
      height = chipHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center
    )
    inlineContent[placeholderKey] = InlineTextContent(placeholder = placeholderShape) {
      InlineCodeChip(text = codeText, fontSizeSp = fontSizeSp)
    }
  }

  /**
   * Consume a fresh image-alt placeholder + register the `GradumIcons.Image`
   * icon in `inlineContent`. The placeholder is a PUA glyph that lives
   * at the start of the alt-text range; the caller appends it
   * followed by a space and the alt text, and the icon paints
   * inline via the surrounding `Text(annotated, inlineContent = ...)`.
   * The base codepoint [IMAGE_ALT_PLACEHOLDER_BASE] is distinct
   * from [INLINE_CODE_PLACEHOLDER] so adjacent image + code
   * spans never collide in the placeholder table.
   */
  fun allocateImageAlt(): String {
    val placeholder: String = IMAGE_ALT_PLACEHOLDER_BASE.toString().repeat(imageAltCounter + 1)
    imageAltCounter += 1
    val iconSize: Float = fontSizeSp * IMAGE_ALT_ICON_EM_SCALE
    val placeholderShape = Placeholder(
      width = iconSize.sp,
      height = iconSize.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center
    )
    inlineContent[placeholder] = InlineTextContent(placeholder = placeholderShape) {
      org.jetbrains.jewel.ui.component.Icon(
        contentDescription = null,
        modifier = Modifier.size(iconSize.dp),
        key = gradum.idea.icons.GradumIcons.Image
      )
    }
    return placeholder
  }

  /**
   * Consume a fresh footnote placeholder + register [FootnoteMark] in
   * `inlineContent`.  The placeholder is a PUA glyph; the caller appends it
   * to the [AnnotatedString] so the footnote composable paints inline via
   * the surrounding `Text(annotated, inlineContent = ...)`.
   */
  fun allocateFootnote(footnoteText: String): String {
    val placeholderKey: String = FOOTNOTE_PLACEHOLDER_BASE.toString().repeat(footnoteCounter + 1)
    footnoteCounter += 1
    val chipWidth: Float = fontSizeSp * cjkAwareWidthRatio(footnoteText) + INLINE_CODE_PLACEHOLDER_PADDING_SP
    val chipHeight: Float = fontSizeSp * PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + INLINE_CODE_PLACEHOLDER_PADDING_SP
    val placeholderShape = Placeholder(
      width = chipWidth.sp,
      height = chipHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
    )
    inlineContent[placeholderKey] = InlineTextContent(placeholder = placeholderShape) {
      FootnoteMark(text = footnoteText, fontSizeSp = fontSizeSp)
    }
    return placeholderKey
  }

  /**
   * Consume a fresh inline LaTeX placeholder + register a
   * [RenderInlineLatex] composable in `inlineContent`. The
   * placeholder is a PUA glyph whose width is measured against
   * the formula's bounding box (estimated by `cjkAwareWidthRatio`
   * for now — a real measurement would require a synchronous
   * LaTeX pre-parse, which the library does inside its
   * `Latex(...)` composable; we use a per-em estimate to
   * reserve enough horizontal space so the surrounding text
   * doesn't reflow when the math is painted). The caller
   * appends the placeholder to the [AnnotatedString] so the
   * `Text(annotated, inlineContent = ...)` will paint the
   * formula inline at that position.
   */
  fun allocateLatex(formulaText: String): String {
    val placeholderKey: String = LATEX_PLACEHOLDER_BASE.toString().repeat(latexCounter + 1)
    latexCounter += 1
    /**
     * Width: use cjkAwareWidthRatio estimate with extra padding.
     * The 2sp padding gives room for wide math glyphs (√, fractions with long numerators, etc.)
     * that can exceed the per-character estimate.
     */
    val placeholderWidth: Float = fontSizeSp * cjkAwareWidthRatio(formulaText) + INLINE_LATEX_PLACEHOLDER_PADDING_SP

    /**
     * Height: use INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER (2.5)
     * instead of PLACEHOLDER_LINE_HEIGHT_MULTIPLIER (1.0) used for chips/footnotes.
     *
     * Chips/footnotes contain single-line text; LaTeX formulas like \frac{a}{b}
     * are ~2× font height, nested fractions or radicals are 2.5–3×.
     * See the constant's comment for rationale and the "tall formulas overlapping text below" symptom this fixes.
     */
    val placeholderHeight: Float = fontSizeSp * INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + INLINE_LATEX_PLACEHOLDER_PADDING_SP

    /**
     * Vertical alignment: PlaceholderVerticalAlign.TextCenter (not Center).
     *
     * The huarangmeng library draws formulas with the mathematical baseline at roughly the Canvas's vertical center.
     * TextCenter aligns the placeholder with the text's x-height line, which is the standard math baseline in LaTeX.
     *
     * Center (line-centered) puts the formula center at the line's vertical midpoint — well above the x-height,
     * causing formulas to appear to float above the text baseline ("formulas appear too high relative to text baseline").
     *
     * AboveBaseline would put the placeholder's bottom on the text baseline, making the formula sit like subscript
     * text — the math axis would still be above the baseline, so it reads as elevated too.
     *
     * TextCenter is the only option that places the math axis at the x-height, matching LaTeX's \textstyle.
     */
    val placeholderShape = Placeholder(
      width = placeholderWidth.sp,
      height = placeholderHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
    )
    inlineContent[placeholderKey] = InlineTextContent(placeholder = placeholderShape) {
      RenderInlineLatex(
        formula = formulaText,
        fontSizeSp = fontSizeSp,
        fontFamily = editorFontFamily
      )
    }
    return placeholderKey
  }
}


/** Per-character width sum: [MONOSPACE_CJK_RATIO] for CJK, [MONOSPACE_LATIN_RATIO] for everything else. */
internal fun cjkAwareWidthRatio(codeText: String): Float {
  if (codeText.isEmpty()) return 0f
  var totalRatio = 0f
  for (char in codeText) totalRatio += if (isCjkChar(char)) MONOSPACE_CJK_RATIO else MONOSPACE_LATIN_RATIO
  return totalRatio
}

private fun renderInlineChildren(
  parentNode: Node,
  annotatedStringBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val children: NodeChildren = NodeChildren.of(parentNode)
  val firstChild: Node? = children.first
  if (firstChild != null) renderInlineNode(firstChild, annotatedStringBuilder, renderState)
  for (child in children.rest) renderInlineNode(child, annotatedStringBuilder, renderState)
}


/** Dispatch a single inline node. Unknown / extension nodes recurse into children. */
private fun renderInlineNode(
  inlineNode: Node,
  annotatedStringBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  when (inlineNode) {
    is Text -> renderTextInline(inlineNode, renderState, annotatedStringBuilder)
    is Emphasis -> renderEmphasisInline(inlineNode, renderState, annotatedStringBuilder)
    is StrongEmphasis -> renderStrongEmphasisInline(inlineNode, annotatedStringBuilder, renderState)
    is Code -> renderCodeInline(inlineNode, annotatedStringBuilder, renderState)
    is Link -> renderLinkInline(inlineNode, renderState, annotatedStringBuilder)
    is Image -> renderImageInline(inlineNode, renderState, annotatedStringBuilder)
    is SoftLineBreak -> annotatedStringBuilder.withStyle(renderState.currentStyle) { append(' ') }
    is HardLineBreak -> annotatedStringBuilder.withStyle(renderState.currentStyle) { append('\n') }
    is Strikethrough -> renderStrikethroughInline(renderState, inlineNode, annotatedStringBuilder)
    else -> {
      if (inlineNode.firstChild != null) renderInlineChildren(inlineNode, annotatedStringBuilder, renderState)
    }
  }
}


private fun renderTextInline(
  textNode: Text, renderState: RenderState, annotatedStringBuilder: AnnotatedString.Builder
) {
  val literal: String = textNode.literal.orEmpty()
  if (literal.isEmpty()) return

  val footnoteMatches: List<MatchResult> = INLINE_FOOTNOTE_REGEX.findAll(literal).toList()
  val latexMatches: List<MatchResult> = INLINE_LATEX_REGEX.findAll(literal).toList()

  val parenLatexMatches: List<MatchResult> = if (renderState.parenLatexFormulas.isEmpty()) {
    emptyList()
  } else {
    INLINE_LATEX_PAREN_MARKER_REGEX.findAll(literal)
      .filter { renderState.parenLatexFormulas.containsKey(it.value) }
      .toList()
  }
  if (footnoteMatches.isEmpty() && latexMatches.isEmpty() && parenLatexMatches.isEmpty()) {
    if (renderState.currentStyle == SpanStyle()) annotatedStringBuilder.append(literal)
    else annotatedStringBuilder.withStyle(renderState.currentStyle) { append(literal) }
    return
  }

  val allMatches: List<MatchResult> = (footnoteMatches + latexMatches + parenLatexMatches)
    .sortedBy { it.range.first }

  var lastIndex = 0
  for (match in allMatches) {
    if (match.range.first < lastIndex) {
      continue
    }
    val preText: String = literal.substring(lastIndex, match.range.first)
    if (preText.isNotEmpty()) {
      if (renderState.currentStyle == SpanStyle()) annotatedStringBuilder.append(preText)
      else annotatedStringBuilder.withStyle(renderState.currentStyle) { append(preText) }
    }

    if (match in footnoteMatches) {
      val footnoteText: String = match.groupValues[2]
        .ifEmpty { match.groupValues[3] }
        .ifEmpty { match.groupValues[4] }
      if (footnoteText.isNotEmpty()) {
        val placeholderKey: String = renderState.allocateFootnote(footnoteText)
        annotatedStringBuilder.pushStringAnnotation(tag = FOOTNOTE_TEXT_TAG, annotation = footnoteText)
        annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
        annotatedStringBuilder.pushStyle(SpanStyle())
        annotatedStringBuilder.append(placeholderKey)
        annotatedStringBuilder.pop()
        annotatedStringBuilder.pop()
        annotatedStringBuilder.pop()
      }
    } else if (match in parenLatexMatches) {
      val formulaText: String = renderState.parenLatexFormulas[match.value].orEmpty()
      if (formulaText.isNotEmpty()) {
        val placeholderKey: String = renderState.allocateLatex(formulaText)
        annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
        annotatedStringBuilder.pushStyle(SpanStyle())
        annotatedStringBuilder.append(placeholderKey)
        annotatedStringBuilder.pop()
        annotatedStringBuilder.pop()
      }
    } else {
      val formulaText: String = match.groupValues[1].ifEmpty { match.groupValues[2] }
      if (formulaText.isNotEmpty()) {
        val placeholderKey: String = renderState.allocateLatex(formulaText)
        annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
        annotatedStringBuilder.pushStyle(SpanStyle())
        annotatedStringBuilder.append(placeholderKey)
        annotatedStringBuilder.pop()
        annotatedStringBuilder.pop()
      }
    }

    lastIndex = match.range.last + 1
  }

  val postText: String = literal.substring(lastIndex)
  if (postText.isNotEmpty()) {
    if (renderState.currentStyle == SpanStyle()) {
      annotatedStringBuilder.append(postText)
    } else {
      annotatedStringBuilder.withStyle(renderState.currentStyle) { append(postText) }
    }
  }
}

private fun renderEmphasisInline(
  emphasisNode: Emphasis,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder,
) {
  // Render emphasis as normal text (no italic style, no markers)
  renderState.withStyle(renderState.currentStyle) { renderInlineChildren(emphasisNode, annotatedStringBuilder, renderState) }
}

private fun renderStrongEmphasisInline(
  strongEmphasisNode: StrongEmphasis,
  annotatedStringBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val boldStyle: SpanStyle = renderState.currentStyle.copy(fontWeight = FontWeight.Bold)
  renderState.withStyle(boldStyle) { renderInlineChildren(strongEmphasisNode, annotatedStringBuilder, renderState) }
}

private fun renderCodeInline(
  codeNode: Code,
  annotatedStringBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val codeText: String = codeNode.literal.orEmpty()
  if (codeText.isEmpty()) return
  val placeholderKey: String = makePlaceholder(renderState.chipCounter)
  val placeholderStart: Int = annotatedStringBuilder.length

  annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CODE_TEXT_TAG, annotation = codeText)
  annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
  annotatedStringBuilder.pushStyle(SpanStyle())
  annotatedStringBuilder.append(placeholderKey)
  annotatedStringBuilder.pop()
  annotatedStringBuilder.pop()
  annotatedStringBuilder.pop()
  val placeholderEnd: Int = annotatedStringBuilder.length
  check(placeholderEnd - placeholderStart == placeholderKey.length) {
    "Inline-code placeholder length changed under inline-content push; " +
      "expected ${placeholderKey.length} chars, got ${placeholderEnd - placeholderStart}"
  }
  renderState.allocateChip(codeText)
}

private fun renderLinkInline(
  linkNode: Link,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val linkStyle: SpanStyle = renderState.currentStyle.copy(
    color = renderState.linkColor,
    textDecoration = combineDecoration(
      renderState.currentStyle.textDecoration ?: TextDecoration.None,
      TextDecoration.Underline
    ),
  )
  val linkUrl: String = linkNode.destination.orEmpty()
  val linkTextStart: Int = annotatedStringBuilder.length
  annotatedStringBuilder.pushStringAnnotation(tag = INLINE_URL_TAG, annotation = linkUrl)
  renderState.withStyle(linkStyle) { renderInlineChildren(linkNode, annotatedStringBuilder, renderState) }
  annotatedStringBuilder.pop()
  val linkTextEnd: Int = annotatedStringBuilder.length
  if (linkUrl.isNotEmpty() && linkTextEnd > linkTextStart) {
    renderState.urlAnnotations += UrlAnnotation(start = linkTextStart, end = linkTextEnd, url = linkUrl)
  }
}

private fun renderImageInline(
  imageNode: Image,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val imageAltStyle: SpanStyle = renderState.currentStyle.copy(
    fontStyle = FontStyle.Italic,
    fontFamily = renderState.editorFontFamily,
    color = renderState.imageAltColor,
    textDecoration = renderState.currentStyle.textDecoration ?: TextDecoration.None,
  )
  val iconPlaceholder: String = renderState.allocateImageAlt()
  renderState.withStyle(imageAltStyle) {
    annotatedStringBuilder.append(iconPlaceholder)
    annotatedStringBuilder.append(' ')
    renderInlineChildren(imageNode, annotatedStringBuilder, renderState)
  }
}

private fun renderStrikethroughInline(
  renderState: RenderState,
  strikethroughNode: Strikethrough,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val strikethroughStyle: SpanStyle = renderState.currentStyle.copy(
    textDecoration = combineDecoration(TextDecoration.LineThrough, (renderState.currentStyle.textDecoration ?: TextDecoration.None))
  )
  renderState.withStyle(strikethroughStyle) { renderInlineChildren(strikethroughNode, annotatedStringBuilder, renderState) }
}


/** Compose two [TextDecoration] values: underline + line-through can both paint. Other: new wins. */
private fun combineDecoration(
  added: TextDecoration, existing: TextDecoration
): TextDecoration {
  val hasUnderline: Boolean = existing.contains(TextDecoration.Underline) || added.contains(TextDecoration.Underline)
  val hasLineThrough: Boolean = existing.contains(TextDecoration.LineThrough) || added.contains(TextDecoration.LineThrough)
  return when {
    hasUnderline && hasLineThrough -> TextDecoration.Underline + TextDecoration.LineThrough
    hasUnderline -> TextDecoration.Underline
    hasLineThrough -> TextDecoration.LineThrough
    else -> TextDecoration.None
  }
}


/** Build a unique PUA placeholder substring for the N-th code span — adjacent chips never collide. */
private fun makePlaceholder(chipIndex: Int): String =
  INLINE_CODE_PLACEHOLDER.toString().repeat(chipIndex + 1)


/**
 * Slice the walker output at link boundaries so the prose path can
 * stay on `Text(annotated, inlineContent = ...)` (for code chips) and
 * the link path can use [org.jetbrains.jewel.ui.component.ExternalLink]
 * (a standalone `Composable` with the external-link icon). Without
 * this split, links would have to be inlined into the `AnnotatedString`
 * — which can't host a `Composable` icon.
 *
 * [inlineContent] is shared across every emitted [InlineSegment.TextSegment]
 * because PUA placeholders in the sub-`AnnotatedString` ranges are
 * looked up against the same map by `Text(..., inlineContent = ...)`.
 *
 * The link text is de-styled (Punctuation / SpanStyles stripped) so
 * the `ExternalLink` picks up its own theme styling. PUA placeholders
 * inside the link range (e.g. `[\`code\`](url)`) are dropped — the
 * chip rendering lives in the prose path, not the link path.
 */
internal fun splitIntoInlineSegments(
  annotated: AnnotatedString, urlAnnotations: List<UrlAnnotation>, inlineContent: Map<String, InlineTextContent>
): List<InlineSegment> {
  if (urlAnnotations.isEmpty()) {
    return listOf(InlineSegment.TextSegment(annotated, inlineContent))
  }
  val sortedUrls: List<UrlAnnotation> = urlAnnotations.sortedBy { it.start }
  val segments: MutableList<InlineSegment> = mutableListOf()
  var cursor = 0
  for ((start, end, url) in sortedUrls) {
    if (start > cursor) {
      segments += InlineSegment.TextSegment(
        annotated = annotated.subSequence(cursor, start),
        inlineContent = inlineContent,
      )
    }
    val linkTextContent: CharSequence = annotated.subSequence(start, end)
    val linkText: String = stripInlineLinkText(linkTextContent)
    segments += InlineSegment.LinkSegment(text = linkText, url = url)
    cursor = end
  }
  if (cursor < annotated.length) {
    segments += InlineSegment.TextSegment(
      annotated = annotated.subSequence(cursor, annotated.length),
      inlineContent = inlineContent,
    )
  }
  return segments
}

/**
 * Drop PUA placeholders from a link's text range — code chips,
 * image icons, footnote marks, and inline LaTeX that happen to
 * be inside a link's label are all rendered through their own
 * `inlineContent` entries, not the link path. `AnnotatedString.toString()`
 * already preserves plain text content; we just need to filter
 * out the PUA chars. The link range was wrapped in a
 * `pushStringAnnotation` for the link's URL, but that's metadata,
 * not visible text.
 */
private fun stripInlineLinkText(range: CharSequence): String {
  if (range.none { it.isInlinePlaceholderPua() }) return range.toString()
  val output: StringBuilder = StringBuilder(range.length)
  for (char in range) {
    if (!char.isInlinePlaceholderPua()) output.append(char)
  }
  return output.toString()
}

/** One of the PUA placeholders reserved for an inline-only chip / icon / formula. */
private fun Char.isInlinePlaceholderPua(): Boolean = when (this) {
  INLINE_CODE_PLACEHOLDER,
  IMAGE_ALT_PLACEHOLDER_BASE,
  FOOTNOTE_PLACEHOLDER_BASE,
  LATEX_PLACEHOLDER_BASE -> true

  else -> this.code in PAREN_LATEX_MARKER_RANGE_START..PAREN_LATEX_MARKER_RANGE_END
}


/**
 * The actual chip composable. Uses `JewelTheme.linkStyle`'s `content` color for a
 * vivid tint across themes; the chip's internal `Text` sets `lineHeight = fontSizeSp.sp`
 * (1.0x) so the editor's 1.5x line height doesn't clip the glyphs.
 *
 * Implementation note: we use `Modifier.background(color, shape)` rather than
 * `Modifier.clip(shape).background(color)`. The former paints the background
 * in the rounded shape but does NOT clip the children — so descenders of
 * characters like `p`, `g`, `y` remain visible even if they briefly extend
 * past the bottom edge of the rounded shape. (The old `clip`+`background`
 * combination clipped both background and text, which is what produced the
 * "text slightly clipped at the bottom" the user reported.)
 */
@Composable
private fun InlineCodeChip(
  text: String, fontSizeSp: Float
) {
  val editorStyle: TextStyle = JewelTheme.editorTextStyle
  val badgeColor: Color = JewelTheme.linkStyle.colors.content
  val chipStyle = TextStyle(
    color = badgeColor,
    fontSize = fontSizeSp.sp,
    lineHeight = fontSizeSp.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = editorStyle.fontFamily
  )
  Box(
    modifier = Modifier
      .background(
        color = badgeColor.copy(alpha = INLINE_CODE_BACKGROUND_ALPHA),
        shape = RoundedCornerShape(inlineCodeCornerRadius)
      )
      .padding(
        vertical = inlineCodePaddingVertical,
        horizontal = inlineCodePaddingHorizontal,
      )
  ) {
    Text(
      text = text,
      maxLines = 1,
      softWrap = false,
      style = chipStyle,
      color = badgeColor
    )
  }
}


/**
 * Footnote marker composable. Renders as a chip with gray (info) background
 * and blue badge text color, matching the inline code chip's shape treatment.
 * Displays just the footnote number/text without any prefix.
 */
@Composable
private fun FootnoteMark(text: String, fontSizeSp: Float) {
  val badgeColor: Color = JewelTheme.linkStyle.colors.content
  val infoColor: Color = JewelTheme.globalColors.text.info
  val chipStyle = TextStyle(
    color = badgeColor,
    fontSize = fontSizeSp.sp,
    lineHeight = fontSizeSp.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = JewelTheme.editorTextStyle.fontFamily,
    textDecoration = TextDecoration.Underline
  )
  Box(
    modifier = Modifier
      .background(
        color = infoColor.copy(alpha = INLINE_CODE_BACKGROUND_ALPHA),
        shape = RoundedCornerShape(inlineCodeCornerRadius)
      )
      .padding(
        vertical = inlineCodePaddingVertical,
        horizontal = inlineCodePaddingHorizontal,
      )
  ) {
    Text(
      text = text,
      maxLines = 1,
      softWrap = false,
      style = chipStyle,
      color = badgeColor
    )
  }
}


/**
 * Chip tint from `JewelTheme.linkStyle` (solid `Color` — not the badge's transparent
 * `background`). The first resolution is logged to the IDE log for sanity check.
 */
@Composable
private fun resolveInlineCodeTint(): Color {
  val candidateTint: Color = JewelTheme.linkStyle.colors.content
  val resolvedTint: Color =
    if (candidateTint.alpha < MIN_OPAQUE_TINT_ALPHA) JewelTheme.contentColor else candidateTint
  log.debug("InlineMarkdown: chip tint resolved to $resolvedTint (alpha=${resolvedTint.alpha})")
  return resolvedTint
}

/**
 * Color for the alt text of an inline `![alt](url)` image. The chat
 * doesn't render the binary, so the alt text is a placeholder /
 * description, not a link. Using the body text's color at 60% alpha
 * reads as "secondary / decorative" — visually subordinate to the
 * link blue and the body black, but still readable in both light
 * and dark themes. (The previous v1.5 path made the alt text use
 * the link color, which confused the user when the message mixed
 * images and links — see the feedback case
 * `![alt](imageUrl) <https://example.com> <email@example.com>`.)
 */
@Composable
private fun resolveImageAltColor(): Color {
  val base: Color = JewelTheme.contentColor
  return base.copy(alpha = 0.6f)
}

/** Editor font size in sp; falls back when the theme's `fontSize` is unspecified. */
@Composable
private fun resolveEditorFontSizeSp(): Float {
  val editorStyle: TextStyle = JewelTheme.editorTextStyle
  val fontSize: androidx.compose.ui.unit.TextUnit = editorStyle.fontSize
  return when {
    fontSize.isSp -> fontSize.value
    fontSize.isEm -> fontSize.value * FALLBACK_EM_FONT_SIZE_SP
    else -> FALLBACK_FONT_SIZE_SP
  }
}
