/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumLinkStylingTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for `gradumInlinesStyling` — the single source of truth for
 * the chat's `Markdown(...)` fallback path's per-scanState link colors
 * and inline emphasis.
 *
 * The v1 implementation kept a hand-rolled `LinkStateStyles` data
 * class with 6 SpanStyle fields; the v2 replacement (LANDED
 * 2026-07-15, after reading Jewel 0.37.0's `LinkColors` source)
 * pulls link colors directly from `JewelTheme.linkStyle.colors` —
 * the official Jewel API already provides 6 scanState-aware Color
 * fields (`content` / `contentDisabled` / `contentFocused` /
 * `contentHovered` / `contentPressed` / `contentVisited`). The
 * hand-rolled re-implementation duplicated them and added a layer
 * to drift against the theme. These tests pin the wiring between
 * the chat's `InlinesStyling` and `LinkColors`:
 *
 *  - Each `LinkColors.*` field lands in the corresponding
 *    `InlinesStyling.*` field as a `SpanStyle` with that color.
 *  - The 6 link states preserve their distinct colors (a future
 *    tweak to one — e.g. visited = desaturated — stays visible).
 *  - `inlineCode` is passed through unchanged.
 *  - `emphasis` and `strongEmphasis` are baked-in italic / bold.
 */
@OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)
class GradumLinkStylingTest {

  private val linkColors: org.jetbrains.jewel.ui.component.styling.LinkColors =
    org.jetbrains.jewel.ui.component.styling.LinkColors(
      content = Color(0xFF3366CC),
      contentDisabled = Color(0xFF999999),
      contentFocused = Color(0xFF224488),
      contentHovered = Color(0xFF4477DD),
      contentPressed = Color(0xFF112266),
      contentVisited = Color(0xFF663399),
    )

  @Test
  fun `gradumInlinesStyling wires each LinkColors state into the right InlinesStyling slot`() {
    val inlineCodeStyle = SpanStyle(background = Color(0xFFEEEEEE))
    val styling = gradumInlinesStyling(
      textStyle = TextStyle(),
      inlineCodeStyle = inlineCodeStyle,
      linkColors = linkColors,
    )
    assertEquals(
      SpanStyle(color = linkColors.content),
      styling.link
    )
    assertEquals(
      SpanStyle(color = linkColors.contentDisabled),
      styling.linkDisabled
    )
    assertEquals(
      SpanStyle(color = linkColors.contentFocused),
      styling.linkFocused
    )
    assertEquals(
      SpanStyle(color = linkColors.contentHovered),
      styling.linkHovered
    )
    assertEquals(
      SpanStyle(color = linkColors.contentPressed),
      styling.linkPressed
    )
    assertEquals(
      SpanStyle(color = linkColors.contentVisited),
      styling.linkVisited
    )
  }

  @Test
  fun `gradumInlinesStyling preserves distinct link state colors`() {
    // Sanity: a future theme tweak that intentionally desaturates
    // `contentVisited` (for example) must NOT get collapsed to
    // `content` by the helper. The 6 states keep their
    // individuality in the InlinesStyling output.
    val styling = gradumInlinesStyling(
      textStyle = TextStyle(),
      inlineCodeStyle = SpanStyle(),
      linkColors = linkColors,
    )
    val distinctColors: Set<Color> = setOf(
      styling.link.color,
      styling.linkDisabled.color,
      styling.linkFocused.color,
      styling.linkHovered.color,
      styling.linkPressed.color,
      styling.linkVisited.color,
    )
    assertEquals(
      "all 6 link scanState colors should be preserved as distinct values",
      6,
      distinctColors.size,
    )
  }

  @Test
  fun `gradumInlinesStyling passes through the inline code style as-is`() {
    val inlineCodeStyle = SpanStyle(background = Color(0xFFEEEEEE))
    val styling = gradumInlinesStyling(
      textStyle = TextStyle(),
      inlineCodeStyle = inlineCodeStyle,
      linkColors = linkColors,
    )
    assertEquals(
      inlineCodeStyle,
      styling.inlineCode
    )
  }

  @Test
  fun `gradumInlinesStyling bakes in italic emphasis and bold strong emphasis`() {
    val styling = gradumInlinesStyling(
      textStyle = TextStyle(),
      inlineCodeStyle = SpanStyle(),
      linkColors = linkColors,
    )
    assertNotNull("emphasis must define a non-empty span", styling.emphasis)
    assertNotNull("strong emphasis must define a non-empty span", styling.strongEmphasis)
    // Italic and Bold weights differ from the base — sanity check
    // that the helper didn't collapse them to an empty SpanStyle.
    assertNotEquals(SpanStyle(), styling.emphasis)
    assertNotEquals(SpanStyle(), styling.strongEmphasis)
    assertTrue(
      "emphasis should set FontStyle.Italic",
      styling.emphasis.fontStyle == FontStyle.Italic,
    )
    assertTrue(
      "strong emphasis should set FontWeight.SemiBold",
      styling.strongEmphasis.fontWeight == FontWeight.SemiBold,
    )
  }
}
