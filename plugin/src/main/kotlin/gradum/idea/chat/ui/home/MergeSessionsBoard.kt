/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MergeSessionsBoard.kt  2026-08-13 12:15:19 Changed by gwy
 */

package gradum.idea.chat.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import gradum.idea.chat.state.GradumChatSession
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Full-screen session picker for [GradumChatSession.mergeSelectedSessions].
 *
 * Replaces the whole Welcome column while merge mode is active: the header,
 * input and quick-start suggestions are hidden so the user can focus on
 * ticking exactly [GradumChatSession.MAX_MERGE_SESSIONS] sessions. Each row
 * carries a [Checkbox] on its leading edge; the merge action only enables
 * once exactly two rows are selected, and the originals are preserved (the
 * merge itself is non-destructive).
 */
@Composable
fun MergeSessionsBoard(
  sessions: List<SessionMeta>,
  selectedIds: Set<String>,
  modifier: Modifier = Modifier,
  onToggleSelection: (String) -> Unit,
  onMerge: () -> Unit,
  onCancel: () -> Unit
) {
  val selectedCount: Int = selectedIds.size
  val maxSessions: Int = GradumChatSession.MAX_MERGE_SESSIONS

  Column(
    modifier = modifier.fillMaxHeight()
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = message("gradum.recent.merge"),
        style = JewelTheme.typography.h4TextStyle
      )
      IconButton(onClick = onCancel) {
        Icon(
          contentDescription = message("gradum.merge.cancel"),
          key = AllIconsKeys.General.Close
        )
      }
    }
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    Text(
      color = JewelTheme.globalColors.text.info,
      text = message("gradum.merge.selected.hint", selectedCount, maxSessions)
    )
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
    ) {
      sessions.forEach { session ->
        MergeSessionRow(
          session = session,
          isSelected = session.sessionId in selectedIds,
          onToggle = { onToggleSelection(session.sessionId) }
        )
      }
    }
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = GradumSpacing.lg),
      horizontalArrangement = Arrangement.Center
    ) {
      DefaultButton(
        onClick = onMerge,
        enabled = selectedCount == maxSessions
      ) {
        Text(text = message("gradum.merge.action"))
      }
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MergeSessionRow(
  session: SessionMeta,
  isSelected: Boolean,
  onToggle: () -> Unit
) {
  var isHovered by remember { mutableStateOf(false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .onPointerEvent(PointerEventType.Enter) { isHovered = true }
      .onPointerEvent(PointerEventType.Exit) { isHovered = false }
      .clip(RoundedCornerShape(6.dp))
      .background(
        if (isHovered || isSelected) JewelTheme.globalColors.text.info
          .copy(alpha = 0.08f) else Color.Transparent
      )
      .clickable(onClick = onToggle)
      .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.sm)
  ) {
    Checkbox(
      checked = isSelected,
      onCheckedChange = { onToggle() }
    )
    Spacer(modifier = Modifier.width(GradumSpacing.md))
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
      Spacer(modifier = Modifier.width(GradumSpacing.md))
      Text(
        text = formatTimestamp(session.updatedAt),
        color = JewelTheme.globalColors.text.info
      )
    }
  }
}
