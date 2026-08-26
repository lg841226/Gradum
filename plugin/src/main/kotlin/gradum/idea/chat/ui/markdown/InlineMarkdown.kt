/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InlineMarkdown.kt  2026-08-26 12:51:12 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.LatexDimensions
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.PluginConfig
import gradum.idea.utils.GradumIcons
import kotlinx.coroutines.delay
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.linkStyle
import kotlin.time.Duration.Companion.milliseconds

private val log: Logger = Logger.getInstance("gradum.idea.chat.ui.markdown.InlineMarkdown")
private const val INLINE_URL_TAG: String = "INLINE_URL"
internal const val INLINE_CODE_SPAN_TAG: String = "INLINE_CODE_SPAN"
internal const val INLINE_CONTENT_TAG: String = "androidx.compose.foundation.text.inlineContent"
internal const val FOOTNOTE_TEXT_TAG: String = "FOOTNOTE_TEXT"
private const val LATEX_PLACEHOLDER_BASE: Char = '\uE003'
private const val FOOTNOTE_PLACEHOLDER_BASE: Char = '\uE002'
private const val IMAGE_ALT_PLACEHOLDER_BASE: Char = '\uE001'
private const val PAREN_LATEX_MARKER_RANGE_END: Int = 0xE1FF
private const val DOLLAR_LATEX_MARKER_RANGE_END: Int = 0xE2FF
private const val PAREN_LATEX_MARKER_RANGE_START: Int = 0xE100
private const val DOLLAR_LATEX_MARKER_RANGE_START: Int = 0xE200
private const val DOLLAR_LATEX_MARKER_RANGE_SIZE: Int = DOLLAR_LATEX_MARKER_RANGE_END - DOLLAR_LATEX_MARKER_RANGE_START + 1
private const val PAREN_LATEX_MARKER_RANGE_SIZE: Int = PAREN_LATEX_MARKER_RANGE_END - PAREN_LATEX_MARKER_RANGE_START + 1
private const val BAIL_REASON_LOG_PREVIEW_CHARS: Int = PluginConfig.BAIL_REASON_LOG_PREVIEW_CHARS
private const val PARSE_FAILURE_LOG_PREVIEW_CHARS: Int = PluginConfig.PARSE_FAILURE_LOG_PREVIEW_CHARS
private const val WALK_FAILURE_LOG_PREVIEW_CHARS: Int = PluginConfig.WALK_FAILURE_LOG_PREVIEW_CHARS

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
 * Matches bare http/https/ftp URLs in plain text. The last character
 * excludes common trailing punctuation so `https://example.com.` captures
 * only the URL without the period.
 */
private val bareUrlRegex: Regex = Regex(
  pattern = """https?://[^\s<>"'()\[\]]+[^\s<>"'().,;:!?\]]""",
  option = RegexOption.IGNORE_CASE
)

/** One renderable inline representation: PUA-placeholder AnnotatedString + chip InlineTextContent map. */
data class InlineMarkdownRender(
  val paragraphCount: Int,
  val annotated: AnnotatedString,
  val urlAnnotations: List<UrlAnnotation>,
  val inlineContent: Map<String, InlineTextContent>
)

/** A (text-offset range, URL) pair attached to a link's text range. */
data class UrlAnnotation(val start: Int, val end: Int, val url: String)

/**
 * One chunk of a rendered inline line — either prose (Text with inlineContent for chips)
 * or a clickable link (ExternalLink). Split at link boundaries by splitIntoInlineSegments.
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
fun rememberInlineMarkdownRender(plainText: String, thinkingMode: Boolean = false): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return InlineMarkdownRenderResult(render = null, bailReason = null)
  val imageAltColor: Color = resolveImageAltColor()
  val fontSizeSp: Float = resolveEditorFontSizeSp()
  val codeColor: Color = JewelTheme.globalColors.text.info
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val latexMeasurer: LatexMeasurerState = rememberLatexMeasurer()
  val density: Density = androidx.compose.ui.platform.LocalDensity.current
  val editorFontFamily: FontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default

  return remember(
    plainText, linkColor, imageAltColor, fontSizeSp, editorFontFamily, thinkingMode, codeColor
  ) {
    parseInlineMarkdown(
      density = density,
      codeColor = codeColor,
      linkColor = linkColor,
      plainText = plainText,
      fontSizeSp = fontSizeSp,
      thinkingMode = thinkingMode,
      imageAltColor = imageAltColor,
      latexMeasurer = latexMeasurer,
      editorFontFamily = editorFontFamily
    )
  }
}


/**
 * Walk a parent node's inline children directly. Used for blocks where
 * serialize-then-re-parse round-trip is lossy (e.g. headings starting with list markers).
 */
