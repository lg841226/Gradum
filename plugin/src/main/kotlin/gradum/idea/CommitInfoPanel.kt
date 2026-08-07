/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommitInfoPanel.kt  2026-08-07 16:01:18 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.chat.ui.chat.skill.internal.linesAddedColor
import gradum.idea.chat.ui.chat.skill.internal.toolCallErrorColor
import gradum.idea.chat.ui.markdown.*
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Cursor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Default width of the commit-details panel shown right of the problem list. */
internal val CommitInfoPanelWidth = 320.dp

/** Minimum width the commit-details panel can be dragged to. */
internal val CommitInfoPanelMinWidth = 320.dp

/** Maximum width the commit-details panel can be dragged to. */
internal val CommitInfoPanelMaxWidth = 500.dp

/**
 * Vertical drag handle between the findings tree and the commit-details
 * panel. Dragging left or right resizes the panel width within
 * [CommitInfoPanelMinWidth]..[CommitInfoPanelMaxWidth].
 *
 * Implemented with a raw pointer loop instead of a gesture detector: the
 * start width is captured once at pointer-down, so a width change never
 * restarts the gesture coroutine.
 */
@Composable
internal fun CommitInfoPanelResizeHandle(
  commitInfoWidth: Dp,
  onResize: (Dp) -> Unit,
  modifier: Modifier = Modifier
) {
  val borderColor = JewelTheme.globalColors.borders.normal
  val handleHitWidth = 8.dp
  val currentWidth by rememberUpdatedState(commitInfoWidth)
  val density = LocalDensity.current
  val resizeCursor = remember { PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)) }

  var handleWindowLeft by remember { mutableFloatStateOf(0f) }

  Box(
    modifier = modifier
      .width(handleHitWidth)
      .pointerHoverIcon(resizeCursor)
      .onGloballyPositioned { handleWindowLeft = it.positionInWindow().x }
      .pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown()
          val startWidth = currentWidth
          val dividerWindowXAtDown = handleWindowLeft + down.position.x
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
              ?: break
            if (!change.pressed) break
            val dividerWindowX = handleWindowLeft + change.position.x
            val deltaPx = dividerWindowX - dividerWindowXAtDown
            if (deltaPx != 0f) {
              change.consume()
              val newWidth = with(density) {
                (startWidth - deltaPx.toDp())
                  .coerceIn(CommitInfoPanelMinWidth, CommitInfoPanelMaxWidth)
              }
              onResize(newWidth)
            }
          }
        }
      }
  ) {
    Box(
      modifier = Modifier
        .fillMaxHeight()
        .width(1.dp)
        .align(Alignment.Center)
        .background(borderColor)
    )
  }
}

/**
 * Right-hand panel that shows the commit details behind the currently
 * selected finding. Delimited from the problem list by a 1 dp line in the
 * theme's normal border color. It renders the commit subject as a bold main
 * title in the editor font, followed by the commit's full body text; the
 * content scrolls independently of the findings tree.
 */
