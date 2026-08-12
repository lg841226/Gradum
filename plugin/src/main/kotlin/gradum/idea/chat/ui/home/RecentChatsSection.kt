/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RecentChatsSection.kt  2026-08-12 16:01:51 Changed by gwy
 */

package gradum.idea.chat.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.history.SessionMeta
import gradum.idea.chat.model.formatTimestamp
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/** Maximum number of recent sessions shown on the Welcome screen. */
const val MAX_RECENT_SESSIONS: Int = 2

/**
 * "Recent Chats" region on the Welcome screen: the saved sessions for this
 * project, most recently updated first.
 *
 * One row per session mirrors `SuggestionCard` (hover highlight, leading
 * icon, ellipsized title); a delete icon is revealed on hover. Clicking a
 * row resumes that session; the delete icon removes it locally + on the
 * server ([gradum.idea.chat.state.GradumChatSession.deleteSession]).
 */
@Composable
fun RecentChatsSection(
  sessions: List<SessionMeta>,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier) {
    Text(
      text = message("gradum.recent.chats"),
      style = JewelTheme.typography.h4TextStyle
    )
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    if (sessions.isEmpty()) {
      Text(
        text = message("gradum.recent.empty"),
        color = JewelTheme.globalColors.text.info
      )
    } else {
      sessions.take(MAX_RECENT_SESSIONS).forEach { session ->
        RecentSessionRow(
          session = session,
          onOpenSession = onOpenSession,
          onDeleteSession = onDeleteSession
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSessionRow(
  session: SessionMeta,
  modifier: Modifier = Modifier,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isHovered by interactionSource.collectIsHoveredAsState()

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.sm)
      .hoverable(interactionSource),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .weight(1f)
        .clickable { onOpenSession(session.sessionId) }
        .clip(RoundedCornerShape(6.dp))
        .background(
          if (isHovered) JewelTheme.globalColors.text.info
            .copy(alpha = 0.08f) else Color.Transparent
        )
        .padding(
          vertical = GradumSpacing.sml,
          horizontal = GradumSpacing.md
        )
    ) {
      Icon(
        key = GradumIcons.Chat,
        contentDescription = null
      )
      Spacer(modifier = Modifier.width(GradumSpacing.md))
      Text(
        maxLines = 1,
        modifier = Modifier.weight(1f),
        overflow = TextOverflow.Ellipsis,
        text = session.title.ifBlank { session.sessionId }
      )
      Text(
        text = formatTimestamp(session.updatedAt),
        color = JewelTheme.globalColors.text.info
      )
    }
    AnimatedVisibility(
      visible = isHovered,
      enter = slideInHorizontally(
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium
        ),
        initialOffsetX = { it / -2 }
      ) + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)),
      exit = slideOutHorizontally(
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium
        ),
        targetOffsetX = { it / -2 }
      ) + fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
    ) {
      Spacer(modifier = Modifier.width(GradumSpacing.sm))
      Tooltip(tooltip = { Text(text = message("gradum.recent.delete")) }) {
        IconButton(onClick = { onDeleteSession(session.sessionId) }) {
          Icon(
            key = AllIconsKeys.General.Delete,
            contentDescription = message("gradum.recent.delete"),
          )
        }
      }
    }
  }
}
