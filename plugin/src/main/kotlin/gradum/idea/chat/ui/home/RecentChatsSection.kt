/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RecentChatsSection.kt  2026-08-12 20:20:23 Changed by gwy
 */

package gradum.idea.chat.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
  modifier: Modifier = Modifier,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit
) {
  Column(modifier = modifier) {
    Text(
      text = message("gradum.recent.chats"),
      style = JewelTheme.typography.h4TextStyle
    )
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    sessions.take(MAX_RECENT_SESSIONS).forEach { session ->
      RecentSessionRow(
        session = session,
        onOpenSession = onOpenSession,
        onDeleteSession = onDeleteSession
      )
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun RecentSessionRow(
  session: SessionMeta,
  modifier: Modifier = Modifier,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit
) {
  var isHovered by remember { mutableStateOf(false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.sm)
      .onPointerEvent(PointerEventType.Enter) { isHovered = true }
      .onPointerEvent(PointerEventType.Exit) { isHovered = false },
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
        text = session.title.ifBlank { formatTimestamp(session.updatedAt) }
      )
      if (session.title.isNotBlank()) {
        Text(
          text = formatTimestamp(session.updatedAt),
          color = JewelTheme.globalColors.text.info
        )
      }
    }
    AnimatedVisibility(
      visible = isHovered,
      enter = fadeIn(animationSpec = tween(400)) +
        scaleIn(initialScale = 0.6f, animationSpec = tween(400))
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .padding(start = GradumSpacing.sm)
          .onPointerEvent(PointerEventType.Enter) { isHovered = true }
          .onPointerEvent(PointerEventType.Exit) { isHovered = false },
      ) {
        Tooltip(tooltip = { Text(text = message("gradum.delete.action")) }) {
          IconButton(onClick = { onDeleteSession(session.sessionId) }) {
            Icon(
              key = AllIconsKeys.General.Delete,
              contentDescription = message("gradum.delete.action"),
            )
          }
        }
      }
    }
  }
}