@Composable
fun rememberInlineMarkdownRenderFromNode(parentNode: Node): InlineMarkdownRenderResult {
  val imageAltColor: Color = resolveImageAltColor()
  val fontSizeSp: Float = resolveEditorFontSizeSp()
  val codeColor: Color = JewelTheme.globalColors.text.info
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val editorFontFamily: FontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
  return remember(parentNode, linkColor, imageAltColor, fontSizeSp, editorFontFamily, codeColor) {
    parseInlineNodes(
      codeColor = codeColor,
      linkColor = linkColor,
      parentNode = parentNode,
      fontSizeSp = fontSizeSp,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily
    )
  }
}

/** Regex for inline footnotes: `^[text]`, `[^ref]`, or `[1]`. */
internal val INLINE_FOOTNOTE_REGEX: Regex = Regex(pattern = """(\^\[([^]]*)]|\[\^([^]]*)]|\[(\d+)])""")

/**
 * Regex for inline LaTeX: $$…$$ (must come first to avoid matching inner $ of $$x^2$$) and $…$.
 * Non-greedy, no newlines. Block-level $$…$$ on its own line is handled by LatexBlockExtension.
 */
internal val INLINE_LATEX_REGEX: Regex = Regex(pattern = """\$\$([^$\n]+?)\$\$|\$([^$\n]+?)\$""")

/** Regex for \(…\)-form LaTeX. Applied BEFORE CommonMark to preserve formulas (CommonMark strips backslashes). */
internal val INLINE_LATEX_PAREN_REGEX: Regex = Regex(pattern = """\\\(([^()\n]+?)\\\)""")

/**
 * Matches "currency-like" dollar-span content: digits with comma/dot
 * thousand/ decimal separators, optional spaces and a +/- sign. `$5.99`,
 * `$1,000`, `$ 50` are prices, not LaTeX — a real formula always carries
 * a letter, symbol, or operator. A pure-digit span is never math.
 */
internal val CURRENCY_LIKE_CONTENT_REGEX: Regex = Regex(pattern = """[\d,.\s+-]*""")

/**
 * Ranges of backtick-delimited code spans in [rawText], used to stop
 * dollar-Latex pre-processing from replacing `$x^2$` inside `` `$x^2$` ``.
 * Without this, the PUA marker lands in the code chip's literal text and
 * renders as a tofu box / broken chip. Tracks an odd/even backtick
 * toggle; CommonMark treats a span as code when the backticks pair up.
 */
internal fun dollarLatexCodeSpanRanges(rawText: String): List<IntRange> {
  val codeSpanRanges: MutableList<IntRange> = mutableListOf()
  var rangeStart: Int? = null
  for (currentIndex in rawText.indices) {
    if (rawText[currentIndex] != '`') continue
    if (rangeStart == null) {
      rangeStart = currentIndex
    } else {
      codeSpanRanges.add(rangeStart..currentIndex)
      rangeStart = null
    }
  }
  return codeSpanRanges
}

/** Regex matching PUA chars in the pre-processed LaTeX marker range (U+E100–U+E2FF). */
internal val INLINE_LATEX_PAREN_MARKER_REGEX: Regex = Regex(pattern = """[\uE100-\uE2FF]""")

