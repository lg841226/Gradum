/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingIndicator.kt  2026-08-12 12:38:25 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import gradum.idea.chat.ui.markdown.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownBlockRenderer
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Collapsible thinking indicator for assistant messages.
 *
 * Renders the LLM's accumulated reasoning as Markdown inside a
 * muted-gray palette so the whole block reads as ephemeral context
 * rather than a finished reply. Fenced code blocks and GFM tables
 * inside the thinking text are rendered without toolbars
 * (`isSimplified = true`) — copy / insert-as-file affordances are
 * only useful once the user has committed to the final answer.
 *
 * Shows "思考" label when collapsed, full thinking content when expanded.
 * Uses animated visibility for expand/collapse transitions.
 *
 * @param thinking The accumulated thinking content from the LLM.
 * @param enterTransition Custom enter transition for the outer wrapper.
 * @param isTaskComplete When true, collapses the thinking content.
 * @param onUrlClick Forwarded to the Markdown renderer for inline
 *   links inside the reasoning block. Same signature as the
 *   response-block renderer so the host (a chat bubble) only has
 *   to wire one handler.
 */
@Composable
fun ThinkingIndicator(
  thinking: String,
  modifier: Modifier = Modifier,
  isTaskComplete: Boolean = false,
  onUrlClick: (String) -> Unit = {},
  enterTransition: EnterTransition = fadeIn(tween(800))
) {
  if (thinking.isBlank()) return

  var isExpanded by remember { mutableStateOf(true) }

  LaunchedEffect(isTaskComplete) {
    if (isTaskComplete) isExpanded = false
  }

  val thinkingStyling = rememberGradumMarkdownStyling(thinkingMode = true)
  val simplifiedCodeRenderer = remember(thinkingStyling) {
    GradumCodeBlockRenderer(styling = thinkingStyling, isSimplified = true)
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.clickable { isExpanded = !isExpanded },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.Nodes.Related
      )
      Text(
        fontWeight = FontWeight.Medium,
        text = message("gradum.thinking"),
      )
      Icon(
        key = if (isExpanded) AllIconsKeys.General.ChevronDown
        else AllIconsKeys.General.ChevronRight,
        contentDescription = null
      )
    }

    if (isExpanded) {
      Spacer(modifier = Modifier.height(GradumSpacing.md))
    }

    AnimatedVisibility(visible = isExpanded) {
      val thinkingColor: androidx.compose.ui.graphics.Color = LocalGlobalColors.current.text.disabled
      CompositionLocalProvider(LocalContentColor provides thinkingColor) {
        CompositionLocalProvider(LocalMarkdownBlockRenderer provides simplifiedCodeRenderer) {
          val segments = remember(thinking) { splitMarkdown(thinking) }
          Column {
            segments.forEach { segment ->
              when (segment) {
                is MarkdownSegment.Plain -> Markdown(
                  markdown = segment.text,
                  onUrlClick = onUrlClick,
                  modifier = Modifier.fillMaxWidth(),
                  markdownStyling = thinkingStyling,
                  blockRenderer = simplifiedCodeRenderer,
                )

                is MarkdownSegment.Table -> {
                  if (segment.isRenderable()) {
                    ScrollableTable(segment, isSimplified = true)
                  }
                }

                is MarkdownSegment.NonProseBlock -> Markdown(
                  markdown = segment.text,
                  onUrlClick = onUrlClick,
                  modifier = Modifier.fillMaxWidth(),
                  markdownStyling = thinkingStyling,
                  blockRenderer = simplifiedCodeRenderer,
                )
              }
            }
          }
        }
      }
    }
  }
}