@Composable
internal fun CommitInfoPanel(
  project: Project,
  isPinned: Boolean,
  finding: AuditFinding,
  onTogglePin: () -> Unit,
  onToggleReviewed: () -> Unit,
  modifier: Modifier = Modifier
) {
  val textColor = LocalGlobalColors.current.text.normal
  val scrollState = rememberScrollState()
  val scope = rememberCoroutineScope()
  var isCopied by remember { mutableStateOf(false) }
  var isTimeExpanded by remember { mutableStateOf(false) }

  val bodyTextStyle = JewelTheme.editorTextStyle.copy(
    fontSize = JewelTheme.editorTextStyle.fontSize - 1.sp
  )
  val commitInfoText = buildString {
    append(finding.subject.ifBlank { "-" })
    if (finding.body.isNotBlank()) {
      append("\n\n")
      append(finding.body)
    }
  }
  val timeAgo = relativeTime(finding.date)
  Column(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
      modifier = Modifier
        .fillMaxWidth()
        .padding(GradumSpacing.sml)
    ) {
      Tooltip(tooltip = { Text(text = message("gradum.toolwindow.git.analysis.action.open.github")) }) {
        IconButton(
          onClick = { openCommitOnGitHub(project, finding.hash) },
          enabled = finding.hash.isNotBlank()
        ) {
          Icon(
            key = AllIconsKeys.General.Export,
            contentDescription = message("gradum.toolwindow.git.analysis.action.open.github")
          )
        }
      }
      Tooltip(tooltip = { Text(text = message("gradum.copy.tooltip")) }) {
        IconButton(
          onClick = {
            copyToClipboard(
              text = commitInfoText,
              onCopied = { isCopied = true },
              onReset = { isCopied = false },
              scope = scope
            )
          },
          enabled = commitInfoText.isNotBlank()
        ) {
          Icon(
            key = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy,
            contentDescription = message("gradum.copy")
          )
        }
      }
      Tooltip(tooltip = { Text(text = message("gradum.toolwindow.git.analysis.action.pin")) }) {
        IconButton(onClick = onTogglePin) {
          Icon(
            key = if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
            contentDescription = message("gradum.toolwindow.git.analysis.action.pin")
          )
        }
      }
      Tooltip(tooltip = { Text(text = message("gradum.toolwindow.git.analysis.action.mark.reviewed")) }) {
        IconButton(onClick = onToggleReviewed) {
          Icon(
            key = AllIconsKeys.Toolwindows.ToolWindowBookmarks,
            contentDescription = message("gradum.toolwindow.git.analysis.action.mark.reviewed")
          )
        }
      }
      if (timeAgo.isNotBlank()) {
        Icon(
          key =
            if (isTimeExpanded) AllIconsKeys.General.ChevronDown
            else AllIconsKeys.General.ChevronRight,
          contentDescription = timeAgo,
          modifier = Modifier
            .clickable { isTimeExpanded = !isTimeExpanded }
            .padding(horizontal = 2.dp)
        )
        if (!isTimeExpanded) {
          Text(
            text = timeAgo,
            color = LocalGlobalColors.current.text.info
          )
        }
      }
    }
    AnimatedVisibility(
      visible = isTimeExpanded && timeAgo.isNotBlank(),
      enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(300)) + fadeIn(animationSpec = tween(200)),
      exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
    ) {
      val hasExplicitAdd = finding.params.containsKey("add")
      val linesAdded = (finding.params["add"] as? Number)?.toInt()
        ?: if (!hasExplicitAdd) null
        else (finding.params["lines"] as? Number)?.toInt()?.coerceAtLeast(0)
      val linesRemoved = (finding.params["dels"] as? Number)?.toInt()
        ?: if (!hasExplicitAdd) (finding.params["lines"] as? Number)?.toInt()?.coerceAtLeast(0)
        else null
      val commitDate = try {
        val date = LocalDate.parse(finding.date, DateTimeFormatter.ISO_LOCAL_DATE)
        when (val days = ChronoUnit.DAYS.between(date, LocalDate.now()).toInt()) {
          0 -> message("gradum.toolwindow.git.analysis.time.just.now")
          1 -> message("gradum.toolwindow.git.analysis.time.day.ago")
          else -> message("gradum.toolwindow.git.analysis.time.days.ago", days)
        }
      } catch (_: Exception) {
        finding.date
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml),
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            start = GradumSpacing.lg,
            top = GradumSpacing.xs,
            bottom = GradumSpacing.sml,
            end = GradumSpacing.lg
          )
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
          Icon(
            contentDescription = null,
            key = AllIconsKeys.General.User
          )
          Text(
            text = finding.author,
            color = LocalGlobalColors.current.text.info
          )
        }
        Text(
          text = commitDate,
          color = LocalGlobalColors.current.text.info
        )
        Spacer(Modifier.weight(1f))
        if (linesAdded != null && linesAdded > 0) {
          Text(
            text = "+$linesAdded",
            color = linesAddedColor()
          )
        }
        if (linesRemoved != null && linesRemoved > 0) {
          Text(
            text = "-$linesRemoved",
            color = toolCallErrorColor()
          )
        }
      }
    }
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(scrollState)
          .padding(
            vertical = GradumSpacing.md,
            horizontal = GradumSpacing.lg
          )
      ) {
        Text(
          color = textColor,
          text = finding.subject.ifBlank { "-" },
          style = bodyTextStyle.copy(fontWeight = FontWeight.Bold)
        )
        if (finding.body.isNotBlank()) {
          Spacer(Modifier.height(GradumSpacing.md))
          val bodySegments = remember(finding.body) { splitMarkdown(finding.body) }
          CompositionLocalProvider(
            LocalMarkdownBodyTextStyle provides bodyTextStyle.copy(color = textColor)
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)) {
              bodySegments.forEach { segment ->
                when (segment) {
                  is MarkdownSegment.Plain -> RenderInlineTextWithChips(
                    onUrlClick = {},
                    text = segment.text,
                    modifier = Modifier.fillMaxWidth(),
                    style = bodyTextStyle.copy(color = textColor)
                  )

                  is MarkdownSegment.NonProseBlock -> RenderNonProseBlock(
                    onUrlClick = {},
                    segment = segment
                  )

                  is MarkdownSegment.Table -> ScrollableTable(
                    onUrlClick = {},
                    table = segment,
                    modifier = Modifier.fillMaxWidth()
                  )
                }
              }
            }
          }
        }
      }

      VerticalScrollbar(
        scrollState = scrollState,
        modifier = Modifier
          .align(Alignment.CenterVertically)
          .fillMaxHeight()
      )
    }
  }
}