/** Pure CommonMark → `AnnotatedString` walk. Theme values passed in by the caller. */
internal fun parseInlineMarkdown(
  linkColor: Color,
  plainText: String,
  fontSizeSp: Float,
  imageAltColor: Color,
  density: Density? = null,
  thinkingMode: Boolean = false,
  codeColor: Color = Color.Unspecified,
  latexMeasurer: LatexMeasurerState? = null,
  editorFontFamily: FontFamily = FontFamily.Default
): InlineMarkdownRenderResult {
  if (plainText.isBlank())
    return InlineMarkdownRenderResult(render = null, bailReason = null)

  val parenPreprocessed: PreprocessedParenLatex =
    preprocessParenLatexFormulas(rawText = plainText)

  val dollarPreprocessed: PreprocessedParenLatex =
    preprocessDollarLatexFormulas(rawText = parenPreprocessed.text)

  val mergedFormulas: Map<String, String> = parenPreprocessed.formulaByMarker + dollarPreprocessed.formulaByMarker

  val document: Document = parseCommonmarkDocument(plainText = dollarPreprocessed.text)
    ?: return bailWithReason(
      plainText = plainText,
      reason = "CommonMark parse failed (see IDE log for details)"
    )
  val children: NodeChildren = NodeChildren.of(parent = document)
  val topBlocks: List<Node> = buildList {
    if (children.first != null) add(children.first)
    addAll(elements = children.rest)
  }
  if (topBlocks.isEmpty()) return bailWithReason("Empty paragraph (no blocks)", plainText)
  val nonParagraphTypes: String? = collectNonParagraphTypes(topBlocks)
  if (nonParagraphTypes != null)
    return bailWithReason("Paragraph contains non-prose blocks: $nonParagraphTypes", plainText)

  return buildInlineRender(
    config = InlineRenderConfig(
      density = density,
      linkColor = linkColor,
      codeColor = codeColor,
      fontSizeSp = fontSizeSp,
      thinkingMode = thinkingMode,
      latexMeasurer = latexMeasurer,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily,
      parenLatexFormulas = mergedFormulas
    ),
    plainText = plainText,
    topBlocks = topBlocks
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
  val text: String, val formulaByMarker: Map<String, String>
)

/**
 * Replace \(…\)-form LaTeX with PUA markers before CommonMark parsing.
 * CommonMark treats \( as backslash-escape, destroying the formula boundary.
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


/**
 * Replace $…$/$$…$$-form LaTeX with PUA markers before CommonMark parsing.
 * CommonMark treats _ as emphasis delimiter, splitting formulas like $(AB)_{ij}$.
 */
internal fun preprocessDollarLatexFormulas(rawText: String): PreprocessedParenLatex {
  val codeSpanRanges: List<IntRange> = dollarLatexCodeSpanRanges(rawText)
  val matches: List<MatchResult> = INLINE_LATEX_REGEX.findAll(rawText).toList()
  if (matches.isEmpty()) {
    return PreprocessedParenLatex(text = rawText, formulaByMarker = emptyMap())
  }

  val formulaByMarker: MutableMap<String, String> = LinkedHashMap(matches.size)
  val rewritten: StringBuilder = StringBuilder(rawText.length)
  var lastIndex = 0

  for ((matchIndex, match) in matches.withIndex()) {
    if (match.range.first < lastIndex ||
      !isInlineLatexCandidate(match, rawText) ||
      matchIndex >= DOLLAR_LATEX_MARKER_RANGE_SIZE ||
      codeSpanRanges.any { it.contains(match.range.first) }
    ) continue

    val formula = match.groupValues[1].ifEmpty { match.groupValues[2] }

    rewritten.run {
      val marker = String(chars = Character.toChars(DOLLAR_LATEX_MARKER_RANGE_START + matchIndex))
      append(rawText, lastIndex, match.range.first)
      formulaByMarker[marker] = formula
      append(marker)
    }

    lastIndex = match.range.last + 1
  }
  rewritten.append(rawText, lastIndex, rawText.length)
  return PreprocessedParenLatex(text = rewritten.toString(), formulaByMarker = formulaByMarker)
}

/**
 * True when a `$…$` [match] in [text] looks like inline LaTeX rather than
 * a currency amount or prose with stray dollar signs. Shared by
 * [preprocessDollarLatexFormulas] and the inline walker (which re-runs
 * [INLINE_LATEX_REGEX] on each text node) so both stay in agreement.
 *
 * Rejects:
 *  - whitespace immediately after the opening `$` or before the closing
 *    `$` (markdown-it rule — inline math never has a space there);
 *  - purely numeric content (`$5.99`, `$1,000`, `$ 50`) which is
 *    currency, not math.
 */
internal fun isInlineLatexCandidate(match: MatchResult, text: String): Boolean {
  val formula: String = match.groupValues[1].ifEmpty { match.groupValues[2] }
  val contentStart: Int = match.range.first + 1
  val contentEnd: Int = match.range.last - 1
  val hasSpaceAfterOpen: Boolean =
    contentStart < text.length && text[contentStart].isWhitespace()
  val hasSpaceBeforeClose: Boolean =
    contentEnd >= 0 && text[contentEnd].isWhitespace()
  return !(hasSpaceAfterOpen || hasSpaceBeforeClose) && !formula.matches(CURRENCY_LIKE_CONTENT_REGEX)
}

private fun parseCommonmarkDocument(plainText: String): Document? {
  @Suppress("TooGenericExceptionCaught")
  return try {
    commonmarkParser.parse(plainText) as Document
  } catch (parseException: Exception) {
    val preview: String =
      plainText.take(n = PARSE_FAILURE_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.warn("Inline markdown parse failed for text: $preview", parseException)
    null
  }
}

/** Walk a parent node's inline children directly into InlineMarkdownRenderResult. */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal fun parseInlineNodes(
  parentNode: Node,
  linkColor: Color,
  fontSizeSp: Float,
  imageAltColor: Color,
  density: Density? = null,
  codeColor: Color = Color.Unspecified,
  latexMeasurer: LatexMeasurerState? = null,
  editorFontFamily: FontFamily = FontFamily.Default
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      density = density,
      linkColor = linkColor,
      codeColor = codeColor,
      fontSizeSp = fontSizeSp,
      imageAltColor = imageAltColor,
      latexMeasurer = latexMeasurer,
      editorFontFamily = editorFontFamily
    )
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      renderInlineChildren(parentNode, renderState, annotatedStringBuilder = builder)
    }
    InlineMarkdownRenderResult(
      render = InlineMarkdownRender(
        paragraphCount = 1,
        annotated = annotatedString,
        inlineContent = renderState.inlineContent.toMap(),
        urlAnnotations = renderState.urlAnnotations.toList()
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
  val preview: String = plainText.take(n = BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
  log.warn("Inline markdown bailed: $reason (text=$preview)")
  return InlineMarkdownRenderResult(render = null, bailReason = reason)
}


/** Encapsulated rendering configuration for [buildInlineRender]. */
internal data class InlineRenderConfig(
  val linkColor: Color,
  val codeColor: Color,
  val fontSizeSp: Float,
  val imageAltColor: Color,
  val density: Density? = null,
  val thinkingMode: Boolean = false,
  val latexMeasurer: LatexMeasurerState? = null,
  val editorFontFamily: FontFamily = FontFamily.Default,
  val parenLatexFormulas: Map<String, String> = emptyMap()
)

/** Walk a paragraph-only AST and build the [InlineMarkdownRender]. Catches walker exceptions as bail. */
@Suppress("TooGenericExceptionCaught")
private fun buildInlineRender(
  plainText: String,
  topBlocks: List<Node>,
  config: InlineRenderConfig,
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      density = config.density,
      linkColor = config.linkColor,
      codeColor = config.codeColor,
      fontSizeSp = config.fontSizeSp,
      imageAltColor = config.imageAltColor,
      latexMeasurer = config.latexMeasurer,
      editorFontFamily = config.editorFontFamily,
      parenLatexFormulas = config.parenLatexFormulas
    )
    val preview: String = plainText.take(n = BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.debug("InlineMarkdown: parse start — text.length=${plainText.length}, fontSizeSp=${config.fontSizeSp}, text=$preview")
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      topBlocks.forEachIndexed { blockIndex, blockNode ->
        if (blockIndex > 0) builder.append("\n\n")
        renderInlineChildren(
          parentNode = blockNode as Paragraph, renderState, annotatedStringBuilder = builder
        )
      }
    }
    log.debug("InlineMarkdown: parse done — inlineContent.keys=${renderState.inlineContent.keys}")
    InlineMarkdownRenderResult(
      render = InlineMarkdownRender(
        annotated = annotatedString,
        paragraphCount = topBlocks.size,
        inlineContent = renderState.inlineContent.toMap(),
        urlAnnotations = renderState.urlAnnotations.toList()
      ),
      bailReason = null,
    )
  } catch (exception: Exception) {
    val reason = "AST walker failed: ${exception.javaClass.simpleName}: ${exception.message}"
    val preview: String = plainText.take(n = WALK_FAILURE_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.warn("Inline markdown AST walk failed for text: $preview", exception)
    InlineMarkdownRenderResult(render = null, bailReason = reason)
  }
}


/** Mutable scanState threaded through the recursive walker. UI-thread-only by design (Compose `remember`). */
@Suppress("LongParameterList")
private class RenderState(
  val linkColor: Color,
  val codeColor: Color,
  val fontSizeSp: Float,
  val imageAltColor: Color,
  val density: Density? = null,
  private val latexMeasurer: LatexMeasurerState? = null,
  val editorFontFamily: FontFamily = FontFamily.Default,
  val parenLatexFormulas: Map<String, String> = emptyMap()
) {
  // Mutable state mutated while walking the AST, kept out of the config
  // constructor so callers pass only immutable rendering settings.
  var currentStyle: SpanStyle = SpanStyle()
  private var latexCounter: Int = 0
  private var imageAltCounter: Int = 0
  private var footnoteCounter: Int = 0
  val urlAnnotations: MutableList<UrlAnnotation> = mutableListOf()
  val inlineContent: MutableMap<String, InlineTextContent> = mutableMapOf()
  private val latexMeasureCache = mutableMapOf<String, LatexDimensions?>()

  /** Snapshot + restore helper for the recursive walker. */
  fun <T> withStyle(replacementStyle: SpanStyle, block: () -> T): T {
    val savedStyle: SpanStyle = currentStyle
    currentStyle = replacementStyle
    return try {
      block()
    } finally {
      currentStyle = savedStyle
    }
  }

  /** Consume a fresh image-alt placeholder + register the GradumIcons.Image icon. */
  fun allocateImageAlt(): String {
    val placeholder: String = IMAGE_ALT_PLACEHOLDER_BASE.toString().repeat(n = imageAltCounter + 1)
    imageAltCounter += 1
    val iconSize: Float = fontSizeSp * MarkdownStyle.Image.ALT_ICON_EM_SCALE
    val placeholderShape = Placeholder(
      width = iconSize.sp,
      height = iconSize.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center
    )
    inlineContent[placeholder] = InlineTextContent(placeholder = placeholderShape) {
      Icon(
        key = GradumIcons.Image,
        contentDescription = null,
        modifier = Modifier.size(iconSize.dp)
      )
    }
    return placeholder
  }

  /** Consume a fresh footnote placeholder + register [FootnoteMark]. */
  fun allocateFootnote(footnoteText: String, isDefinition: Boolean = false): String {
    val placeholderKey: String = FOOTNOTE_PLACEHOLDER_BASE.toString().repeat(n = footnoteCounter + 1)
    footnoteCounter += 1
    val numberFontSizeSp: Float = fontSizeSp - 1f
    val chipWidth: Float =
      fontSizeSp * cjkAwareWidthRatio(codeText = footnoteText) + MarkdownStyle.InlineCode.PLACEHOLDER_PADDING_SP
    val chipHeight: Float =
      fontSizeSp * MarkdownStyle.Latex.PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + MarkdownStyle.InlineCode.PLACEHOLDER_PADDING_SP
    val placeholderShape = Placeholder(
      width = chipWidth.sp,
      height = chipHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center
    )
    inlineContent[placeholderKey] = InlineTextContent(placeholder = placeholderShape) {
      FootnoteMark(text = footnoteText, fontSizeSp = numberFontSizeSp, isDefinition = isDefinition)
    }
    return placeholderKey
  }

  /** Consume a fresh inline LaTeX placeholder. Uses real measurement via LatexMeasurerState, fallback to estimation. */
  fun allocateLatex(formulaText: String): String {
    val placeholderKey: String = LATEX_PLACEHOLDER_BASE.toString().repeat(n = latexCounter + 1)
    latexCounter += 1

    val dimensions: LatexDimensions? =
      if (latexMeasurer != null && density != null) {
        latexMeasureCache.getOrPut(key = formulaText) {
          latexMeasurer.measure(
            latex = formulaText,
            config = LatexConfig(fontSize = fontSizeSp.sp)
          )
        }
      } else null
    val placeholderWidth: Float =
      if (dimensions != null && dimensions.widthPx > 0f && density != null) {
        dimensions.widthPx / density.density + MarkdownStyle.Latex.PLACEHOLDER_WIDTH_PADDING_SP
      } else {
        fontSizeSp * estimateLatexWidth(formulaText) + MarkdownStyle.Latex.PLACEHOLDER_WIDTH_PADDING_SP
      }

    val placeholderHeight: Float =
      fontSizeSp * MarkdownStyle.Latex.INLINE_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + MarkdownStyle.Latex.INLINE_PLACEHOLDER_PADDING_SP

    val placeholderShape = Placeholder(
      width = placeholderWidth.sp,
      height = placeholderHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center
    )
    inlineContent[placeholderKey] = InlineTextContent(placeholder = placeholderShape) {
      RenderInlineLatex(
        formula = formulaText,
        fontSizeSp = fontSizeSp
      )
    }
    return placeholderKey
  }
}

/** Per-character width sum: [MarkdownStyle.Latex.MONOSPACE_CJK_RATIO] for CJK, [MarkdownStyle.Latex.MONOSPACE_LATIN_RATIO] for everything else. */
internal fun cjkAwareWidthRatio(codeText: String): Float {
  return codeText.fold(initial = 0f) { acc, char ->
    acc + if (isCjkChar(char)) MarkdownStyle.Latex.MONOSPACE_CJK_RATIO
    else MarkdownStyle.Latex.MONOSPACE_LATIN_RATIO
  }
}

/** Fallback width estimation for LaTeX formulas (used when LatexMeasurerState unavailable). */
internal fun estimateLatexWidth(formula: String): Float {
  if (formula.isEmpty()) return 0f
  var totalWidth = 0f
  for (chinaChar in formula) {
    totalWidth += when {
      chinaChar.isDigit() -> MarkdownStyle.Latex.DIGIT_RATIO
      chinaChar.isLetter() -> MarkdownStyle.Latex.LETTER_RATIO
      isCjkChar(chinaChar) -> MarkdownStyle.Latex.MONOSPACE_CJK_RATIO
      chinaChar in MarkdownStyle.Latex.NARROW_SYMBOLS -> MarkdownStyle.Latex.NARROW_SYMBOL_RATIO
      else -> MarkdownStyle.Latex.DEFAULT_SYMBOL_RATIO
    }
  }
  return totalWidth
}

private fun renderInlineChildren(
  parentNode: Node,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val children: NodeChildren = NodeChildren.of(parentNode)
  val firstChild: Node? = children.first
  if (firstChild != null) renderInlineNode(firstChild, renderState, annotatedStringBuilder)
  for (child in children.rest) renderInlineNode(child, renderState, annotatedStringBuilder)
}


/** Dispatch a single inline node. Unknown / extension nodes recurse into children. */
private fun renderInlineNode(
  inlineNode: Node,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  when (inlineNode) {
    is Text -> renderTextInline(textNode = inlineNode, renderState, annotatedStringBuilder)
    is Emphasis -> renderEmphasisInline(emphasisNode = inlineNode, renderState, annotatedStringBuilder)
    is StrongEmphasis -> renderStrongEmphasisInline(renderState, strongEmphasisNode = inlineNode, annotatedStringBuilder)
    is Code -> renderCodeInline(codeNode = inlineNode, renderState, annotatedStringBuilder)
    is Link -> renderLinkInline(linkNode = inlineNode, renderState, annotatedStringBuilder)
    is Image -> renderImageInline(imageNode = inlineNode, renderState, annotatedStringBuilder)
    is SoftLineBreak -> annotatedStringBuilder.withStyle(renderState.currentStyle) { append(' ') }
    is HardLineBreak -> annotatedStringBuilder.withStyle(renderState.currentStyle) { append('\n') }
    is Strikethrough -> renderStrikethroughInline(renderState, strikethroughNode = inlineNode, annotatedStringBuilder)
    else -> {
      if (inlineNode.firstChild != null)
        renderInlineChildren(parentNode = inlineNode, renderState, annotatedStringBuilder)
    }
  }
}


private fun renderTextInline(
  textNode: Text, renderState: RenderState, annotatedStringBuilder: AnnotatedString.Builder
) {
  val literal: String = textNode.literal.orEmpty()
  if (literal.isEmpty()) return

  val footnoteMatches: List<MatchResult> = INLINE_FOOTNOTE_REGEX.findAll(input = literal).toList()
  val latexMatches: List<MatchResult> = INLINE_LATEX_REGEX.findAll(input = literal)
    .filter { isInlineLatexCandidate(match = it, text = literal) }
    .toList()

  val urlMatches: List<MatchResult> =
    if (textNode.parent is Link) emptyList()
    else bareUrlRegex.findAll(input = literal).toList()

  val parenLatexMatches: List<MatchResult> =
    if (renderState.parenLatexFormulas.isEmpty()) {
      emptyList()
    } else {
      INLINE_LATEX_PAREN_MARKER_REGEX.findAll(input = literal)
        .filter { renderState.parenLatexFormulas.containsKey(it.value) }
        .toList()
    }
  if (footnoteMatches.isEmpty() && latexMatches.isEmpty() && parenLatexMatches.isEmpty() && urlMatches.isEmpty()) {
    if (renderState.currentStyle == SpanStyle()) annotatedStringBuilder.append(literal)
    else annotatedStringBuilder.withStyle(renderState.currentStyle) { append(literal) }
    return
  }

  val allMatches: List<MatchResult> = (footnoteMatches + latexMatches + parenLatexMatches + urlMatches)
    .sortedBy { it.range.first }

  val linkStyle: SpanStyle = renderState.currentStyle.copy(
    color = renderState.linkColor,
    textDecoration = combineDecoration(
      added = renderState.currentStyle.textDecoration ?: TextDecoration.None,
      existing = TextDecoration.Underline
    ),
  )

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

    when (match) {
      in footnoteMatches -> {
        val footnoteText: String = match.groupValues[2]
          .ifEmpty { match.groupValues[3] }
          .ifEmpty { match.groupValues[4] }
        if (footnoteText.isNotEmpty()) {
          val isDefinition: Boolean = literal.getOrNull(index = match.range.last + 1) == ':'
          val placeholderKey: String = renderState.allocateFootnote(footnoteText, isDefinition)
          annotatedStringBuilder.pushStringAnnotation(tag = FOOTNOTE_TEXT_TAG, annotation = footnoteText)
          annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
          annotatedStringBuilder.pushStyle(SpanStyle())
          annotatedStringBuilder.append(placeholderKey)
          annotatedStringBuilder.pop()
          annotatedStringBuilder.pop()
          annotatedStringBuilder.pop()
        }
      }

      in parenLatexMatches -> {
        val formulaText: String = renderState.parenLatexFormulas[match.value].orEmpty()
        if (formulaText.isNotEmpty()) {
          val placeholderKey: String = renderState.allocateLatex(formulaText)
          annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
          annotatedStringBuilder.pushStyle(SpanStyle())
          annotatedStringBuilder.append(placeholderKey)
          annotatedStringBuilder.pop()
          annotatedStringBuilder.pop()
        }
      }

      in urlMatches -> {
        val url: String = match.value
        val urlStart: Int = annotatedStringBuilder.length
        annotatedStringBuilder.pushStringAnnotation(tag = INLINE_URL_TAG, annotation = url)
        renderState.withStyle(replacementStyle = linkStyle) { annotatedStringBuilder.append(url) }
        annotatedStringBuilder.pop()
        val urlEnd: Int = annotatedStringBuilder.length
        if (urlEnd > urlStart) {
          renderState.urlAnnotations += UrlAnnotation(start = urlStart, end = urlEnd, url = url)
        }
      }

      else -> {
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
    }

    lastIndex = match.range.last + 1
  }

  val postText: String = literal.substring(startIndex = lastIndex)
  if (postText.isNotEmpty()) {
    if (renderState.currentStyle == SpanStyle()) annotatedStringBuilder.append(postText)
    else annotatedStringBuilder.withStyle(renderState.currentStyle) { append(postText) }
  }
}

private fun renderEmphasisInline(
  emphasisNode: Emphasis, renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  // Render emphasis as normal text (no italic style, no markers)
  renderState.withStyle(replacementStyle = renderState.currentStyle) {
    renderInlineChildren(parentNode = emphasisNode, renderState, annotatedStringBuilder)
  }
}

private fun renderStrongEmphasisInline(
  renderState: RenderState,
  strongEmphasisNode: StrongEmphasis,
  annotatedStringBuilder: AnnotatedString.Builder,
) {
  val boldStyle: SpanStyle = renderState.currentStyle.copy(fontWeight = FontWeight.SemiBold)
  renderState.withStyle(replacementStyle = boldStyle) {
    renderInlineChildren(parentNode = strongEmphasisNode, renderState, annotatedStringBuilder)
  }
}

private fun renderCodeInline(
  codeNode: Code,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val codeText: String = codeNode.literal.orEmpty()
  if (codeText.isEmpty()) return
  val codeSpanStyle = SpanStyle(
    color = renderState.codeColor,
    fontFamily = renderState.editorFontFamily,
    fontSize = renderState.fontSizeSp.sp,
  )
  annotatedStringBuilder.pushStringAnnotation(tag = INLINE_CODE_SPAN_TAG, annotation = codeText)
  annotatedStringBuilder.pushStyle(codeSpanStyle)
  annotatedStringBuilder.append(codeText)
  annotatedStringBuilder.pop() // SpanStyle
  annotatedStringBuilder.pop() // StringAnnotation
}

private fun renderLinkInline(
  linkNode: Link, renderState: RenderState, annotatedStringBuilder: AnnotatedString.Builder
) {
  val linkStyle: SpanStyle = renderState.currentStyle.copy(
    color = renderState.linkColor,
    textDecoration = combineDecoration(
      added = renderState.currentStyle.textDecoration ?: TextDecoration.None,
      existing = TextDecoration.Underline
    ),
  )
  val linkUrl: String = linkNode.destination.orEmpty()
  val linkTextStart: Int = annotatedStringBuilder.length
  annotatedStringBuilder.pushStringAnnotation(tag = INLINE_URL_TAG, annotation = linkUrl)
  renderState.withStyle(replacementStyle = linkStyle) {
    renderInlineChildren(parentNode = linkNode, renderState, annotatedStringBuilder)
  }
  annotatedStringBuilder.pop()
  val linkTextEnd: Int = annotatedStringBuilder.length
  if (linkUrl.isNotEmpty() && linkTextEnd > linkTextStart) {
    renderState.urlAnnotations += UrlAnnotation(start = linkTextStart, end = linkTextEnd, url = linkUrl)
  }
}

private fun renderImageInline(
  imageNode: Image, renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val imageAltStyle: SpanStyle = renderState.currentStyle.copy(
    fontStyle = FontStyle.Italic,
    color = renderState.imageAltColor,
    fontFamily = renderState.editorFontFamily,
    textDecoration = renderState.currentStyle.textDecoration ?: TextDecoration.None
  )
  val iconPlaceholder: String = renderState.allocateImageAlt()
  renderState.withStyle(replacementStyle = imageAltStyle) {
    annotatedStringBuilder.pushStringAnnotation(
      tag = INLINE_CONTENT_TAG, annotation = iconPlaceholder
    )
    annotatedStringBuilder.append(iconPlaceholder)
    annotatedStringBuilder.pop()
    annotatedStringBuilder.append(' ')
    renderInlineChildren(parentNode = imageNode, renderState, annotatedStringBuilder)
  }
}

private fun renderStrikethroughInline(
  renderState: RenderState,
  strikethroughNode: Strikethrough,
  annotatedStringBuilder: AnnotatedString.Builder
) {
  val strikethroughStyle: SpanStyle = renderState.currentStyle.copy(
    textDecoration = combineDecoration(
      added = TextDecoration.LineThrough,
      existing = (renderState.currentStyle.textDecoration ?: TextDecoration.None)
    )
  )
  renderState.withStyle(replacementStyle = strikethroughStyle) {
    renderInlineChildren(parentNode = strikethroughNode, renderState, annotatedStringBuilder)
  }
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

/**
 * Split walker output at link boundaries: prose stays on Text(annotated, inlineContent),
 * links use ExternalLink (standalone Composable with icon).
 */
internal fun splitIntoInlineSegments(
  annotated: AnnotatedString,
  urlAnnotations: List<UrlAnnotation>, inlineContent: Map<String, InlineTextContent>
): List<InlineSegment> {
  if (urlAnnotations.isEmpty())
    return listOf(InlineSegment.TextSegment(annotated, inlineContent))

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
    val linkText: String = stripInlineLinkText(range = linkTextContent)
    segments += InlineSegment.LinkSegment(text = linkText, url = url)
    cursor = end
  }
  if (cursor < annotated.length) {
    segments += InlineSegment.TextSegment(
      inlineContent = inlineContent,
      annotated = annotated.subSequence(cursor, annotated.length)
    )
  }
  return segments
}

/** Drop PUA placeholders from a link's text range — chips/icons/formulas render through their own inlineContent. */
private fun stripInlineLinkText(range: CharSequence): String {
  if (range.none { it.isInlinePlaceholderPua() })
    return range.toString()
  val output: StringBuilder = StringBuilder(range.length)
  for (char in range) {
    if (!char.isInlinePlaceholderPua())
      output.append(char)
  }
  return output.toString()
}

/** One of the PUA placeholders reserved for an inline-only chip / icon / formula. */
private fun Char.isInlinePlaceholderPua(): Boolean =
  when (this) {
    IMAGE_ALT_PLACEHOLDER_BASE,
    FOOTNOTE_PLACEHOLDER_BASE,
    LATEX_PLACEHOLDER_BASE -> true

    else -> this.code in PAREN_LATEX_MARKER_RANGE_START..DOLLAR_LATEX_MARKER_RANGE_END
  }

/**
 * Footnote marker composable.
 *
 * A *definition* chip (`[^2]: ...`) registers its on-screen position with
 * [LocalFootnoteRegistry] so references can jump to it. A *reference* chip
 * (`[^2]` in prose) is clickable and smooth-scrolls to its matching definition.
 */
@Composable
private fun FootnoteMark(text: String, fontSizeSp: Float, isDefinition: Boolean = false) {
  val infoColor: Color = JewelTheme.globalColors.text.info
  val badgeColor: Color = JewelTheme.linkStyle.colors.content
  val registry: FootnoteRegistry = LocalFootnoteRegistry.current
  val footnoteFlashBackground: Color = rememberBadgeBlueColor()
  val definitionChipId: Any = remember(key1 = text, key2 = isDefinition) { Any() }

  val normalBackground: Color = infoColor.copy(alpha = MarkdownStyle.InlineCode.BACKGROUND_ALPHA)

  val flashTarget: FootnoteRegistry.FlashTarget? = registry.flashTarget
  val footnoteFlashTriggerKey =
    if (isDefinition) flashTarget?.takeIf { it.label == text }
    else null

  var isFlashing by remember { mutableStateOf(value = false) }
  LaunchedEffect(key1 = footnoteFlashTriggerKey) {
    if (footnoteFlashTriggerKey != null) {
      isFlashing = true
      delay(
        duration = (MarkdownStyle.FootnoteAnimation.FLASH_IN_MS.toLong() +
          MarkdownStyle.FootnoteAnimation.FLASH_HOLD_MS).milliseconds
      )
      isFlashing = false
    }
  }

  val backgroundColor by animateColorAsState(
    targetValue =
      if (isFlashing) footnoteFlashBackground
      else normalBackground,
    animationSpec = tween(
      durationMillis =
        if (isFlashing) MarkdownStyle.FootnoteAnimation.FLASH_IN_MS
        else MarkdownStyle.FootnoteAnimation.FLASH_OUT_MS
    ),
    label = "footnoteFlashBackground"
  )
  val foregroundColor by animateColorAsState(
    targetValue =
      if (isFlashing) Color.White
      else badgeColor,
    animationSpec = tween(
      durationMillis =
        if (isFlashing) MarkdownStyle.FootnoteAnimation.FLASH_IN_MS
        else MarkdownStyle.FootnoteAnimation.FLASH_OUT_MS
    ),
    label = "footnoteFlashText"
  )

  val hoverInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() }
  val isHovered by hoverInteractionSource.collectIsHoveredAsState()

  val chipStyle = TextStyle(
    color = foregroundColor,
    fontSize = fontSizeSp.sp,
    lineHeight = fontSizeSp.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = JewelTheme.editorTextStyle.fontFamily,
    textDecoration =
      if (isHovered) TextDecoration.Underline
      else null
  )
  Box(
    modifier = Modifier
      .background(
        color = backgroundColor,
        shape = RoundedCornerShape(size = MarkdownStyle.InlineCode.CORNER_RADIUS)
      )
      .padding(
        vertical = MarkdownStyle.InlineCode.PADDING_VERTICAL,
        horizontal = MarkdownStyle.InlineCode.PADDING_HORIZONTAL
      )
      .hoverable(hoverInteractionSource)
      .then(
        other = if (isDefinition) {
          Modifier.onGloballyPositioned { coordinates ->
            registry.updateDefinitionPosition(
              label = text,
              definitionChipId,
              positionInWindow = coordinates.localToWindow(relativeToLocal = Offset.Zero)
            )
          }
        } else {
          Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = hoverInteractionSource) {
              registry.scrollToFootnote(label = text)
            }
        }
      )
  ) {
    Text(
      text = text,
      maxLines = 1,
      softWrap = false,
      style = chipStyle
    )
  }
}

/** Flash timing for a definition chip that has just been jumped to. */
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
  return base.copy(alpha = MarkdownStyle.Image.ALT_COLOR_ALPHA)
}

/** Editor font size in sp; falls back when the theme's `fontSize` is unspecified. */
@Composable
private fun resolveEditorFontSizeSp(): Float {
  val editorStyle: TextStyle = JewelTheme.editorTextStyle
  val fontSize: androidx.compose.ui.unit.TextUnit = editorStyle.fontSize
  return when {
    fontSize.isSp -> fontSize.value
    fontSize.isEm -> fontSize.value * MarkdownStyle.FontFallback.EM_SIZE_SP
    else -> MarkdownStyle.FontFallback.BODY_SIZE_SP
  }
}
