/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AuditFindingsTree.kt  2026-08-25 01:50:11 Changed by gwy
 */

@file:OptIn(
  InternalJewelApi::class,
  ExperimentalJewelApi::class,
  ExperimentalComposeUiApi::class,
  ExperimentalFoundationApi::class
)

package gradum.idea

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import java.util.*

/** Default number of findings shown per group before a loadProperties-more row appears. */
private const val DEFAULT_FINDING_LIMIT = 50

/** Cap for the displayed count so rows never overflow with huge numbers. */
private const val MAX_DISPLAY_COUNT = 9999

/**
 * Builds the displayed count text and its singular/plural message key from
 * [count]. Shared by every row that shows a finding total so the number
 * formatting and key selection stay in one place.
 */
private fun findingCountPresentation(count: Int): Pair<String, String> {
  val formatted: String = "%,d".format(Locale.ROOT, minOf(a = count, b = MAX_DISPLAY_COUNT))
  val key: String =
    if (count == 1) "gradum.toolwindow.git.analysis.problem"
    else "gradum.toolwindow.git.analysis.problems"
  return formatted to key
}

/**
 * Builds a [Tree] of [AuditTreeItem] nodes for the findings list. When a
 * non-blank [branchName] is available the group rows are wrapped in a single
 * branch node on top; groups with zero findings are still skipped, and each
 * group's children are its findings, sorted most-severe-first.
 */
internal fun buildAuditFindingsTree(
  findings: List<AuditFinding>, groupBySeverity: Boolean, branchName: String?,
  groupLimits: Map<AuditTreeItem, Int>
): Tree<AuditTreeItem> =
  buildTree {
    val groupItems: List<AuditTreeItem> = if (groupBySeverity) {
      SEVERITY_ORDER.mapNotNull { level: String ->
        val grouped = findings.filter {
          it.level == level
        }
        if (grouped.isEmpty()) null
        else AuditTreeItem.SeverityGroup(level, count = grouped.size)
      }
    } else {
      AuditGroup.entries.mapNotNull { group: AuditGroup ->
        val grouped = findings.filter {
          auditGroupOf(it.code) == group
        }
        if (grouped.isEmpty()) null
        else AuditTreeItem.Group(group, count = grouped.size)
      }
    }

    fun TreeGeneratorScope<AuditTreeItem>.addGroupRows() {
      groupItems.forEach { groupItem ->
        val grouped =
          when (groupItem) {
            is AuditTreeItem.Group -> findings.filter {
              auditGroupOf(it.code) == groupItem.group
            }

            is AuditTreeItem.SeverityGroup -> findings.filter {
              it.level == groupItem.level
            }

            is AuditTreeItem.Branch,
            is AuditTreeItem.Finding,
            is AuditTreeItem.LoadMore -> emptyList()
          }
        addNode(data = groupItem, id = groupItem) {
          val sorted = grouped.sortedWith(comparator = compareBy { severityRank(it.level) })
          val limit: Int = groupLimits[groupItem] ?: DEFAULT_FINDING_LIMIT

          sorted.take(n = limit).forEach { finding: AuditFinding ->
            val findingKey = AuditTreeItem.Finding(finding)
            addLeaf(data = findingKey, id = findingKey)
          }
          val remaining: Int = sorted.size - limit
          if (remaining > 0) {
            val moreRow = AuditTreeItem.LoadMore(groupItem, remaining)
            addLeaf(data = moreRow, id = moreRow)
          }
        }
      }
    }

    val branchKey = branchName?.takeIf { it.isNotBlank() }
      ?.let { AuditTreeItem.Branch(name = it, count = findings.size) }
    if (branchKey == null) addGroupRows()
    else addNode(data = branchKey, id = branchKey) { addGroupRows() }
  }

/**
 * Renders the parsed SXXXX audit findings after a successful scan. Findings
 * are bucketed into either the four fixed [AuditGroup] rows or severity
 * rows (when [groupBySeverity] is true). Each group row shows its name plus
 * finding count; an empty findings list falls back to the "no suspicious
 * issues" message.
 */
