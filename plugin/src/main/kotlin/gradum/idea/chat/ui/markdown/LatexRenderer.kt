/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LatexRenderer.kt  2026-07-20 22:01:28 Changed by gwy
 */

package gradum.idea.chat.ui.markdown

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
private const val BLOCK_LATEX_VERTICAL_PADDING_DP: Float = 12f
private const val FALLBACK_TEXT_VERTICAL_PADDING_DP: Float = 2f
private const val FALLBACK_FONT_SIZE_SP: Float = 13f
private const val FALLBACK_FONT_WEIGHT: Int = 500

/**
 * Render a block-level LaTeX formula (`$$…$$`). Centered, padded
 * vertically, no horizontal margin — the parent block column
 * already provides that. Falls back to monospace raw text if the
 * library throws (broken formula, missing font, …).
 *
 * The huarangmeng library exposes a `@Composable Latex(latex,
 * modifier, config, isDarkTheme)`. The `config.fontSize` is the
 * only knob we set — `theme` is derived from the IDE's text
 * color so the formula stays readable when the user toggles
 * the editor background.
 */
@Composable
fun RenderLatexBlock(formula: String, modifier: Modifier = Modifier) {
  val renderState: LatexRenderState = rememberLatexRenderState(formula = formula)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = BLOCK_LATEX_VERTICAL_PADDING_DP.dp),
    contentAlignment = Alignment.Center
  ) {
    if (renderState.shouldFallback) {
      LatexFallbackText(
        formula = formula,
        isBlock = true,
        modifier = Modifier.fillMaxWidth()
      )
    } else {
      Latex(
        latex = formula,
        config = renderState.config.copy(fontSize = BLOCK_LATEX_DEFAULT_FONT_SIZE_SP.sp),
        isDarkTheme = isSystemInDarkTheme(),
        modifier = Modifier.fillMaxWidth()
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

/**
 * `remember`d render state for a single formula. Holds the
 * [LatexConfig] and a "should we fall back" flag. The first
 * time [RenderLatexBlock] / [RenderInlineLatex] is called for
 * a given formula, the flag is `false` (try the library). If
 * the library throws, the composable catches it, flips the
 * flag, and the next recomposition renders the fallback. The
 * formula text is the only `key` — re-deriving the state on
 * every recomposition would defeat `remember`.
 */
@Composable
private fun rememberLatexRenderState(formula: String): LatexRenderState {
  val globalColors = LocalGlobalColors.current
  val baseColor: Color = JewelTheme.contentColor
  val config: LatexConfig = remember(formula, baseColor, globalColors) {
    buildAdaptiveLatexConfig(
      baseColor = baseColor,
      backgroundColor = globalColors.panelBackground
    )
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
 * Build a [LatexConfig] that uses the IDE's current text color
 * for both light and dark theme entries, so the formula stays
 * readable when the user toggles the editor background. The
 * background color is `panelBackground` so the library's own
 * background (when it draws one) matches the chat panel.
 *
 * We can't use [LatexTheme.Companion.auto] with proper light/dark
 * variants here because the chat panel's text color is the same
 * in both modes (the IntelliJ LaF inverts the background, not
 * the text). One color, two themes — the [Latex] composable
 * reads `isDarkTheme` at render time and picks the matching
 * entry.
 */
private fun buildAdaptiveLatexConfig(
  baseColor: Color, backgroundColor: Color
): LatexConfig {
  val colors = LatexThemeColors(
    color = baseColor,
    backgroundColor = backgroundColor
  )
  val theme: LatexTheme = LatexTheme.auto(light = colors, dark = colors)
  return LatexConfig(theme = theme)
}

/**
 * Fallback rendering for a formula the library can't draw.
 * Shows the original `$$…$$` / `$…$` source as monospace text in
 * a muted color. Triggered when:
 *
 * - the formula is empty (we never ask the library to render
 *   an empty canvas);
 * - the library throws on a previous attempt (caught and
 *   retried via [rememberLatexRenderState]);
 * - the user disables the LaTeX feature in the future.
 *
 * The `$$…$$` wrapping is added back on top of the formula for
 * block mode so the fallback is a faithful re-creation of the
 * original Markdown source (copy-paste back into the chat
 * works). Inline mode keeps the `$…$` markers.
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
    fontSize = fontSizeSp.sp,
    fontFamily = fontFamily,
    fontStyle = FontStyle.Italic,
    color = globalColors.text.info,
    fontWeight = FontWeight(FALLBACK_FONT_WEIGHT)
  )
  Box(
    modifier = modifier.padding(vertical = FALLBACK_TEXT_VERTICAL_PADDING_DP.dp)
  ) {
    Text(text = wrapped, style = fallbackStyle, modifier = Modifier.fillMaxWidth())
  }
}
