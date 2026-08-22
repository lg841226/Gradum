/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingIndicator.kt  2026-08-14 15:03:22 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import gradum.idea.chat.ui.markdown.GradumMarkdown
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
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
 * @param isTaskComplete When true, collapses the thinking content.
 * @param hasResponseAfter When true, auto-collapses because a
 *   response block follows this thinking block.
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
  hasResponseAfter: Boolean = false,
  onUrlClick: (String) -> Unit = {},
  startCollapsed: Boolean = false
) {
  if (thinking.isBlank()) return

  var isExpanded by remember { mutableStateOf(!startCollapsed) }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.clickable { isExpanded = !isExpanded },
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.Nodes.Related
      )
      Text(
        text = message("gradum.thinking"),
        fontWeight = FontWeight.Medium
      )
      Icon(
        contentDescription = null,
        key = if (isExpanded) AllIconsKeys.General.ChevronDown
        else AllIconsKeys.General.ChevronRight
      )
    }

    if (isExpanded) Spacer(modifier = Modifier.height(GradumSpacing.md))

    if (isExpanded) {
      GradumMarkdown(text = thinking) {
        thinkingMode = true
        isSimplified = true
        animationEnabled = false
        paragraphStyle = rememberGradumParagraphTextStyle().copy(
          color = LocalGlobalColors.current.text.info
        )
      }
    }
  }
}