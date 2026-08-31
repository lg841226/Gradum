/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RecentChatsSection.kt  2026-08-31 19:21:55 Changed by gwy
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

/**
 * "Recent Chats" region on the Welcome screen: the saved sessions for this
 * project, most recently updated first.
 *
 * One row per session mirrors `SuggestionCard` (hover highlight, leading
 * icon, ellipsized title); a delete icon is revealed on hover. Clicking a
 * row resumes that session; the delete icon removes it locally + on the
 * server ([gradum.idea.chat.state.GradumChatSession.deleteSession]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentChatsSection(
  maxDisplay: Int = 2,
  expanded: Boolean = false,
  sessions: List<SessionMeta>,
  modifier: Modifier = Modifier,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onStartMerge: (() -> Unit)? = null
) {
  val displayCount: Int = if (expanded) maxDisplay + 2 else maxDisplay

  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = message("gradum.recent.chats"),
        style = JewelTheme.typography.h4TextStyle
      )
      if (onStartMerge != null) {
        Spacer(modifier = Modifier.width(GradumSpacing.md))
        Tooltip(tooltip = { Text(text = message("gradum.recent.manage")) }) {
          IconButton(onClick = onStartMerge) {
            Icon(
              key = AllIconsKeys.General.Settings,
              contentDescription = message("gradum.recent.manage")
            )
          }
        }
      }
    }
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    sessions.take(n = displayCount).forEach { session ->
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
  var isHovered: Boolean by remember { mutableStateOf(value = false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.sm)
      .onPointerEvent(eventType = PointerEventType.Enter) { isHovered = true }
      .onPointerEvent(eventType = PointerEventType.Exit) { isHovered = false },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .weight(1f)
        .clickable { onOpenSession(session.sessionId) }
        .clip(shape = RoundedCornerShape(size = 6.dp))
        .background(
          color =
            if (isHovered) JewelTheme.globalColors.text.info.copy(alpha = 0.08f)
            else Color.Transparent
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
      Text(
        text = formatTimestamp(session.updatedAt),
        color = JewelTheme.globalColors.text.info
      )
      Spacer(modifier = Modifier.width(GradumSpacing.sm))
      Icon(
        contentDescription = null,
        key = AllIconsKeys.Actions.MoveToWindow
      )
    }
    AnimatedVisibility(
      visible = isHovered,
      enter = fadeIn(animationSpec = tween(durationMillis = 400)) +
        scaleIn(initialScale = 0.6f, animationSpec = tween(durationMillis = 400))
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .padding(start = GradumSpacing.sm)
          .onPointerEvent(eventType = PointerEventType.Enter) { isHovered = true }
          .onPointerEvent(eventType = PointerEventType.Exit) { isHovered = false },
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
