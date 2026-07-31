/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InlineMarkdown.kt  2026-07-31 11:32:57 Changed by gwy
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.LatexDimensions
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.ui.GradumSpacing
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


private const val INLINE_CODE_PLACEHOLDER: Char = '\uE000'
private const val INLINE_CODE_TEXT_TAG: String = "INLINE_CODE_TEXT"

/** Compose's internal tag for inline-content lookups. Must match exactly — custom tags are silently dropped. */
internal const val INLINE_CONTENT_TAG: String = "androidx.compose.foundation.text.inlineContent"

private const val INLINE_URL_TAG: String = "INLINE_URL"
private const val IMAGE_ALT_PLACEHOLDER_BASE: Char = '\uE001'

internal const val FOOTNOTE_TEXT_TAG: String = "FOOTNOTE_TEXT"
private const val FOOTNOTE_PLACEHOLDER_BASE: Char = '\uE002'

/** PUA base for inline LaTeX placeholders. Distinct from code/image/footnote bases. */
private const val LATEX_PLACEHOLDER_BASE: Char = '\uE003'

/** PUA range for pre-processed \(…\)-form LaTeX markers (256 slots). CommonMark treats \( as backslash-escape, so we must pre-process BEFORE parsing. */
private const val PAREN_LATEX_MARKER_RANGE_START: Int = 0xE100
private const val PAREN_LATEX_MARKER_RANGE_END: Int = 0xE1FF
private const val PAREN_LATEX_MARKER_RANGE_SIZE: Int =
  PAREN_LATEX_MARKER_RANGE_END - PAREN_LATEX_MARKER_RANGE_START + 1

/** PUA range for pre-processed $…$-form LaTeX markers (256 slots). CommonMark treats _ as emphasis delimiter, splitting formulas. */
private const val DOLLAR_LATEX_MARKER_RANGE_START: Int = 0xE200
private const val DOLLAR_LATEX_MARKER_RANGE_END: Int = 0xE2FF
private const val DOLLAR_LATEX_MARKER_RANGE_SIZE: Int =
  DOLLAR_LATEX_MARKER_RANGE_END - DOLLAR_LATEX_MARKER_RANGE_START + 1

private const val IMAGE_ALT_ICON_EM_SCALE: Float = 1.4f

private val inlineCodePaddingHorizontal: Dp = GradumSpacing.sm
private val inlineCodePaddingVertical: Dp = GradumSpacing.xs


private val inlineCodeCornerRadius: Dp = GradumSpacing.sm
internal const val INLINE_CODE_BACKGROUND_ALPHA: Float = 0.16f
private const val MONOSPACE_LATIN_RATIO: Float = 0.6f
private const val MONOSPACE_CJK_RATIO: Float = 1.0f
private const val PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 1.0f

/** Line-height multiplier for inline LaTeX placeholders. Tall formulas (fractions, radicals) need extra height. */
private const val INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 1.4f

/** Chip/footnote padding (10sp) vs LaTeX padding (2sp). LaTeX renderer draws tight bounding boxes. */
private const val INLINE_CODE_PLACEHOLDER_PADDING_SP: Float = 10f
private const val PLACEHOLDER_WIDTH_PADDING_SP: Float = 2f

/** Character width ratios for inline LaTeX placeholder estimation (fallback only — real measurement preferred). */
private const val LATEX_LETTER_RATIO: Float = 0.45f
private const val LATEX_DIGIT_RATIO: Float = 0.45f
private const val LATEX_NARROW_SYMBOL_RATIO: Float = 0.25f
private const val LATEX_DEFAULT_SYMBOL_RATIO: Float = 0.35f

/** Math symbols that render significantly narrower than their character advance. */
private val LATEX_NARROW_SYMBOLS: Set<Char> = setOf(
  '=', '+', '-', '^', '_', '{', '}', '\\', ',', '.', ';', ':',
  '!', '|', '<', '>', '/', '*', '~', '(', ')', '[', ']'
)

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
 * Matches bare http/https/ftp URLs in plain text. The last character
 * excludes common trailing punctuation so `https://example.com.` captures
 * only the URL without the period.
 */
private val bareUrlRegex: Regex = Regex(
  """https?://[^\s<>"'()\[\]]+[^\s<>"'().,;:!?\]]""",
  RegexOption.IGNORE_CASE
)

