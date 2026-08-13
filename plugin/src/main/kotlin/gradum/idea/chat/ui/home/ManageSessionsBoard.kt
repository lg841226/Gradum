/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ManageSessionsBoard.kt  2026-08-13 15:51:53 Changed by gwy
 */

package gradum.idea.chat.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Full-screen session manager, opened from the "Manage" gear on the Welcome
 * screen. Replaces the whole Welcome column so the user can focus on the
 * session list.
 *
 * Every row is a selection entry: a checkbox on the leading edge (outside the
 * clickable sub-entry) plus the row itself both toggle the merge/delete
 * selection. A hover reveal offers rename (inline, pen becomes a checkmark +
 * cancel while editing) and delete. The bottom action bar only appears for
 * multi-selection (≥[GradumChatSession.MIN_MERGE_SESSIONS]) and offers a
 * merge of everything selected plus a bulk delete.
 *
 * A top search field filters the list by title. Sessions are grouped by age:
 * Today, Yesterday, This Week, This Month, Older.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ManageSessionsBoard(
  sessions: List<SessionMeta>,
  selectedIds: Set<String>,
  onMerge: () -> Unit,
  onClearSelection: () -> Unit,
  onDeleteSelected: () -> Unit,
  modifier: Modifier = Modifier,
  onToggleSelection: (String) -> Unit,
  onRenameSession: (String, String) -> Unit,
  onDeleteSession: (String) -> Unit = {}
) {
  val selectedCount: Int = selectedIds.size
  val searchState: TextFieldState = remember { TextFieldState() }
  var exactMatch by remember { mutableStateOf(false) }
  val searchQuery: String = searchState.text.toString().trim()
  val filteredSessions: List<SessionMeta> = sessions.filter {
    when {
      searchQuery.isBlank() -> true
      exactMatch -> it.title == searchQuery
      else -> it.title.contains(searchQuery, ignoreCase = true)
    }
  }
  var renamingSessionId by remember { mutableStateOf<String?>(null) }
  val renamingTitle: TextFieldState = remember(renamingSessionId) {
    val session: SessionMeta? = sessions.find { it.sessionId == renamingSessionId }
    TextFieldState(session?.title.orEmpty())
  }

  Column(modifier = modifier.fillMaxHeight()) {
    Spacer(Modifier.height(GradumSpacing.md))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = message("gradum.manage.title"),
        style = JewelTheme.typography.h4TextStyle
      )
    }
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        key = GradumIcons.Search,
        contentDescription = message("gradum.manage.search.placeholder")
      )
      Spacer(modifier = Modifier.width(GradumSpacing.md))
      TextField(
        undecorated = true,
        state = searchState,
        modifier = Modifier.weight(1f)
          .padding(vertical = GradumSpacing.sm),
        placeholder = {
          Text(text = message("gradum.manage.search.placeholder"))
        }
      )
      Spacer(modifier = Modifier.width(GradumSpacing.sm))
      Tooltip(tooltip = { Text(text = message("gradum.manage.search.exact")) }) {
        ToggleableIconButton(
          value = exactMatch,
          onValueChange = { exactMatch = it }
        ) {
          Icon(
            key = AllIconsKeys.Actions.MatchCase,
            contentDescription = message("gradum.manage.search.exact")
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
    if (filteredSessions.isEmpty()) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = message("gradum.manage.search.empty"),
          color = JewelTheme.globalColors.text.info
        )
      }
    } else {
      val lastSelectedIndex: Int = filteredSessions.indexOfLast {
        it.sessionId in selectedIds
      }
      val groupedSessions: List<Pair<String, List<SessionMeta>>> =
        groupSessionsByAge(filteredSessions)
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
      ) {
        var flatIndex = 0
        groupedSessions.forEach { (groupLabel, groupSessions) ->
          if (groupSessions.isNotEmpty()) {
            Text(
              text = groupLabel,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.padding(
                top = GradumSpacing.md,
                bottom = GradumSpacing.sm
              )
            )
            groupSessions.forEach { session ->
              ManageSessionRow(
                session = session,
                isSelected = session.sessionId in selectedIds,
                isRenaming = session.sessionId == renamingSessionId,
                renamingTitle = renamingTitle,
                onToggle = { onToggleSelection(session.sessionId) },
                onStartRename = { renamingSessionId = session.sessionId },
                onCommitRename = { newTitle ->
                  onRenameSession(session.sessionId, newTitle)
                  renamingSessionId = null
                },
                onCancelRename = { renamingSessionId = null },
                onDelete = { onDeleteSession(session.sessionId) }
              )
              if (flatIndex == lastSelectedIndex &&
                selectedCount >= GradumChatSession.MIN_MERGE_SESSIONS
              ) {
                Spacer(modifier = Modifier.height(GradumSpacing.sm))
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GradumSpacing.md),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
                ) {
                  Tooltip(tooltip = { Text(text = message("gradum.manage.merge")) }) {
                    IconButton(onClick = onMerge) {
                      Icon(
                        key = AllIconsKeys.General.Vcs,
                        contentDescription = message("gradum.manage.merge")
                      )
                    }
                  }
                  Tooltip(tooltip = { Text(text = message("gradum.manage.delete.selected")) }) {
                    IconButton(onClick = onDeleteSelected) {
                      Icon(
                        key = AllIconsKeys.General.Delete,
                        contentDescription = message("gradum.manage.delete.selected")
                      )
                    }
                  }
                  Tooltip(tooltip = { Text(text = message("gradum.manage.cancel")) }) {
                    IconButton(onClick = onClearSelection) {
                      Icon(
                        key = AllIconsKeys.General.Close,
                        contentDescription = message("gradum.manage.cancel")
                      )
                    }
                  }
                }
                Spacer(modifier = Modifier.height(GradumSpacing.sm))
              }
              flatIndex++
            }
          }
        }
      }
    }
  }
}

