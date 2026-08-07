/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 */

@file:OptIn(InternalJewelApi::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package gradum.idea

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.skill.internal.linesAddedColor
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.util.*

/**
 * Builds a [Tree] of [AuditTreeItem] nodes for the findings list. Groups
 * with zero findings are skipped; each node's children are its findings,
 * sorted most-severe-first.
 */
internal fun buildAuditFindingsTree(
  findings: List<AuditFinding>, groupBySeverity: Boolean
): Tree<AuditTreeItem> =
  buildTree {
    val groupItems: List<AuditTreeItem> = if (groupBySeverity) {
      SEVERITY_ORDER.mapNotNull { level ->
        val grouped = findings.filter { it.level == level }
        if (grouped.isEmpty()) null else AuditTreeItem.SeverityGroup(level, grouped.size)
      }
    } else {
      AuditGroup.entries.mapNotNull { group ->
        val grouped = findings.filter { auditGroupOf(it.code) == group }
        if (grouped.isEmpty()) null else AuditTreeItem.Group(group, grouped.size)
      }
    }
    groupItems.forEach { groupItem ->
      val grouped = when (groupItem) {
        is AuditTreeItem.Group -> findings.filter { auditGroupOf(it.code) == groupItem.group }
        is AuditTreeItem.SeverityGroup -> findings.filter { it.level == groupItem.level }
        is AuditTreeItem.Finding -> emptyList()
      }
      addNode(groupItem, groupItem) {
        grouped
          .sortedWith(compareBy { severityRank(it.level) })
          .forEach { finding ->
            val findingKey = AuditTreeItem.Finding(finding)
            addLeaf(findingKey, findingKey)
          }
      }
    }
  }

/**
 * Renders the parsed SXXXX audit findings after a successful scan. Findings
 * are bucketed into either the four fixed [AuditGroup] rows or severity
 * rows (when [groupBySeverity] is true). Each group row shows its name plus
 * finding count; an empty findings list falls back to the "no suspicious
 * issues" message.
 */
@OptIn(InternalJewelApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun AuditFindingsTree(
  isAllExpanded: Boolean,
  groupBySeverity: Boolean,
  reviewedFindings: Set<String>,
  modifier: Modifier = Modifier,
  onFindingSelected: (AuditFinding?) -> Unit
) {
  val findings = GradumGitAnalysisService.auditFindings

  if (findings.isEmpty()) {
    Text(
      textAlign = TextAlign.Center,
      modifier = modifier.fillMaxSize(),
      color = LocalGlobalColors.current.text.info,
      text = message(
        "gradum.toolwindow.git.analysis.success", GradumGitAnalysisService.totalCommits
      ),
    )
    return
  }

  val treeState = rememberTreeState()
  val regularStyle = JewelTheme.typography.regular
  val problemTree = remember(findings, groupBySeverity) {
    buildAuditFindingsTree(findings, groupBySeverity)
  }
  val groupKeys = remember(findings, groupBySeverity) {
    val groupItems = if (groupBySeverity) {
      SEVERITY_ORDER.mapNotNull { level ->
        val count = findings.count { it.level == level }
        if (count == 0) null else AuditTreeItem.SeverityGroup(level, count)
      }
    } else {
      AuditGroup.entries.mapNotNull { group ->
        val count = findings.count { auditGroupOf(it.code) == group }
        if (count == 0) null else AuditTreeItem.Group(group, count)
      }
    }
    groupItems.toSet()
  }

  LaunchedEffect(isAllExpanded, groupKeys) {
    treeState.openNodes = if (isAllExpanded) groupKeys else emptySet()
  }

  Row(modifier = modifier.fillMaxSize()) {
    @Suppress("UnstableApiUsage")
    LazyTree(
      tree = problemTree,
      treeState = treeState,
      onElementClick = { element ->
        onFindingSelected((element.data as? AuditTreeItem.Finding)?.finding)
      },
      onSelectionChange = { elements ->
        onFindingSelected(
          elements
            .mapNotNull { (it.data as? AuditTreeItem.Finding)?.finding }
            .lastOrNull()
        )
      },
      modifier = Modifier.weight(1f).fillMaxHeight()
    ) { element ->
      when (val item = element.data) {
        is AuditTreeItem.Group -> {
          val activeCount = item.count - findings.count {
            auditGroupOf(it.code) == item.group && findingKey(it) in reviewedFindings
          }
          val formattedCount = "%,d".format(Locale.ROOT, minOf(activeCount, 9999))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(
                vertical = GradumSpacing.sm,
                horizontal = GradumSpacing.sml
              ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
          ) {
            Text(
              text = item.group.label(),
              style = regularStyle.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
              style = regularStyle,
              text = message("gradum.toolwindow.git.analysis.problems", formattedCount),
              color = LocalGlobalColors.current.text.info,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        is AuditTreeItem.SeverityGroup -> {
          val activeCount = item.count - findings.count {
            it.level == item.level && findingKey(it) in reviewedFindings
          }
          val formattedCount = "%,d".format(Locale.ROOT, minOf(activeCount, 9999))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(
                vertical = GradumSpacing.sm,
                horizontal = GradumSpacing.sml
              ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
          ) {
            Icon(
              contentDescription = null,
              key = severityIcon(item.level)
            )
            Text(
              text = severityLabel(item.level),
              style = regularStyle.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
              maxLines = 1,
              style = regularStyle,
              overflow = TextOverflow.Ellipsis,
              color = LocalGlobalColors.current.text.info,
              text = message("gradum.toolwindow.git.analysis.problems", formattedCount)
            )
          }
        }

        is AuditTreeItem.Finding -> {
          val finding = item.finding
          val isReviewed = findingKey(finding) in reviewedFindings
          var isHovered by remember { mutableStateOf(false) }
          Tooltip(tooltip = {
            Text(text = "(${finding.code}) ${finding.formatBody()}")
          }) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                .padding(
                  vertical = GradumSpacing.sm,
                  horizontal = GradumSpacing.sml
                ),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
            ) {
              if (!groupBySeverity) {
                if (isReviewed) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml),
                    modifier = Modifier.onPointerEvent(PointerEventType.Enter) { isHovered = true }
                      .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                  ) {
                    Icon(
                      contentDescription = null,
                      key = AllIconsKeys.General.GreenCheckmark
                    )
                    AnimatedVisibility(
                      visible = isHovered,
                      enter = expandHorizontally(
                        expandFrom = Alignment.Start, animationSpec = tween(200)
                      ) + fadeIn(animationSpec = tween(200)),
                      exit = shrinkHorizontally(
                        shrinkTowards = Alignment.Start, animationSpec = tween(150)
                      ) + fadeOut(animationSpec = tween(150))
                    ) {
                      Text(
                        maxLines = 1,
                        color = linesAddedColor(),
                        text = message("gradum.toolwindow.git.analysis.reviewed")
                      )
                    }
                  }
                } else {
                  Icon(
                    contentDescription = null,
                    key = severityIcon(finding.level)
                  )
                }
              }
              if (groupBySeverity) {
                Text(
                  text = finding.code,
                  style = regularStyle.copy(fontWeight = FontWeight.SemiBold)
                )
              }
              Text(
                maxLines = 1,
                style = regularStyle.copy(
                  textDecoration = if (isReviewed) TextDecoration.LineThrough else null
                ),
                text = finding.formatBody(),
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
      }
    }

    @Suppress("UnstableApiUsage")
    VerticalScrollbar(
      scrollState = treeState.lazyListState.lazyListState,
      modifier = Modifier
        .align(Alignment.CenterVertically)
        .fillMaxHeight()
    )
  }
}
