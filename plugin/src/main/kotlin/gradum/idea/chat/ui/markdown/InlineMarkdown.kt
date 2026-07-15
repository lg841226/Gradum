/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * InlineMarkdown.kt  2026-07-15 18:47:26 Changed by gwy
 */

// Detekt defaults disagree with project standards (2-space indent, 200-char
// lines, Compose-PascalCase, 1-line spacing between imports and code, etc.).
@file:Suppress(
  "MaximumLineLength",
  "Indentation",
  "FunctionNaming",
  "SpacingBetweenPackageAndImports",
  "NoConsecutiveBlankLines",
  "NoMultipleSpaces",
  "ArgumentListWrapping",
)

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
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

// Em-based scale for the inline image icon's placeholder box.
// Jewel SVG icons draw at their intrinsic (usually 16dp) size;
// 1.4em at 14sp ≈ 19.6dp, so the icon fills the box with a tiny
// breathing margin and aligns its visual mass with the cap
// height of the surrounding text.
private const val IMAGE_ALT_ICON_EM_SCALE: Float = 1.4f
private val InlineCodePaddingHorizontal: Dp = GradumSpacing.xs
private val InlineCodePaddingVertical: Dp = 0.dp
private const val INLINE_CODE_BACKGROUND_ALPHA: Float = 0.12f
private const val MONOSPACE_LATIN_RATIO: Float = 0.6f
private const val MONOSPACE_CJK_RATIO: Float = 1.0f
private const val PLACEHOLDER_LINE_HEIGHT_MULTIPLIER: Float = 1.0f
private const val PLACEHOLDER_WIDTH_PADDING_SP: Float = 8f
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
  val annotated: AnnotatedString,
  val inlineContent: Map<String, InlineTextContent>,
  val paragraphCount: Int,
  val urlAnnotations: List<UrlAnnotation>,
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
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
  ) : InlineSegment

  /** A link chunk. [text] is the visible label, [url] the click target. */
  data class LinkSegment(
    val text: String,
    val url: String,
  ) : InlineSegment
}

