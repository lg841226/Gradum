/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AppearanceProvider.kt  2026-08-20 11:12:00 Changed by gwy
 */

package gradum.idea.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gradum.idea.utils.GradumSpacing

/**
 * Current paragraph density, provided by [ProvideAppearance].
 * Maps the density selection to concrete vertical spacing.
 */
val LocalParagraphSpacing = staticCompositionLocalOf { GradumSpacing.md }

/**
 * Current assistant body font size in sp, provided by [ProvideAppearance].
 */
val LocalParagraphFontSize = staticCompositionLocalOf { DEFAULT_PARAGRAPH_FONT_SIZE_SP.sp }

/**
 * Whether the grouped message timestamps are rendered.
 */
val LocalShowTimestamp = staticCompositionLocalOf { true }

/**
 * Whether the thinking block starts collapsed (showing only the label).
 */
val LocalCollapseThinkingByDefault = staticCompositionLocalOf { false }

/**
 * Whether the assistant bubble shows the model name header.
 */
val LocalShowModelName = staticCompositionLocalOf { true }

/**
 * Whether the chat auto-scrolls to the bottom when new content arrives.
 */
val LocalAutoScrollToBottom = staticCompositionLocalOf { true }

/**
 * Code-block font size in sp, or [CODE_BLOCK_FONT_SIZE_AUTO_SP] to follow
 * the IDE editor font.
 */
val LocalCodeBlockFontSize = staticCompositionLocalOf { CODE_BLOCK_FONT_SIZE_AUTO_SP }

/** Whether the copy button is shown in the message actions row. */
val LocalShowCopyAction = staticCompositionLocalOf { true }

/** Whether the retry button is shown in the message actions row. */
val LocalShowRetryAction = staticCompositionLocalOf { true }

/** Whether the like / dislike buttons are shown (they toggle together). */
val LocalShowLikeDislikeAction = staticCompositionLocalOf { true }

/**
 * Provides the persisted appearance settings to the compose tree.
 *
 * Reads [AppearanceSettings] and subscribes to its snapshot so the tree
 * recomposes when the user changes settings. Place at the root of the
 * Gradum UI (before both the chat and welcome screens).
 */
@Composable
fun ProvideAppearance(content: @Composable () -> Unit) {
  val settings = androidx.compose.runtime.remember { AppearanceSettings.getInstance() }
  val snapshot = settings.snapshot

  val paragraphSpacing: Dp = when (snapshot.paragraphDensity) {
    ParagraphDensity.COMPACT -> 0.dp
    ParagraphDensity.DEFAULT -> GradumSpacing.md
    ParagraphDensity.SPACIOUS -> GradumSpacing.lrl
  }
  val paragraphFontSize: TextUnit = snapshot.paragraphFontSizeSp.sp

  CompositionLocalProvider(
    LocalParagraphSpacing provides paragraphSpacing,
    LocalParagraphFontSize provides paragraphFontSize,
    LocalShowTimestamp provides snapshot.showTimestamp,
    LocalCollapseThinkingByDefault provides snapshot.collapseThinkingByDefault,
    LocalShowModelName provides snapshot.showModelName,
    LocalAutoScrollToBottom provides snapshot.autoScrollToBottom,
    LocalCodeBlockFontSize provides snapshot.codeBlockFontSizeSp,
    LocalShowCopyAction provides snapshot.showCopyAction,
    LocalShowRetryAction provides snapshot.showRetryAction,
    LocalShowLikeDislikeAction provides snapshot.showLikeDislikeAction
  ) {
    content()
  }
}