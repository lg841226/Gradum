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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.ui.GradumSpacing
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
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
private val InlineCodePaddingHorizontal = 2.dp
private val InlineCodePaddingVertical = 0.dp
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
  val linkColor: Color = resolveLinkColor()
  val fontSizeSp: Float = resolveEditorFontSizeSp()
  return remember(plainText) {
    parseInlineMarkdown(
      plainText = plainText,
      fontSizeSp = fontSizeSp,
      chipTint = chipTint,
      linkColor = linkColor,
    )
  }
}


/** Pure CommonMark → `AnnotatedString` walk. Theme values passed in by the caller. */
internal fun parseInlineMarkdown(
  plainText: String,
  fontSizeSp: Float,
  chipTint: Color,
  linkColor: Color,
): InlineMarkdownRenderResult {
  if (plainText.isBlank()) return bailNoRender()

  val document: Document = parseCommonmarkDocument(plainText) ?: return bailWithReason(
    reason = "commonmark 解析异常: 详见 IDE log",
    plainText = plainText,
  )
  val children: NodeChildren = NodeChildren.of(document)
  val topBlocks: List<Node> = buildList {
    if (children.first != null) add(children.first!!)
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


private fun bailNoRender(): InlineMarkdownRenderResult =
  InlineMarkdownRenderResult(render = null, bailReason = null)


private fun bailWithReason(
  reason: String,
  plainText: String,
): InlineMarkdownRenderResult {
  val preview: String = plainText.take(BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
  log.warn("Inline markdown bailed: $reason (text=$preview)")
  return InlineMarkdownRenderResult(render = null, bailReason = reason)
}


/** Walk the paragraph-only AST and build the [InlineMarkdownRender]. Catches walker exceptions as bail. */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
private fun buildInlineRender(
  plainText: String,
  topBlocks: List<Node>,
  fontSizeSp: Float,
  chipTint: Color,
  linkColor: Color,
): InlineMarkdownRenderResult {
  return try {
    val renderState: RenderState = RenderState(
      fontSizeSp = fontSizeSp,
      chipTint = chipTint,
      linkColor = linkColor,
    )
    val preview: String = plainText.take(BAIL_REASON_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.debug("InlineMarkdown: parse start — text.length=${plainText.length}, fontSizeSp=$fontSizeSp, chipTint=$chipTint, text=$preview")
    val annotatedString: AnnotatedString = buildAnnotatedString {
      val annotatedBuilder: AnnotatedString.Builder = this
      topBlocks.forEachIndexed { blockIndex, blockNode ->
        if (blockIndex > 0) annotatedBuilder.append("\n\n")
        renderInlineChildren(blockNode as Paragraph, annotatedBuilder, renderState)
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
  } catch (walkException: Exception) {
    val reason: String = "AST walker 异常: ${walkException.javaClass.simpleName}: ${walkException.message}"
    val preview: String = plainText.take(WALK_FAILURE_LOG_PREVIEW_CHARS).replace("\n", " ")
    log.warn("Inline markdown AST walk failed for text: $preview", walkException)
    InlineMarkdownRenderResult(render = null, bailReason = reason)
  }
}


/** Mutable state threaded through the recursive walker. UI-thread-only by design (Compose `remember`). */
@Suppress("LongParameterList")
private class RenderState(
  val fontSizeSp: Float,
  val chipTint: Color,
  val linkColor: Color,
  val inlineContent: MutableMap<String, InlineTextContent> = mutableMapOf(),
  val urlAnnotations: MutableList<UrlAnnotation> = mutableListOf(),
  var currentStyle: SpanStyle = SpanStyle(),
  var chipCounter: Int = 0,
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
    val chipPlaceholder: String = makePlaceholder(chipCounter)
    chipCounter += 1
    val chipWidthSp: Float = fontSizeSp * cjkAwareWidthRatio(codeText) + PLACEHOLDER_WIDTH_PADDING_SP
    val chipHeightSp: Float = fontSizeSp * PLACEHOLDER_LINE_HEIGHT_MULTIPLIER + PLACEHOLDER_WIDTH_PADDING_SP
    val placeholderShape: Placeholder = Placeholder(
      width = chipWidthSp.sp,
      height = chipHeightSp.sp,
      placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
    )
    inlineContent[chipPlaceholder] = InlineTextContent(placeholder = placeholderShape) {
      InlineCodeChip(text = codeText, fontSizeSp = fontSizeSp)
    }
  }
}


/** Per-character width sum: [MONOSPACE_CJK_RATIO] for CJK, [MONOSPACE_LATIN_RATIO] for everything else. */
internal fun cjkAwareWidthRatio(codeText: String): Float {
  if (codeText.isEmpty()) return 0f
  var ratioSum: Float = 0f
  for (char in codeText) ratioSum += if (isCjkChar(char)) MONOSPACE_CJK_RATIO else MONOSPACE_LATIN_RATIO
  return ratioSum
}


private fun renderInlineChildren(
  parentNode: Node,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val children: NodeChildren = NodeChildren.of(parentNode)
  val first: Node? = children.first
  if (first != null) renderInlineNode(first, annotatedBuilder, renderState)
  for (childNode in children.rest) renderInlineNode(childNode, annotatedBuilder, renderState)
}


/** Dispatch a single inline node. Unknown / extension nodes recurse into children. */
private fun renderInlineNode(
  currentNode: Node,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  when (currentNode) {
    is Text -> renderTextInline(currentNode, annotatedBuilder, renderState)
    is Emphasis -> renderEmphasisInline(currentNode, annotatedBuilder, renderState)
    is StrongEmphasis -> renderStrongEmphasisInline(currentNode, annotatedBuilder, renderState)
    is Code -> renderCodeInline(currentNode, annotatedBuilder, renderState)
    is Link -> renderLinkInline(currentNode, annotatedBuilder, renderState)
    is Image -> renderImageInline(currentNode, annotatedBuilder, renderState)
    is SoftLineBreak -> renderSoftLineBreakInline(annotatedBuilder, renderState)
    is HardLineBreak -> renderHardLineBreakInline(annotatedBuilder, renderState)
    is HtmlInline -> renderHtmlInlineInline(currentNode, annotatedBuilder, renderState)
    is Strikethrough -> renderStrikethroughInline(currentNode, annotatedBuilder, renderState)
    else -> renderUnknownInline(currentNode, annotatedBuilder, renderState)
  }
}


private fun renderTextInline(
  currentNode: Text,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val literalText: String = currentNode.literal.orEmpty()
  if (literalText.isEmpty()) return
  if (renderState.currentStyle == SpanStyle()) {
    annotatedBuilder.append(literalText)
  } else {
    annotatedBuilder.withStyle(renderState.currentStyle) { append(literalText) }
  }
}

private fun renderEmphasisInline(
  currentNode: Emphasis,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val italicStyle: SpanStyle = renderState.currentStyle.copy(fontStyle = FontStyle.Italic)
  renderState.withStyle(italicStyle) { renderInlineChildren(currentNode, annotatedBuilder, renderState) }
}

private fun renderStrongEmphasisInline(
  currentNode: StrongEmphasis,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val boldStyle: SpanStyle = renderState.currentStyle.copy(fontWeight = FontWeight.Bold)
  renderState.withStyle(boldStyle) { renderInlineChildren(currentNode, annotatedBuilder, renderState) }
}

private fun renderCodeInline(
  currentNode: Code,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val codeText: String = currentNode.literal.orEmpty()
  if (codeText.isEmpty()) return
  val chipPlaceholder: String = makePlaceholder(renderState.chipCounter)
  val placeholderStart: Int = annotatedBuilder.length

  annotatedBuilder.pushStringAnnotation(tag = INLINE_CODE_TEXT_TAG, annotation = codeText)
  annotatedBuilder.pushStringAnnotation(tag = INLINE_CONTENT_TAG, annotation = chipPlaceholder)
  annotatedBuilder.pushStyle(SpanStyle())
  annotatedBuilder.append(chipPlaceholder)
  annotatedBuilder.pop()
  annotatedBuilder.pop()
  annotatedBuilder.pop()
  val placeholderEnd: Int = annotatedBuilder.length
  check(placeholderEnd - placeholderStart == chipPlaceholder.length) {
    "Inline-code placeholder length changed under inline-content push; " +
      "expected ${chipPlaceholder.length} chars, got ${placeholderEnd - placeholderStart}"
  }
  renderState.allocateChip(codeText)
}

private fun renderLinkInline(
  currentNode: Link,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val linkStyle: SpanStyle = renderState.currentStyle.copy(
    color = renderState.linkColor,
    textDecoration = combineDecoration(
      renderState.currentStyle.textDecoration ?: TextDecoration.None,
      TextDecoration.Underline,
    ),
  )
  val linkUrl: String = currentNode.destination.orEmpty()
  val linkStart: Int = annotatedBuilder.length
  annotatedBuilder.pushStringAnnotation(tag = INLINE_URL_TAG, annotation = linkUrl)
  renderState.withStyle(linkStyle) { renderInlineChildren(currentNode, annotatedBuilder, renderState) }
  annotatedBuilder.pop()
  val linkEnd: Int = annotatedBuilder.length
  if (linkUrl.isNotEmpty() && linkEnd > linkStart) {
    renderState.urlAnnotations += UrlAnnotation(start = linkStart, end = linkEnd, url = linkUrl)
  }
}

private fun renderImageInline(
  currentNode: Image,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val linkUrl: String = currentNode.destination.orEmpty()
  val imageLinkStyle: SpanStyle = renderState.currentStyle.copy(
    color = renderState.linkColor,
    textDecoration = combineDecoration(
      renderState.currentStyle.textDecoration ?: TextDecoration.None,
      TextDecoration.Underline,
    ),
  )
  val imageStart: Int = annotatedBuilder.length
  annotatedBuilder.pushStringAnnotation(tag = INLINE_URL_TAG, annotation = linkUrl)
  renderState.withStyle(imageLinkStyle) { renderInlineChildren(currentNode, annotatedBuilder, renderState) }
  annotatedBuilder.pop()
  val imageEnd: Int = annotatedBuilder.length
  if (linkUrl.isNotEmpty() && imageEnd > imageStart) {
    renderState.urlAnnotations += UrlAnnotation(start = imageStart, end = imageEnd, url = linkUrl)
  }
}

private fun renderSoftLineBreakInline(
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) = annotatedBuilder.withStyle(renderState.currentStyle) { append(' ') }

private fun renderHardLineBreakInline(
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) = annotatedBuilder.withStyle(renderState.currentStyle) { append('\n') }

private fun renderHtmlInlineInline(
  currentNode: HtmlInline,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val literalText: String = currentNode.literal.orEmpty()
  if (literalText.isNotEmpty()) annotatedBuilder.withStyle(renderState.currentStyle) { append(literalText) }
}

private fun renderStrikethroughInline(
  currentNode: Strikethrough,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  val strikethroughStyle: SpanStyle = renderState.currentStyle.copy(
    textDecoration = (renderState.currentStyle.textDecoration ?: TextDecoration.None)
      .let { existingDecoration -> combineDecoration(existingDecoration, TextDecoration.LineThrough) }
  )
  renderState.withStyle(strikethroughStyle) { renderInlineChildren(currentNode, annotatedBuilder, renderState) }
}


/** Compose two [TextDecoration] values: underline + line-through can both paint. Other: new wins. */
private fun combineDecoration(
  existingDecoration: TextDecoration,
  addedDecoration: TextDecoration,
): TextDecoration {
  val hasUnderline: Boolean = existingDecoration.contains(TextDecoration.Underline) || addedDecoration.contains(TextDecoration.Underline)
  val hasLineThrough: Boolean = existingDecoration.contains(TextDecoration.LineThrough) || addedDecoration.contains(TextDecoration.LineThrough)
  return when {
    hasUnderline && hasLineThrough -> TextDecoration.Underline + TextDecoration.LineThrough
    hasUnderline -> TextDecoration.Underline
    hasLineThrough -> TextDecoration.LineThrough
    else -> TextDecoration.None
  }
}

private fun renderUnknownInline(
  currentNode: Node,
  annotatedBuilder: AnnotatedString.Builder,
  renderState: RenderState,
) {
  if (currentNode.firstChild != null) renderInlineChildren(currentNode, annotatedBuilder, renderState)
}


/** Build a unique PUA placeholder substring for the N-th code span — adjacent chips never collide. */
private fun makePlaceholder(chipIndex: Int): String =
  INLINE_CODE_PLACEHOLDER.toString().repeat(chipIndex + 1)


/**
 * The actual chip composable. Uses [JewelTheme.linkStyle]'s `content` color for a
 * vivid tint across themes; the chip's internal `Text` sets `lineHeight = fontSizeSp.sp`
 * (1.0x) so the editor's 1.5x line height doesn't clip the glyphs.
 */
@Composable
private fun InlineCodeChip(
  text: String,
  fontSizeSp: Float,
) {
  val editorStyle: TextStyle = JewelTheme.editorTextStyle
  val chipFontSizeSp: Float = fontSizeSp
  val badgeColor: Color = JewelTheme.linkStyle.colors.content
  val chipStyle: TextStyle = TextStyle(
    fontFamily = editorStyle.fontFamily,
    fontSize = chipFontSizeSp.sp,
    lineHeight = chipFontSizeSp.sp,
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
 * Chip tint from [JewelTheme.linkStyle] (solid `Color` — not the badge's transparent
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

/** Link color from Jewel theme. */
@Composable
private fun resolveLinkColor(): Color = JewelTheme.linkStyle.colors.content

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
