/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumGitAnalysisToolWindowFactory.kt  2026-07-31 18:57:47 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.markdown.rememberGradumMarkdownStyling
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Factory for creating the Gradum Git Analysis tool window.
 *
 * Docked at the bottom of the IDE, next to the Problems panel.
 */
@OptIn(ExperimentalJewelApi::class)
class GradumGitAnalysisToolWindowFactory : ToolWindowFactory {

  @Suppress("UnstableApiUsage")
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    toolWindow.addComposeTab(message("gradum.toolwindow.git.analysis")) {
      SwingBridgeTheme {
        val styling = rememberGradumMarkdownStyling()
        val bullet = styling.list.unordered.bullet ?: '\u2022'
        val bulletStyle: TextStyle = styling.list.unordered.bulletStyle
        val contentStyle: TextStyle = styling.paragraph.inlinesStyling.textStyle
        val scanState = GradumGitAnalysisService.scanState
        val infoTextColor = LocalGlobalColors.current.text.info

        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          when (scanState) {
            GradumGitAnalysisService.ScanState.SCANNING -> {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
              ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                  color = infoTextColor,
                  text = message("gradum.toolwindow.git.analysis.scanning"),
                  style = JewelTheme.typography.regular
                )
                Spacer(Modifier.height(GradumSpacing.sm))
                OutlinedButton(onClick = { GradumGitAnalysisService.cancelScan() }) {
                  Text(message("gradum.toolwindow.git.analysis.cancel"))
                }
              }
            }

            GradumGitAnalysisService.ScanState.FAILED -> {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
              ) {
                Text(
                  color = infoTextColor,
                  text = message("gradum.toolwindow.git.analysis.interrupted")
                )
                GradumGitAnalysisService.lastError?.let { error ->
                  Text(
                    text = error,
                    color = infoTextColor,
                    textAlign = TextAlign.Center
                  )
                }
                Spacer(Modifier.height(GradumSpacing.sm))
                OutlinedButton(onClick = { GradumGitAnalysisService.cancelScan() }) {
                  Text(message("gradum.toolwindow.git.analysis.back"))
                }
              }
            }

            GradumGitAnalysisService.ScanState.SUCCESS -> {
              Box(Modifier.fillMaxSize()) {
                GitAuditActionBar(modifier = Modifier.align(Alignment.TopStart))
                Text(
                  color = infoTextColor,
                  text = message(
                    "gradum.toolwindow.git.analysis.success",
                    GradumGitAnalysisService.totalCommits
                  ),
                  modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = GradumSpacing.lg),
                  textAlign = TextAlign.Center
                )
              }
            }

            else -> {
              Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
                ) {
                  Icon(
                    contentDescription = null,
                    key = GradumIcons.ColorLogo,
                    modifier = Modifier.size(28.dp)
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
                    onClick = {},
                    text = message("gradum.toolwindow.git.analysis.view.full")
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}


/**
 * Reusable vertical action bar shown after a successful scan. Every action is
 * backed by [gradum.idea.chat.ui.common.IconTooltipButton] with an
 * internationalized tooltip and accessibility description.
 */
@androidx.compose.runtime.Composable
private fun GitAuditActionBar(modifier: Modifier = Modifier) {
  val lineColor = JewelTheme.globalColors.borders.normal
  var isAllExpanded by remember { mutableStateOf(false) }
  val currentTooltip = if (isAllExpanded) {
    message("gradum.toolwindow.git.analysis.action.collapse")
  } else {
    message("gradum.toolwindow.git.analysis.action.expand")
  }

  Column(modifier = modifier) {
    Row {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(GradumSpacing.sml))
        IconTooltipButton(
          tooltip = message("gradum.toolwindow.git.analysis.action.close"),
          iconKey = AllIconsKeys.General.Close,
          contentDescription = message("gradum.toolwindow.git.analysis.action.close"),
          onClick = {},
          modifier = Modifier.iconButtonPadding()
        )
        IconTooltipButton(
          tooltip = message("gradum.toolwindow.git.analysis.action.refresh"),
          iconKey = AllIconsKeys.General.Refresh,
          contentDescription = message("gradum.toolwindow.git.analysis.action.refresh"),
          onClick = {},
          modifier = Modifier.iconButtonPadding()
        )
        IconTooltipButton(
          tooltip = message("gradum.toolwindow.git.analysis.action.preview"),
          iconKey = AllIconsKeys.General.LayoutEditorPreview,
          contentDescription = message("gradum.toolwindow.git.analysis.action.preview"),
          onClick = {},
          enabled = false,
          modifier = Modifier.iconButtonPadding()
        )
        IconTooltipButton(
          tooltip = message("gradum.toolwindow.git.analysis.action.show"),
          iconKey = AllIconsKeys.General.Show,
          contentDescription = message("gradum.toolwindow.git.analysis.action.show"),
          onClick = {},
          enabled = false,
          modifier = Modifier.iconButtonPadding()
        )
        IconTooltipButton(
          tooltip = currentTooltip,
          iconKey = if (isAllExpanded) GradumIcons.ExpandAll else GradumIcons.CollapseAll,
          contentDescription = currentTooltip,
          onClick = { isAllExpanded = !isAllExpanded },
          enabled = false,
          modifier = Modifier.iconButtonPadding()
        )
      }
      Box(
        modifier = Modifier
          .width(1.dp)
          .fillMaxHeight()
          .background(lineColor)
      )
    }
  }
}

private fun Modifier.iconButtonPadding() = this
  .padding(horizontal = GradumSpacing.sml)
  .padding(bottom = GradumSpacing.xs)

/**
 * Renders the `-` markdown list as plain Compose rows, matching the chat's
 * unordered-list style from `BlockRenderer`: a `•` bullet in
 * `globalColors.text.info`, a 20 dp right-aligned marker column with an
 * `sm` gap, and `md` vertical spacing between items.
 */
@androidx.compose.runtime.Composable
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
      Row(
        verticalAlignment = Alignment.Top,
      ) {
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