/** Outcome of an inline parse. `render == null` → caller falls back to native `Markdown(...)`. */
data class InlineMarkdownRenderResult(
  val render: InlineMarkdownRender?,
  val bailReason: String?,
)


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
  return remember(plainText) {
    parseInlineMarkdown(
      plainText = plainText,
      fontSizeSp = fontSizeSp,
      chipTint = chipTint,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
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
  return remember(parentNode) {
    parseInlineNodes(
      parentNode = parentNode,
      fontSizeSp = fontSizeSp,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
    )
  }
}


/** Pure CommonMark → `AnnotatedString` walk. Theme values passed in by the caller. */
internal fun parseInlineMarkdown(
  plainText: String,
  fontSizeSp: Float,
  chipTint: Color,
  linkColor: Color,
  imageAltColor: Color,
): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return InlineMarkdownRenderResult(render = null, bailReason = null)

  val document: Document = parseCommonmarkDocument(plainText) ?: return bailWithReason(
    reason = "commonmark 解析异常: 详见 IDE log",
    plainText = plainText,
  )
  val children: NodeChildren = NodeChildren.of(document)
  val topBlocks: List<Node> = buildList {
    if (children.first != null) add(children.first)
    addAll(children.rest)
  }
  if (topBlocks.isEmpty()) return bailWithReason("段落为空 (no blocks)", plainText)
  val nonParagraphTypes: String? = collectNonParagraphTypes(topBlocks)
  if (nonParagraphTypes != null) {
    return bailWithReason("段落含非 prose 块: $nonParagraphTypes", plainText)
  }
  return buildInlineRender(
    plainText = plainText,
    topBlocks = topBlocks,
    fontSizeSp = fontSizeSp,
    chipTint = chipTint,
    linkColor = linkColor,
    imageAltColor = imageAltColor,
  )
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
  parentNode: Node,
  fontSizeSp: Float,
  linkColor: Color,
  imageAltColor: Color,
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      fontSizeSp = fontSizeSp,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
    )
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val builder: AnnotatedString.Builder = this
      renderInlineChildren(parentNode, builder, renderState)
    }
    InlineMarkdownRenderResult(
      render = InlineMarkdownRender(
        annotated = annotatedString,
        inlineContent = renderState.inlineContent.toMap(),
        paragraphCount = 1,
        urlAnnotations = renderState.urlAnnotations.toList(),
      ),
      bailReason = null,
    )
  } catch (exception: Exception) {
    val reason = "AST walker 异常: ${exception.javaClass.simpleName}: ${exception.message}"
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


private fun bailWithReason(
  reason: String,
  plainText: String,
): InlineMarkdownRenderResult {
  val preview: String = plainText.take(BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
  log.warn("Inline markdown bailed: $reason (text=$preview)")
  return InlineMarkdownRenderResult(render = null, bailReason = reason)
}


/** Walk a paragraph-only AST and build the [InlineMarkdownRender]. Catches walker exceptions as bail. */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
private fun buildInlineRender(
  plainText: String,
  topBlocks: List<Node>,
  fontSizeSp: Float,
  chipTint: Color,
  linkColor: Color,
  imageAltColor: Color,
): InlineMarkdownRenderResult {
  return try {
    val renderState = RenderState(
      fontSizeSp = fontSizeSp,
      linkColor = linkColor,
      imageAltColor = imageAltColor,
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
        annotated = annotatedString,
        inlineContent = renderState.inlineContent.toMap(),
        paragraphCount = topBlocks.size,
        urlAnnotations = renderState.urlAnnotations.toList(),
      ),
      bailReason = null,
    )
  } catch (exception: Exception) {
    val reason = "AST walker 异常: ${exception.javaClass.simpleName}: ${exception.message}"
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
  val inlineContent: MutableMap<String, InlineTextContent> = mutableMapOf(),
  val urlAnnotations: MutableList<UrlAnnotation> = mutableListOf(),
  var currentStyle: SpanStyle = SpanStyle(),
  var chipCounter: Int = 0,
  var imageAltCounter: Int = 0,
) {
  /** Snapshot + restore helper for the recursive walker. */
  fun <T> withStyle(replacementStyle: SpanStyle, lambdaBlock: () -> T): T {
    val savedStyle: SpanStyle = currentStyle
    currentStyle = replacementStyle
    return try {
      lambdaBlock()
    } finally {
      currentStyle = savedStyle
    }
  }

  /** Consume a fresh chip placeholder + register the chip in `inlineContent`. */
  fun allocateChip(codeText: String) {
    val placeholderKey: String = makePlaceholder(chipCounter)
    chipCounter += 1
    val chipWidth: Float = fontSizeSp * cjkAwareWidthRatio(codeText) + PLACEHOLDER_WIDTH_PADDING_SP
    val chipHeight: Float = fontSizeSp * PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + PLACEHOLDER_WIDTH_PADDING_SP
    val placeholderShape = Placeholder(
      width = chipWidth.sp,
      height = chipHeight.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
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
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
    )
    inlineContent[placeholder] = InlineTextContent(placeholder = placeholderShape) {
      org.jetbrains.jewel.ui.component.Icon(
        key = gradum.idea.icons.GradumIcons.Image,
        contentDescription = null,
        modifier = Modifier.size(iconSize.dp),
      )
    }
    return placeholder
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
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val children: NodeChildren = NodeChildren.of(parentNode)
  val first: Node? = children.first
  if (first != null) renderInlineNode(first, builder, renderState)
  for (childNode in children.rest) renderInlineNode(childNode, builder, renderState)
}


/** Dispatch a single inline node. Unknown / extension nodes recurse into children. */
private fun renderInlineNode(
  node: Node,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  when (node) {
    is Text -> renderTextInline(node, builder, renderState)
    is Emphasis -> renderEmphasisInline(node, builder, renderState)
    is StrongEmphasis -> renderStrongEmphasisInline(node, builder, renderState)
    is Code -> renderCodeInline(node, builder, renderState)
    is Link -> renderLinkInline(node, builder, renderState)
    is Image -> renderImageInline(node, builder, renderState)
    is SoftLineBreak -> builder.withStyle(renderState.currentStyle) { append(' ') }
    is HardLineBreak -> builder.withStyle(renderState.currentStyle) { append('\n') }
    is Strikethrough -> renderStrikethroughInline(node, builder, renderState)
    else -> {
      if (node.firstChild != null) renderInlineChildren(node, builder, renderState)
    }
  }
}


private fun renderTextInline(
  node: Text,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val literal: String = node.literal.orEmpty()
  if (literal.isEmpty()) return
  if (renderState.currentStyle == SpanStyle()) {
    builder.append(literal)
  } else {
    builder.withStyle(renderState.currentStyle) { append(literal) }
  }
}

private fun renderEmphasisInline(
  node: Emphasis,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val italicStyle: SpanStyle = renderState.currentStyle.copy(fontStyle = FontStyle.Italic)
  renderState.withStyle(italicStyle) { renderInlineChildren(node, builder, renderState) }
}

private fun renderStrongEmphasisInline(
  node: StrongEmphasis,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val boldStyle: SpanStyle = renderState.currentStyle.copy(fontWeight = FontWeight.Bold)
  renderState.withStyle(boldStyle) { renderInlineChildren(node, builder, renderState) }
}

private fun renderCodeInline(
  node: Code,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val codeText: String = node.literal.orEmpty()
  if (codeText.isEmpty()) return
  val placeholderKey: String = makePlaceholder(renderState.chipCounter)
  val placeholderStart: Int = builder.length

  builder.pushStringAnnotation(tag = INLINE_CODE_TEXT_TAG, annotation = codeText)
  builder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = placeholderKey)
  builder.pushStyle(SpanStyle())
  builder.append(placeholderKey)
  builder.pop()
  builder.pop()
  builder.pop()
  val placeholderEnd: Int = builder.length
  check(placeholderEnd - placeholderStart == placeholderKey.length) {
    "Inline-code placeholder length changed under inline-content push; " +
      "expected ${placeholderKey.length} chars, got ${placeholderEnd - placeholderStart}"
  }
  renderState.allocateChip(codeText)
}

private fun renderLinkInline(
  node: Link,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  // The chat's link rule is "color + permanent underline" — see
  // [linkStateStyles] KDoc for why we abandoned the v1
  // "color-only at rest, underline on hover" rule. The walker
  // emits an `Underline` SpanStyle here so the link paints
  // stable even on code paths that don't go through
  // [ExternalLink] (e.g. the legacy `UrlAnnotation` +
  // `pointerInput` path on a plain `Text`, or the
  // `Markdown(...)` fallback when the upstream split bails).
  // The parent [SpanStyle.textDecoration] is COMPOSED with the
  // link's underline via [combineDecoration] so a [Strikethrough]
  // wrapper still paints both: `~~[text](url)~~` →
  // `Underline + LineThrough`, not just one or the other.
  val linkStyle: SpanStyle = renderState.currentStyle.copy(
    color = renderState.linkColor,
    textDecoration = combineDecoration(
      renderState.currentStyle.textDecoration ?: TextDecoration.None,
      TextDecoration.Underline,
    ),
  )
  val linkUrl: String = node.destination.orEmpty()
  val linkTextStart: Int = builder.length
  builder.pushStringAnnotation(tag = INLINE_URL_TAG, annotation = linkUrl)
  renderState.withStyle(linkStyle) { renderInlineChildren(node, builder, renderState) }
  builder.pop()
  val linkTextEnd: Int = builder.length
  if (linkUrl.isNotEmpty() && linkTextEnd > linkTextStart) {
    renderState.urlAnnotations += UrlAnnotation(start = linkTextStart, end = linkTextEnd, url = linkUrl)
  }
}

private fun renderImageInline(
  node: Image,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  // An inline `![alt](url)` image in a chat message is a *placeholder*,
  // not a clickable target — the chat doesn't render the binary, so
  // the alt text is a description ("there's a picture here"), not a
  // link to a destination. The previous v1.5 path registered the
  // image's URL as a `UrlAnnotation`, which made the alt text render
  // as `ExternalLink("Image alt text", url=imageUrl)` — visually
  // indistinguishable from a regular link, which surprised the user
  // when their message mixed images and links (e.g. the
  // `![alt](imageUrl) <https://example.com> <email@example.com>`
  // case in the feedback, where the whole thing read as one blue
  // link). Render the alt text as italic muted-gray with a "🖼"
  // prefix so it's clearly a placeholder, NOT a link. The image's
  // `destination` is dropped (we have nowhere to send the user for
  // a binary that isn't shown).
  val imageAltStyle: SpanStyle = renderState.currentStyle.copy(
    fontStyle = FontStyle.Italic,
    color = renderState.imageAltColor,
    textDecoration = renderState.currentStyle.textDecoration ?: TextDecoration.None,
  )
  val iconPlaceholder: String = renderState.allocateImageAlt()
  renderState.withStyle(imageAltStyle) {
    builder.append(iconPlaceholder)
    builder.append(' ')
    renderInlineChildren(node, builder, renderState)
  }
}

private fun renderStrikethroughInline(
  node: Strikethrough,
  builder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val strikethroughStyle: SpanStyle = renderState.currentStyle.copy(
    textDecoration = combineDecoration((renderState.currentStyle.textDecoration ?: TextDecoration.None), TextDecoration.LineThrough)
  )
  renderState.withStyle(strikethroughStyle) { renderInlineChildren(node, builder, renderState) }
}


/** Compose two [TextDecoration] values: underline + line-through can both paint. Other: new wins. */
private fun combineDecoration(
  existing: TextDecoration,
  added: TextDecoration,
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
  annotated: AnnotatedString,
  inlineContent: Map<String, InlineTextContent>,
  urlAnnotations: List<UrlAnnotation>,
): List<InlineSegment> {
  if (urlAnnotations.isEmpty()) {
    return listOf(InlineSegment.TextSegment(annotated, inlineContent))
  }
  val sortedUrls: List<UrlAnnotation> = urlAnnotations.sortedBy { it.start }
  val segments: MutableList<InlineSegment> = mutableListOf()
  var cursor = 0
  for (urlAnnotation in sortedUrls) {
    if (urlAnnotation.start > cursor) {
      segments += InlineSegment.TextSegment(
        annotated = annotated.subSequence(cursor, urlAnnotation.start),
        inlineContent = inlineContent,
      )
    }
    val linkTextContent: CharSequence = annotated.subSequence(urlAnnotation.start, urlAnnotation.end)
    val linkText: String = stripInlineLinkText(linkTextContent)
    segments += InlineSegment.LinkSegment(text = linkText, url = urlAnnotation.url)
    cursor = urlAnnotation.end
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
 * Drop PUA placeholders from a link's text range — code chips that
 * happen to be inside a link's label are rendered through the prose
 * path, not the link path. `AnnotatedString.toString()` already
 * preserves plain text content; we just need to filter out the PUA
 * chars. The link range was wrapped in a `pushStringAnnotation` for
 * the link's URL, but that's metadata, not visible text.
 */
private fun stripInlineLinkText(range: CharSequence): String {
  if (range.none { it == INLINE_CODE_PLACEHOLDER }) return range.toString()
  val output: StringBuilder = StringBuilder(range.length)
  for (char in range) {
    if (char != INLINE_CODE_PLACEHOLDER) output.append(char)
  }
  return output.toString()
}


/**
 * The actual chip composable. Uses `JewelTheme.linkStyle`'s `content` color for a
 * vivid tint across themes; the chip's internal `Text` sets `lineHeight = fontSizeSp.sp`
 * (1.0x) so the editor's 1.5x line height doesn't clip the glyphs.
 */
@Composable
private fun InlineCodeChip(
  text: String,
  fontSizeSp: Float,
) {
  val editorStyle: TextStyle = JewelTheme.editorTextStyle
  val badgeColor: Color = JewelTheme.linkStyle.colors.content
  val chipStyle = TextStyle(
    fontFamily = editorStyle.fontFamily,
    fontSize = fontSizeSp.sp,
    lineHeight = fontSizeSp.sp,
    fontWeight = FontWeight.Medium,
    color = badgeColor,
  )
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(GradumSpacing.sm))
      .background(badgeColor.copy(alpha = INLINE_CODE_BACKGROUND_ALPHA))
      .padding(
        horizontal = InlineCodePaddingHorizontal,
        vertical = InlineCodePaddingVertical,
      )
  ) {
    Text(
      text = text,
      style = chipStyle,
      color = badgeColor,
      maxLines = 1,
      softWrap = false,
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