@Composable
internal fun AuditFindingsTree(
  isAllExpanded: Boolean,
  groupBySeverity: Boolean,
  modifier: Modifier = Modifier,
  reviewedFindings: Set<String>,
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
  val branchName = GradumGitAnalysisService.currentBranch
  var groupLimits by remember { mutableStateOf<Map<AuditTreeItem, Int>>(value = emptyMap()) }
  val branchKey = remember(key1 = branchName, key2 = findings) {
    branchName?.takeIf { it.isNotBlank() }?.let { AuditTreeItem.Branch(name = it, count = findings.size) }
  }
  val problemTree = remember(findings, groupBySeverity, branchName, groupLimits) {
    buildAuditFindingsTree(findings, groupBySeverity, branchName, groupLimits)
  }
  val groupKeys = remember(key1 = findings, key2 = groupBySeverity) {
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

  LaunchedEffect(key1 = isAllExpanded, key2 = groupKeys, key3 = branchKey) {
    treeState.openNodes =
      if (branchKey == null)
        if (isAllExpanded) groupKeys else emptySet()
      else
        if (isAllExpanded) groupKeys + branchKey else setOf(branchKey)
  }

  Row(modifier = modifier.fillMaxSize()) {
    @Suppress("UnstableApiUsage")
    LazyTree(
      tree = problemTree,
      treeState = treeState,
      onElementClick = { element ->
        when (val data = element.data) {
          is AuditTreeItem.Finding -> onFindingSelected(data.finding)
          is AuditTreeItem.LoadMore -> {
            val key = data.group
            groupLimits =
              groupLimits + (key to ((groupLimits[key] ?: DEFAULT_FINDING_LIMIT) + DEFAULT_FINDING_LIMIT))
          }

          else -> Unit
        }
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
        is AuditTreeItem.Branch -> {
          val (formattedCount, countKey) = findingCountPresentation(item.count)

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
              key = AllIconsKeys.Vcs.Branch
            )
            Text(
              maxLines = 1,
              text = item.name,
              overflow = TextOverflow.Ellipsis,
              style = regularStyle.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
              maxLines = 1,
              style = regularStyle,
              overflow = TextOverflow.Ellipsis,
              text = message(countKey, formattedCount),
              color = LocalGlobalColors.current.text.info
            )
          }
        }

        is AuditTreeItem.Group -> {
          val activeCount = item.count - findings.count {
            auditGroupOf(it.code) == item.group && findingKey(it) in reviewedFindings
          }
          val (formattedCount, countKey) = findingCountPresentation(activeCount)

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
              style = regularStyle,
              text = item.group.label()
            )
            Text(
              maxLines = 1,
              style = regularStyle,
              overflow = TextOverflow.Ellipsis,
              text = message(countKey, formattedCount),
              color = LocalGlobalColors.current.text.info
            )
          }
        }

        is AuditTreeItem.SeverityGroup -> {
          val activeCount = item.count - findings.count {
            it.level == item.level && findingKey(it) in reviewedFindings
          }
          val (formattedCount, countKey) = findingCountPresentation(activeCount)
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
              style = regularStyle,
              text = severityLabel(item.level)
            )
            Text(
              maxLines = 1,
              style = regularStyle,
              overflow = TextOverflow.Ellipsis,
              text = message(countKey, formattedCount),
              color = LocalGlobalColors.current.text.info
            )
          }
        }

        is AuditTreeItem.LoadMore -> {
          Text(
            modifier = Modifier
              .fillMaxWidth()
              .padding(
                vertical = GradumSpacing.sm,
                horizontal = GradumSpacing.sml
              ),
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            color = LocalGlobalColors.current.text.info,
            text = message("gradum.toolwindow.git.analysis.load.more", item.remaining)
          )
        }

        is AuditTreeItem.Finding -> {
          val finding = item.finding
          val isReviewed = findingKey(finding) in reviewedFindings
          var isHovered by remember { mutableStateOf(value = false) }
          Tooltip(tooltip = {
            Text(text = "(${finding.code}) ${finding.formatBody()}")
          }) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .onPointerEvent(eventType = PointerEventType.Enter) { isHovered = true }
                .onPointerEvent(eventType = PointerEventType.Exit) { isHovered = false }
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
                    modifier = Modifier.onPointerEvent(eventType = PointerEventType.Enter) { isHovered = true }
                      .onPointerEvent(eventType = PointerEventType.Exit) { isHovered = false }
                  ) {
                    Icon(
                      contentDescription = null,
                      key = severityOutlineIcon(finding.level)
                    )
                    AnimatedVisibility(
                      visible = isHovered,
                      enter = expandHorizontally(
                        expandFrom = Alignment.Start, animationSpec = tween(durationMillis = 200)
                      ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                      exit = shrinkHorizontally(
                        shrinkTowards = Alignment.Start, animationSpec = tween(durationMillis = 150)
                      ) + fadeOut(animationSpec = tween(durationMillis = 150))
                    ) {
                      Text(
                        maxLines = 1,
                        text = message("gradum.toolwindow.git.analysis.reviewed"),
                        color = JewelTheme.globalColors.text.info
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