/** One renderable inline representation: PUA-placeholder AnnotatedString + chip InlineTextContent map. */
data class InlineMarkdownRender(
  val paragraphCount: Int,
  val annotated: AnnotatedString,
  val urlAnnotations: List<UrlAnnotation>,
  val inlineContent: Map<String, InlineTextContent>,
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
fun rememberInlineMarkdownRender(plainText: String): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return InlineMarkdownRenderResult(render = null, bailReason = null)
  val chipTint: Color = resolveInlineCodeTint()
  val linkColor: Color = JewelTheme.linkStyle.colors.content
  val imageAltColor: Color = resolveImageAltColor()
  val fontSizeSp: Float = resolveEditorFontSizeSp()
  val editorFontFamily: FontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
  val latexMeasurer: LatexMeasurerState = rememberLatexMeasurer()
  val density: Density = androidx.compose.ui.platform.LocalDensity.current
  return remember(plainText, chipTint, linkColor, imageAltColor, fontSizeSp, editorFontFamily) {
    parseInlineMarkdown(
      density = density,
      chipTint = chipTint,
      plainText = plainText,
      linkColor = linkColor,
      fontSizeSp = fontSizeSp,
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


/** Regex for inline footnotes: `^[text]`, `[^ref]`, or `[1]`. */
internal val INLINE_FOOTNOTE_REGEX: Regex = Regex("""(\^\[([^]]*)]|\[\^([^]]*)]|\[(\d+)])""")

/**
 * Regex for inline LaTeX: $$…$$ (must come first to avoid matching inner $ of $$x^2$$) and $…$.
 * Non-greedy, no newlines. Block-level $$…$$ on its own line is handled by LatexBlockExtension.
 */
internal val INLINE_LATEX_REGEX: Regex = Regex("""\$\$([^$\n]+?)\$\$|\$([^$\n]+?)\$""")

/** Regex for \(…\)-form LaTeX. Applied BEFORE CommonMark to preserve formulas (CommonMark strips backslashes). */
internal val INLINE_LATEX_PAREN_REGEX: Regex = Regex("""\\\(([^()\n]+?)\\\)""")

/** Regex matching PUA chars in the pre-processed LaTeX marker range (U+E100–U+E2FF). */
internal val INLINE_LATEX_PAREN_MARKER_REGEX: Regex = Regex("""[\uE100-\uE2FF]""")

/** Pure CommonMark → `AnnotatedString` walk. Theme values passed in by the caller. */
internal fun parseInlineMarkdown(
  density: Density? = null,
  latexMeasurer: LatexMeasurerState? = null,
  editorFontFamily: FontFamily = FontFamily.Default,
  plainText: String, fontSizeSp: Float, chipTint: Color, linkColor: Color, imageAltColor: Color
): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return InlineMarkdownRenderResult(render = null, bailReason = null)

  val parenPreprocessed: PreprocessedParenLatex =
    preprocessParenLatexFormulas(plainText)

  val dollarPreprocessed: PreprocessedParenLatex =
    preprocessDollarLatexFormulas(parenPreprocessed.text)

  val mergedFormulas: Map<String, String> = parenPreprocessed.formulaByMarker + dollarPreprocessed.formulaByMarker

  val document: Document = parseCommonmarkDocument(dollarPreprocessed.text)
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
    parenLatexFormulas = mergedFormulas,
    latexMeasurer = latexMeasurer,
    density = density
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
  val formulaByMarker: Map<String, String>
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
  val matches: List<MatchResult> = INLINE_LATEX_REGEX.findAll(rawText).toList()
  if (matches.isEmpty()) {
    return PreprocessedParenLatex(text = rawText, formulaByMarker = emptyMap())
  }
  val formulaByMarker: MutableMap<String, String> = LinkedHashMap(matches.size)
  val rewritten: StringBuilder = StringBuilder(rawText.length)
  var lastIndex = 0
  for ((matchIndex, match) in matches.withIndex()) {
    if (matchIndex >= DOLLAR_LATEX_MARKER_RANGE_SIZE) continue
    if (match.range.first < lastIndex) continue

    rewritten.append(rawText, lastIndex, match.range.first)
    val markerCode: Int = DOLLAR_LATEX_MARKER_RANGE_START + matchIndex
    val marker = String(Character.toChars(markerCode))
    val formula = match.groupValues[1].ifEmpty { match.groupValues[2] }
    formulaByMarker[marker] = formula
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


/** Walk a parent node's inline children directly into InlineMarkdownRenderResult. */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal fun parseInlineNodes(
  parentNode: Node, fontSizeSp: Float, linkColor: Color, imageAltColor: Color,
  editorFontFamily: FontFamily = FontFamily.Default,
  latexMeasurer: LatexMeasurerState? = null,
  density: Density? = null
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      fontSizeSp = fontSizeSp,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily,
      latexMeasurer = latexMeasurer,
      density = density
    )
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      renderInlineChildren(parentNode, renderState, builder)
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
  parenLatexFormulas: Map<String, String> = emptyMap(),
  latexMeasurer: LatexMeasurerState? = null,
  density: Density? = null
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      fontSizeSp = fontSizeSp,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
      editorFontFamily = editorFontFamily,
      parenLatexFormulas = parenLatexFormulas,
      latexMeasurer = latexMeasurer,
      density = density
    )
    val preview: String = plainText.take(BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.debug("InlineMarkdown: parse start — text.length=${plainText.length}, fontSizeSp=$fontSizeSp, chipTint=$chipTint, text=$preview")
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      topBlocks.forEachIndexed { blockIndex, blockNode ->
        if (blockIndex > 0) builder.append("\n\n")
        renderInlineChildren(blockNode as Paragraph, renderState, builder)
      }
    }
    log.debug("InlineMarkdown: parse done — chipCounter=${renderState.chipCounter}, inlineContent.keys=${renderState.inlineContent.keys}")
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
  var latexCounter: Int = 0,
  var imageAltCounter: Int = 0,
  var footnoteCounter: Int = 0,
  var currentStyle: SpanStyle = SpanStyle(),
  val urlAnnotations: MutableList<UrlAnnotation> = mutableListOf(),
  val inlineContent: MutableMap<String, InlineTextContent> = mutableMapOf(),
  val parenLatexFormulas: Map<String, String> = emptyMap(),
  private val latexMeasurer: LatexMeasurerState? = null,
  val density: Density? = null
) {
  private val latexMeasureCache = mutableMapOf<String, LatexDimensions?>()

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


  /** Consume a fresh image-alt placeholder + register the GradumIcons.Image icon. */
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
      Icon(
        contentDescription = null,
        modifier = Modifier.size(iconSize.dp),
        key = gradum.idea.icons.GradumIcons.Image
      )
    }
    return placeholder
  }

  /** Consume a fresh footnote placeholder + register [FootnoteMark]. */
  fun allocateFootnote(footnoteText: String, isDefinition: Boolean = false): String {
    val placeholderKey: String = FOOTNOTE_PLACEHOLDER_BASE.toString().repeat(footnoteCounter + 1)
    footnoteCounter += 1
    val numberFontSizeSp: Float = fontSizeSp - 1f
    val chipWidth: Float = fontSizeSp * cjkAwareWidthRatio(footnoteText) + INLINE_CODE_PLACEHOLDER_PADDING_SP
    val chipHeight: Float = fontSizeSp * PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + INLINE_CODE_PLACEHOLDER_PADDING_SP
    val placeholderShape = Placeholder(
      width = chipWidth.sp,
      height = chipHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
    )
    inlineContent[placeholderKey] = InlineTextContent(placeholder = placeholderShape) {
      FootnoteMark(text = footnoteText, fontSizeSp = numberFontSizeSp, isDefinition = isDefinition)
    }
    return placeholderKey
  }

  /** Consume a fresh inline LaTeX placeholder. Uses real measurement via LatexMeasurerState, fallback to estimation. */
  fun allocateLatex(formulaText: String): String {
    val placeholderKey: String = LATEX_PLACEHOLDER_BASE.toString().repeat(latexCounter + 1)
    latexCounter += 1

    val dimensions: LatexDimensions? = if (latexMeasurer != null && density != null) {
      latexMeasureCache.getOrPut(formulaText) {
        latexMeasurer.measure(formulaText, config = LatexConfig(fontSize = fontSizeSp.sp))
      }
    } else null
    val placeholderWidth: Float = if (dimensions != null && dimensions.widthPx > 0f && density != null) {
      dimensions.widthPx / density.density + PLACEHOLDER_WIDTH_PADDING_SP
    } else {
      fontSizeSp * estimateLatexWidth(formulaText) + PLACEHOLDER_WIDTH_PADDING_SP
    }

    val placeholderHeight: Float = fontSizeSp * INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + INLINE_LATEX_PLACEHOLDER_PADDING_SP

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


/** Per-character width sum: [MONOSPACE_CJK_RATIO] for CJK, [MONOSPACE_LATIN_RATIO] for everything else. */
internal fun cjkAwareWidthRatio(codeText: String): Float {
  if (codeText.isEmpty()) return 0f
  var totalRatio = 0f
  for (char in codeText) totalRatio += if (isCjkChar(char)) MONOSPACE_CJK_RATIO else MONOSPACE_LATIN_RATIO
  return totalRatio
}

/** Fallback width estimation for LaTeX formulas (used when LatexMeasurerState unavailable). */
internal fun estimateLatexWidth(formula: String): Float {
  if (formula.isEmpty()) return 0f
  var totalWidth = 0f
  for (chinaChar in formula) {
    totalWidth += when {
      isCjkChar(chinaChar) -> MONOSPACE_CJK_RATIO
      chinaChar in LATEX_NARROW_SYMBOLS -> LATEX_NARROW_SYMBOL_RATIO
      chinaChar.isLetter() -> LATEX_LETTER_RATIO
      chinaChar.isDigit() -> LATEX_DIGIT_RATIO
      else -> LATEX_DEFAULT_SYMBOL_RATIO
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
    is Code -> renderCodeInline(inlineNode, renderState, annotatedStringBuilder)
    is Link -> renderLinkInline(inlineNode, renderState, annotatedStringBuilder)
    is Image -> renderImageInline(inlineNode, renderState, annotatedStringBuilder)
    is SoftLineBreak -> annotatedStringBuilder.withStyle(renderState.currentStyle) { append(' ') }
    is HardLineBreak -> annotatedStringBuilder.withStyle(renderState.currentStyle) { append('\n') }
    is Strikethrough -> renderStrikethroughInline(renderState, inlineNode, annotatedStringBuilder)
    else -> {
      if (inlineNode.firstChild != null) renderInlineChildren(inlineNode, renderState, annotatedStringBuilder)
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
  val urlMatches: List<MatchResult> = bareUrlRegex.findAll(literal).toList()

  val parenLatexMatches: List<MatchResult> = if (renderState.parenLatexFormulas.isEmpty()) {
    emptyList()
  } else {
    INLINE_LATEX_PAREN_MARKER_REGEX.findAll(literal)
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
      renderState.currentStyle.textDecoration ?: TextDecoration.None,
      TextDecoration.Underline
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
          val isDefinition: Boolean = literal.getOrNull(match.range.last + 1) == ':'
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
        renderState.withStyle(linkStyle) { annotatedStringBuilder.append(url) }
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
  renderState.withStyle(renderState.currentStyle) { renderInlineChildren(emphasisNode, renderState, annotatedStringBuilder) }
}

private fun renderStrongEmphasisInline(
  strongEmphasisNode: StrongEmphasis, annotatedStringBuilder: AnnotatedString.Builder, renderState: RenderState
) {
  val boldStyle: SpanStyle = renderState.currentStyle.copy(fontWeight = FontWeight.SemiBold)
  renderState.withStyle(boldStyle) {
    renderInlineChildren(strongEmphasisNode, renderState, annotatedStringBuilder)
  }
}

private fun renderCodeInline(
  codeNode: Code,
  renderState: RenderState,
  annotatedStringBuilder: AnnotatedString.Builder
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
  linkNode: Link, renderState: RenderState, annotatedStringBuilder: AnnotatedString.Builder
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
  renderState.withStyle(linkStyle) { renderInlineChildren(linkNode, renderState, annotatedStringBuilder) }
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
    renderInlineChildren(imageNode, renderState, annotatedStringBuilder)
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
  renderState.withStyle(strikethroughStyle) { renderInlineChildren(strikethroughNode, renderState, annotatedStringBuilder) }
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
 * Split walker output at link boundaries: prose stays on Text(annotated, inlineContent),
 * links use ExternalLink (standalone Composable with icon).
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

/** Drop PUA placeholders from a link's text range — chips/icons/formulas render through their own inlineContent. */
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

  else -> this.code in PAREN_LATEX_MARKER_RANGE_START..DOLLAR_LATEX_MARKER_RANGE_END
}


/**
 * Inline code chip. Uses background(color, shape) without clip to avoid clipping descenders (p, g, y).
 */
@Composable
private fun InlineCodeChip(
  text: String, fontSizeSp: Float
) {
  val editorStyle: TextStyle = JewelTheme.editorTextStyle
  val badgeColor: Color = JewelTheme.globalColors.text.info
  val chipStyle = TextStyle(
    color = badgeColor,
    fontSize = fontSizeSp.sp,
    lineHeight = fontSizeSp.sp,
    fontWeight = FontWeight.Normal,
    fontFamily = editorStyle.fontFamily
  )
  Box(
    modifier = Modifier
      .background(
        shape = RoundedCornerShape(inlineCodeCornerRadius),
        color = badgeColor.copy(alpha = INLINE_CODE_BACKGROUND_ALPHA)
      )
      .padding(
        vertical = inlineCodePaddingVertical,
        horizontal = inlineCodePaddingHorizontal
      )
      .onGloballyPositioned { coordinates ->
        log.info("InlineCodeChip render: text='$text', actualWidth=${coordinates.size.width}px, fontSize=$fontSizeSp")
      }
  ) {
    Text(
      text = text,
      maxLines = 1,
      softWrap = false,
      style = chipStyle
    )
  }
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
  val badgeColor: Color = JewelTheme.linkStyle.colors.content
  val infoColor: Color = JewelTheme.globalColors.text.info
  val registry: FootnoteRegistry = LocalFootnoteRegistry.current
  val footnoteFlashBackground: Color = rememberBadgeBlueColor()
  val definitionChipId: Any = remember(text, isDefinition) { Any() }

  val normalBackground: Color = infoColor.copy(alpha = INLINE_CODE_BACKGROUND_ALPHA)

  val flashTarget: FootnoteRegistry.FlashTarget? = registry.flashTarget
  val footnoteFlashTriggerKey = if (isDefinition) flashTarget?.takeIf { it.label == text } else null

  var isFlashing by remember { mutableStateOf(false) }
  LaunchedEffect(footnoteFlashTriggerKey) {
    if (footnoteFlashTriggerKey != null) {
      isFlashing = true
      delay((FootnoteFlashInMillis.toLong() + FootnoteFlashHoldMillis).milliseconds)
      isFlashing = false
    }
  }

  val backgroundColor by animateColorAsState(
    targetValue = if (isFlashing) footnoteFlashBackground else normalBackground,
    animationSpec = tween(if (isFlashing) FootnoteFlashInMillis else FootnoteFlashOutMillis),
    label = "footnoteFlashBackground"
  )
  val foregroundColor by animateColorAsState(
    targetValue = if (isFlashing) Color.White else badgeColor,
    animationSpec = tween(if (isFlashing) FootnoteFlashInMillis else FootnoteFlashOutMillis),
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
    textDecoration = if (isHovered) TextDecoration.Underline else null
  )
  Box(
    modifier = Modifier
      .background(
        shape = RoundedCornerShape(inlineCodeCornerRadius),
        color = backgroundColor
      )
      .padding(
        vertical = inlineCodePaddingVertical,
        horizontal = inlineCodePaddingHorizontal
      )
      .hoverable(hoverInteractionSource)
      .then(
        if (isDefinition) {
          Modifier.onGloballyPositioned { coordinates ->
            registry.updateDefinitionPosition(
              text,
              definitionChipId,
              coordinates.localToWindow(Offset.Zero)
            )
          }
        } else {
          Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = hoverInteractionSource) {
              registry.scrollToFootnote(text)
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
private const val FootnoteFlashInMillis: Int = 200
private const val FootnoteFlashHoldMillis: Long = 150
private const val FootnoteFlashOutMillis: Int = 300

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