@OptIn(
  ExperimentalComposeUiApi::class,
  androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun ManageSessionRow(
  session: SessionMeta,
  isSelected: Boolean,
  isRenaming: Boolean,
  renamingTitle: TextFieldState,
  onToggle: () -> Unit,
  onDelete: () -> Unit,
  onStartRename: () -> Unit,
  onCancelRename: () -> Unit,
  onCommitRename: (String) -> Unit
) {
  var isHovered by remember { mutableStateOf(false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = GradumSpacing.sm)
      .onPointerEvent(PointerEventType.Enter) { isHovered = true }
      .onPointerEvent(PointerEventType.Exit) { isHovered = false }
  ) {
    Checkbox(
      checked = isSelected,
      onCheckedChange = { onToggle() }
    )
    Spacer(modifier = Modifier.width(GradumSpacing.sml))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .weight(1f)
        .clip(RoundedCornerShape(6.dp))
        .background(
          if (isHovered || isSelected) JewelTheme.globalColors.text.info
            .copy(alpha = 0.08f) else Color.Transparent
        )
        .clickable(onClick = onToggle)
        .padding(
          horizontal = GradumSpacing.md,
          vertical = GradumSpacing.sml
        )
    ) {
      Icon(
        key = GradumIcons.Chat,
        contentDescription = null
      )
      Spacer(modifier = Modifier.width(GradumSpacing.md))
      if (isRenaming) {
        TextField(
          undecorated = true,
          state = renamingTitle,
          modifier = Modifier.weight(1f),
          placeholder = {
            Text(text = message("gradum.manage.rename.placeholder"))
          }
        )
      } else {
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
    AnimatedVisibility(
      visible = isHovered || isRenaming,
      enter = fadeIn(animationSpec = tween(400)) +
        scaleIn(initialScale = 0.6f, animationSpec = tween(400))
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .padding(start = GradumSpacing.sm)
          .onPointerEvent(PointerEventType.Enter) { isHovered = true }
          .onPointerEvent(PointerEventType.Exit) { isHovered = false }
      ) {
        if (isRenaming) {
          Tooltip(tooltip = { Text(text = message("gradum.manage.rename.confirm")) }) {
            IconButton(
              onClick = { onCommitRename(renamingTitle.text.toString()) }
            ) {
              Icon(
                key = AllIconsKeys.Actions.Checked,
                contentDescription = message("gradum.manage.rename.confirm")
              )
            }
          }
          Tooltip(tooltip = { Text(text = message("gradum.manage.cancel")) }) {
            IconButton(onClick = onCancelRename) {
              Icon(
                key = AllIconsKeys.General.Close,
                contentDescription = message("gradum.manage.cancel")
              )
            }
          }
        } else {
          Tooltip(tooltip = { Text(text = message("gradum.manage.rename")) }) {
            IconButton(onClick = onStartRename) {
              Icon(
                key = AllIconsKeys.Actions.Edit,
                contentDescription = message("gradum.manage.rename")
              )
            }
          }
          Tooltip(tooltip = { Text(text = message("gradum.delete.action")) }) {
            IconButton(onClick = onDelete) {
              Icon(
                key = AllIconsKeys.General.Delete,
                contentDescription = message("gradum.delete.action")
              )
            }
          }
        }
      }
    }
  }
}

private fun groupSessionsByAge(
  sessions: List<SessionMeta>
): List<Pair<String, List<SessionMeta>>> {
  val zone: ZoneId = ZoneId.systemDefault()
  val today: LocalDate = LocalDate.now(zone)
  val yesterday: LocalDate = today.minusDays(1)
  val weekAgo: LocalDate = today.minusDays(7)
  val monthAgo: LocalDate = today.minusMonths(1)

  data class Bucket(
    val label: String,
    val sessions: MutableList<SessionMeta> = mutableListOf()
  )

  val todayBucket = Bucket(message("gradum.manage.group.today"))
  val yesterdayBucket = Bucket(message("gradum.manage.group.yesterday"))
  val weekBucket = Bucket(message("gradum.manage.group.this.week"))
  val monthBucket = Bucket(message("gradum.manage.group.this.month"))
  val olderBucket = Bucket(message("gradum.manage.group.older"))

  sessions.forEach { session ->
    val createdDate: LocalDate = Instant.ofEpochMilli(session.createdAt)
      .atZone(zone).toLocalDate()
    when {
      !createdDate.isBefore(today) -> todayBucket.sessions.add(session)
      createdDate == yesterday -> yesterdayBucket.sessions.add(session)
      !createdDate.isBefore(weekAgo) -> weekBucket.sessions.add(session)
      !createdDate.isBefore(monthAgo) -> monthBucket.sessions.add(session)
      else -> olderBucket.sessions.add(session)
    }
  }

  return listOf(
    todayBucket.label to todayBucket.sessions,
    yesterdayBucket.label to yesterdayBucket.sessions,
    weekBucket.label to weekBucket.sessions,
    monthBucket.label to monthBucket.sessions,
    olderBucket.label to olderBucket.sessions
  )
}
