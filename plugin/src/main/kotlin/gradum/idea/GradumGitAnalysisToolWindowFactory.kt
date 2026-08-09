/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumGitAnalysisToolWindowFactory.kt  2026-08-08 12:24:04 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import gradum.idea.chat.ui.markdown.rememberGradumMarkdownStyling
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import kotlin.time.Duration.Companion.milliseconds

private const val BANNER_ANIMATION_DURATION_MS = 300

/**
 * Factory for creating the Gradum Git Analysis tool window.
 *
 * Docked at the bottom of the IDE, next to the Problems panel.
 */
class GradumGitAnalysisToolWindowFactory : ToolWindowFactory {

  @Suppress("UnstableApiUsage")
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    toolWindow.addComposeTab(message("gradum.toolwindow.git.analysis")) {
      SwingBridgeTheme {
        val styling = rememberGradumMarkdownStyling()
        val scanState = GradumGitAnalysisService.scanState
        val bullet = styling.list.unordered.bullet ?: '\u2022'
        val infoTextColor = LocalGlobalColors.current.text.info
        val bulletStyle: TextStyle = styling.list.unordered.bulletStyle
        val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
        val groupingScope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }
        var isAllExpanded by remember { mutableStateOf(false) }
        var showCommitInfo by remember { mutableStateOf(false) }
        var groupBySeverity by remember { mutableStateOf(false) }
        var isGroupingTransition by remember { mutableStateOf(false) }
        var reviewedFindings by remember { mutableStateOf(setOf<String>()) }
        var selectedFinding by remember { mutableStateOf<AuditFinding?>(null) }

        DisposableEffect(project, toolWindow) {
          val connection = project.messageBus.connect()
          connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shownToolWindow: ToolWindow) {
              if (shownToolWindow.id != toolWindow.id) return
              ApplicationManager.getApplication().invokeLater {
                focusRequester.requestFocus()
              }
            }
          })
          onDispose { connection.dispose() }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusTarget()
        ) {
          if (scanState != GradumGitAnalysisService.ScanState.IDLE) {
            val bannerDismissed = GradumGitAnalysisService.bannerDismissed
            var bannerClosed by remember(scanState) { mutableStateOf(false) }
            val qualityBand = GradumGitAnalysisService.qualityBand
            Column(modifier = Modifier.fillMaxSize()) {
              if (qualityBand != null) {
                val bandLabel = when (qualityBand) {
                  "Excellent" -> message("gradum.toolwindow.git.analysis.band.excellent")
                  "Good" -> message("gradum.toolwindow.git.analysis.band.good")
                  "Fair" -> message("gradum.toolwindow.git.analysis.band.fair")
                  "Needs Attention" -> message("gradum.toolwindow.git.analysis.band.needs.attention")
                  "Caution" -> message("gradum.toolwindow.git.analysis.band.caution")
                  else -> qualityBand
                }
                val scanTime = scanCompletedAgo(GradumGitAnalysisService.scanCompletedAt)
                val bannerText = if (scanTime.isNotBlank()) {
                  message("gradum.toolwindow.git.analysis.banner.complete.with.time", bandLabel, scanTime)
                } else {
                  message("gradum.toolwindow.git.analysis.banner.complete", bandLabel)
                }
                AnimatedVisibility(
                  visible = !bannerDismissed && !bannerClosed,
                  enter = slideInVertically(
                    animationSpec = tween(BANNER_ANIMATION_DURATION_MS),
                    initialOffsetY = { -it }
                  ) + fadeIn(animationSpec = tween(BANNER_ANIMATION_DURATION_MS))
                ) {
                  GradumBanner(
                    text = bannerText,
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = GradumSpacing.sml),
                    icon = {
                      Icon(
                        contentDescription = null,
                        key = AllIconsKeys.Status.Success
                      )
                    },
                    linkContent = {
                      Link(
                        text = message("gradum.toolwindow.git.analysis.banner.dismiss"),
                        onClick = { GradumGitAnalysisService.bannerDismissed = true }
                      )
                    },
                    iconContent = {
                      Tooltip(
                        tooltip = {
                          Text(text = message("gradum.toolwindow.git.analysis.banner.close"))
                        }
                      ) {
                        IconButton(onClick = { bannerClosed = true }) {
                          Icon(
                            AllIconsKeys.General.Close,
                            contentDescription = message("gradum.toolwindow.git.analysis.banner.close"),
                            modifier = Modifier.size(16.dp)
                          )
                        }
                      }
                    }
                  )
                }
              }
              Row(modifier = Modifier.weight(1f).fillMaxSize()) {
                GitAuditActionBar(
                  onClose = { GradumGitAnalysisService.goHome() },
                  onRefresh = { GradumGitAnalysisService.startScan(project) },
                  onTogglePreview = { showCommitInfo = !showCommitInfo },
                  onToggleReviewed = {
                    if (selectedFinding != null) {
                      val key = findingKey(selectedFinding!!)
                      reviewedFindings = if (key in reviewedFindings) reviewedFindings - key
                      else reviewedFindings + key
                    }
                  },
                  onToggleExpandAll = { isAllExpanded = !isAllExpanded },
                  onToggleGroupBySeverity = {
                    if (!isGroupingTransition) {
                      isGroupingTransition = true
                      groupingScope.launch {
                        delay(300.milliseconds)
                        groupBySeverity = !groupBySeverity
                        isGroupingTransition = false
                      }
                    }
                  },
                  isAllExpanded = isAllExpanded,
                  enabled = scanState != GradumGitAnalysisService.ScanState.SCANNING,
                  groupBySeverity = groupBySeverity,
                  isGroupingTransition = isGroupingTransition,
                  reviewedFindings = reviewedFindings,
                  selectedFinding = selectedFinding,
                  modifier = Modifier.fillMaxHeight()
                )
                Box(modifier = Modifier.weight(1f)) {
                  when (scanState) {
                    GradumGitAnalysisService.ScanState.SCANNING -> {
                      Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                      ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(GradumSpacing.sml))
                        Text(
                          color = infoTextColor,
                          text = message("gradum.toolwindow.git.analysis.scanning"),
                          style = JewelTheme.typography.regular
                        )
                        Spacer(Modifier.height(GradumSpacing.md))
                        OutlinedButton(onClick = { GradumGitAnalysisService.cancelScan() }) {
                          Text(message("gradum.toolwindow.git.analysis.cancel"))
                        }
                      }
                    }

                    GradumGitAnalysisService.ScanState.FAILED -> {
                      Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                      ) {
                        Text(
                          color = infoTextColor,
                          text = message("gradum.toolwindow.git.analysis.interrupted")
                        )
                        GradumGitAnalysisService.lastErrorMessage?.let { error ->
                          Spacer(Modifier.height(GradumSpacing.sml))
                          Text(
                            text = error,
                            color = infoTextColor,
                            textAlign = TextAlign.Center
                          )
                        }
                        Spacer(Modifier.height(GradumSpacing.md))
                        OutlinedButton(onClick = { GradumGitAnalysisService.goHome() }) {
                          Text(message("gradum.toolwindow.git.analysis.back"))
                        }
                      }
                    }

                    GradumGitAnalysisService.ScanState.SUCCESS -> {
                      var commitInfoWidth by remember { mutableStateOf(CommitInfoPanelWidth) }
                      var pinnedFinding by remember { mutableStateOf<AuditFinding?>(null) }
                      val displayFinding = pinnedFinding ?: selectedFinding
                      Row(modifier = Modifier.fillMaxSize()) {
                        AuditFindingsTree(
                          isAllExpanded = isAllExpanded,
                          groupBySeverity = groupBySeverity,
                          reviewedFindings = reviewedFindings,
                          modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { selectedFinding = it }
                        if (showCommitInfo && displayFinding?.hasRealCommitHash() == true) {
                          CommitInfoPanelResizeHandle(
                            commitInfoWidth = commitInfoWidth,
                            onResize = { commitInfoWidth = it },
                            modifier = Modifier.fillMaxHeight()
                          )
                          CommitInfoPanel(
                            finding = displayFinding,
                            project = project,
                            isPinned = pinnedFinding != null,
                            onTogglePin = {
                              pinnedFinding = if (pinnedFinding == null) displayFinding else null
                            },
                            onToggleReviewed = {
                              val key = findingKey(displayFinding)
                              reviewedFindings = if (key in reviewedFindings) reviewedFindings - key
                              else reviewedFindings + key
                            },
                            modifier = Modifier
                              .fillMaxHeight()
                              .width(commitInfoWidth)
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          } else {
            Column(
              horizontalAlignment = Alignment.Start,
              modifier = Modifier.align(Alignment.Center),
              verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
              ) {
                Icon(
                  contentDescription = null,
                  key = GradumIcons.ColorLogo,
                  modifier = Modifier.size(26.dp)
                )
                Text(
                  style = JewelTheme.typography.h4TextStyle,
                  text = message("gradum.toolwindow.git.analysis.title")
                )
              }
              GitAuditFeatureList(
                bullet = bullet,
                bulletStyle = bulletStyle,
                contentStyle = contentStyle
              )
              Spacer(modifier = Modifier.height(GradumSpacing.sm))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
              ) {
                DefaultButton(onClick = { GradumGitAnalysisService.startScan(project) }) {
                  Text(message("gradum.toolwindow.git.analysis.begin"))
                }
                ExternalLink(
                  onClick = {}, text = message("gradum.toolwindow.git.analysis.view.full")
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Renders the `-` markdown list as plain Compose rows, matching the chat's
 * unordered-list style from `BlockRenderer`: a `•` bullet in
 * `globalColors.text.info`, a 20 dp right-aligned marker column with an
 * `sm` gap, and `md` vertical spacing between items.
 */
@Composable
private fun GitAuditFeatureList(
  bullet: Char, bulletStyle: TextStyle, contentStyle: TextStyle
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    listOf(
      message("gradum.toolwindow.git.analysis.feature.0"),
      message("gradum.toolwindow.git.analysis.feature.1"),
      message("gradum.toolwindow.git.analysis.feature.2"),
    ).forEach { item ->
      Row(verticalAlignment = Alignment.Top) {
        Box(
          contentAlignment = Alignment.CenterEnd,
          modifier = Modifier.padding(end = GradumSpacing.sm)
        ) {
          Text(
            maxLines = 1,
            softWrap = false,
            style = bulletStyle,
            text = bullet.toString(),
            textAlign = TextAlign.End
          )
        }
        Text(
          text = item,
          style = contentStyle
        )
      }
    }
  }
}
