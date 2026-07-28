/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LatexThemeColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private const val BLOCK_LATEX_DEFAULT_FONT_SIZE_SP: Float = 18f

// Vertical padding around block formulas. Bumped from 32dp to 48dp per user feedback
// "blocking text below" — the library's Canvas can be 100-150dp tall for multi-line formulas.
// Library internal padding (CANVAS_VERTICAL_PADDING=0.10f, ~1.8dp) is baked into Canvas size,
// not user-configurable. Async parsing (first frame 0×0 → parsed height) causes a layout shift;
// heightIn(min=...) in RenderLatexBlock reserves BLOCK_LATEX_MIN_CONTENT_HEIGHT_DP to stabilize it.
private const val BLOCK_LATEX_VERTICAL_PADDING_DP: Float = 48f

// Minimum inner content height the Box reserves for the formula. 44dp fits a single \frac{}{}
// without making small formulas look floating. See also BLOCK_LATEX_VERTICAL_PADDING_DP.
private const val BLOCK_LATEX_MIN_CONTENT_HEIGHT_DP: Float = 44f
private const val FALLBACK_TEXT_VERTICAL_PADDING_DP: Float = 2f
private const val FALLBACK_FONT_SIZE_SP: Float = 13f
private const val FALLBACK_FONT_WEIGHT: Int = 500

/**
 * Render a block-level LaTeX formula (`$$…$$`). Centered, padded vertically.
 * Falls back to monospace raw text if the library throws.
 *
 * Alignment: `TopCenter` — overflow extends downward into bottom padding.
 * `Center` horizontally centers the formula within the panel.
 * Sizing: outer `Box` is `fillMaxWidth`, inner `Latex` sizes to content (no fillMaxWidth).
 * Layout-shift defense: `heightIn(min=...)` reserves 44dp for the formula so the Box
 * stays stable on the first frame (library async-parses, first frame is 0×0).
 * Vertical padding: 48dp each side (see [BLOCK_LATEX_VERTICAL_PADDING_DP]).
 */
@Composable
fun RenderLatexBlock(formula: String, modifier: Modifier = Modifier) {
  val renderState: LatexRenderState = rememberLatexRenderState(formula = formula)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = BLOCK_LATEX_MIN_CONTENT_HEIGHT_DP.dp + BLOCK_LATEX_VERTICAL_PADDING_DP.dp * 2)
      .padding(vertical = BLOCK_LATEX_VERTICAL_PADDING_DP.dp),
    contentAlignment = Alignment.TopCenter
  ) {
    if (renderState.shouldFallback) {
      LatexFallbackText(
        isBlock = true,
        formula = formula,
        modifier = Modifier.fillMaxWidth()
      )
    } else {
      Latex(
        latex = formula,
        isDarkTheme = isSystemInDarkTheme(),
        config = renderState.config.copy(fontSize = BLOCK_LATEX_DEFAULT_FONT_SIZE_SP.sp)
      )
    }
  }
}

/**
 * Render an inline LaTeX formula (`$…$`). Sized to match the
 * surrounding text so it sits on the same baseline. Used by
 * [RenderState.allocateLatex] in InlineMarkdown for the PUA
 * placeholder path.
 */
@Composable
internal fun RenderInlineLatex(
  formula: String, fontSizeSp: Float, fontFamily: FontFamily?
) {
  val renderState: LatexRenderState = rememberLatexRenderState(formula = formula)
  if (renderState.shouldFallback) {
    LatexFallbackText(
      formula = formula,
      isBlock = false,
      fontFamily = fontFamily,
      fontSizeSp = fontSizeSp
    )
  } else {
    Latex(
      latex = formula,
      config = renderState.config.copy(fontSize = fontSizeSp.sp),
      isDarkTheme = isSystemInDarkTheme()
    )
  }
}

/** Cached per-formula render decisions. */
@Composable
private fun rememberLatexRenderState(formula: String): LatexRenderState {
  val globalColors = LocalGlobalColors.current
  val baseColor: Color = JewelTheme.contentColor
  val config: LatexConfig = remember(formula, baseColor, globalColors) {
    buildAdaptiveLatexConfig(baseColor = baseColor)
  }
  return remember(formula, config) {
    LatexRenderState(
      config = config,
      shouldFallback = formula.isBlank()
    )
  }
}

/** Bundle of cached per-formula render decisions. */
internal data class LatexRenderState(
  val config: LatexConfig,
  val shouldFallback: Boolean,
)

/**
 * Build a [LatexConfig] with the IDE's text color for both light/dark (the chat panel's text
 * color is the same in both modes — IntelliJ inverts background, not text).
 * Background is [Color.Transparent] to avoid a "chip-like" rectangle behind formulas.
 */
private fun buildAdaptiveLatexConfig(baseColor: Color): LatexConfig {
  val colors = LatexThemeColors(
    color = baseColor,
    backgroundColor = Color.Transparent
  )
  val theme: LatexTheme = LatexTheme.auto(light = colors, dark = colors)
  return LatexConfig(theme = theme)
}

/**
 * Fallback rendering for a formula the library can't draw. Shows the original Markdown source
 * (with `$$…$$` / `$…$` wrapping) as monospace text in a muted color.
 */
@Composable
private fun LatexFallbackText(
  formula: String,
  isBlock: Boolean,
  modifier: Modifier = Modifier,
  fontFamily: FontFamily? = null,
  fontSizeSp: Float = FALLBACK_FONT_SIZE_SP
) {
  val globalColors = LocalGlobalColors.current
  val wrapped: String = if (isBlock) {
    "$$ ${formula.trim()} $$"
  } else {
    "$${formula}$"
  }
  val fallbackStyle = TextStyle(
    fontFamily = fontFamily,
    fontSize = fontSizeSp.sp,
    fontStyle = FontStyle.Italic,
    color = globalColors.text.info,
    fontWeight = FontWeight(FALLBACK_FONT_WEIGHT)
  )
  Box(
    modifier = modifier
      .padding(vertical = FALLBACK_TEXT_VERTICAL_PADDING_DP.dp)
  ) {
    Text(text = wrapped, style = fallbackStyle, modifier = Modifier.fillMaxWidth())
  }
}
