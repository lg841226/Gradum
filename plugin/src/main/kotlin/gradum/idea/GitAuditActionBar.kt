/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GitAuditActionBar.kt  2026-08-25 22:12:07 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Reusable vertical action bar shown after a successful scan. Every action is
 * backed by [IconTooltipButton] with an internationalized tooltip and
 * accessibility description.
 */
@Composable
internal fun GitAuditActionBar(
  onClose: () -> Unit,
  onRefresh: () -> Unit,
  onTogglePreview: () -> Unit,
  onToggleReviewed: () -> Unit,
  onToggleExpandAll: () -> Unit,
  onToggleGroupBySeverity: () -> Unit,
  isAllExpanded: Boolean,
  enabled: Boolean = true,
  groupBySeverity: Boolean,
  isGroupingTransition: Boolean,
  reviewedFindings: Set<String>,
  selectedFinding: AuditFinding?,
  modifier: Modifier = Modifier
) {
  val currentTooltip = if (isAllExpanded) {
    message("gradum.toolwindow.git.analysis.action.collapse")
  } else {
    message("gradum.toolwindow.git.analysis.action.expand")
  }
  val previewEnabled = selectedFinding?.hasRealCommitHash() == true
  val scope = rememberCoroutineScope()
  var isCopied by remember { mutableStateOf(value = false) }
  val findings = GradumGitAnalysisService.auditFindings
  val findingsJson = remember(key1 = findings, key2 = reviewedFindings) {
    auditFindingsToJson(findings.filter { findingKey(it) !in reviewedFindings })
  }
  val groupingTooltip = if (groupBySeverity) {
    message("gradum.toolwindow.git.analysis.action.show.group.by.audit")
  } else {
    message("gradum.toolwindow.git.analysis.action.show.group.by.severity")
  }

  Column(modifier = modifier) {
    Column(
      modifier = Modifier.fillMaxHeight(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Spacer(Modifier.height(GradumSpacing.sml))
      IconTooltipButton(
        tooltip = message("gradum.toolwindow.git.analysis.action.close"),
        iconKey = AllIconsKeys.General.Close,
        onClick = onClose,
        enabled = enabled,
        contentDescription = message("gradum.toolwindow.git.analysis.action.close"),
        modifier = Modifier.iconButtonPadding()
      )
      IconTooltipButton(
        tooltip = message("gradum.toolwindow.git.analysis.action.refresh"),
        iconKey = AllIconsKeys.General.Refresh,
        onClick = onRefresh,
        enabled = enabled,
        contentDescription = message("gradum.toolwindow.git.analysis.action.refresh"),
        modifier = Modifier.iconButtonPadding()
      )
      IconTooltipButton(
        tooltip = message("gradum.toolwindow.git.analysis.action.preview"),
        iconKey = AllIconsKeys.General.LayoutEditorPreview,
        onClick = onTogglePreview,
        enabled = enabled && previewEnabled,
        contentDescription = message("gradum.toolwindow.git.analysis.action.preview"),
        modifier = Modifier.iconButtonPadding()
      )
      IconTooltipButton(
        tooltip = currentTooltip,
        iconKey = if (isAllExpanded) GradumIcons.CollapseAll else GradumIcons.ExpandAll,
        onClick = onToggleExpandAll,
        enabled = enabled,
        contentDescription = currentTooltip,
        modifier = Modifier.iconButtonPadding()
      )
      Tooltip(tooltip = { Text(text = groupingTooltip) }) {
        IconButton(
          onClick = onToggleGroupBySeverity,
          enabled = enabled && !isGroupingTransition,
          modifier = Modifier.iconButtonPadding()
        ) {
          if (isGroupingTransition) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp)
            )
          } else {
            Icon(
              key = AllIconsKeys.General.Show,
              contentDescription = groupingTooltip
            )
          }
        }
      }
      Tooltip(tooltip = {
        Text(text = message("gradum.toolwindow.git.analysis.action.mark.reviewed"))
      }) {
        IconButton(
          onClick = onToggleReviewed,
          enabled = enabled && selectedFinding != null,
          modifier = Modifier.iconButtonPadding()
        ) {
          Icon(
            key = AllIconsKeys.Toolwindows.ToolWindowBookmarks,
            contentDescription = message("gradum.toolwindow.git.analysis.action.mark.reviewed")
          )
        }
      }
      IconTooltipButton(
        tooltip = message("gradum.toolwindow.git.analysis.action.copy.json"),
        iconKey = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy,
        onClick = {
          copyToClipboard(
            text = findingsJson,
            onCopied = { isCopied = true },
            onReset = { isCopied = false },
            scope = scope
          )
        },
        enabled = enabled && findings.isNotEmpty(),
        contentDescription = message("gradum.toolwindow.git.analysis.action.copy.json"),
        modifier = Modifier.iconButtonPadding()
      )
      Spacer(Modifier.height(GradumSpacing.sml))
    }
  }
}

internal fun Modifier.iconButtonPadding() = this
  .padding(horizontal = GradumSpacing.sml)
  .padding(bottom = GradumSpacing.xs)
