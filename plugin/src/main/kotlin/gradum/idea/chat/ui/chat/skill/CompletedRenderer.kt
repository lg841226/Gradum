/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CompletedRenderer.kt  2026-08-25 19:25:05 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Default renderer for the server-side `finish_to_do_item` skill
 * (alias "Completed"). Renders a collapsible capsule header (green
 * checkmark + "Completed" + just-completed task name + chevron),
 * mirroring the [PlannedRenderer] row pattern. Expanding the header
 * reveals the full task list with the same per-task states as the
 * plan list:
 * - Completed task (index < `currentIndex`): green checkmark.
 * - Current task (index == `currentIndex`): spinner.
 * - Pending task: 1-based sequence number in the editor font.
 */
class CompletedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = AllIconsKeys.Actions.Report

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
    val taskName: String = (arguments["task"] as? String)
      ?: (result["currentTask"] as? String)
      ?: (result["skippedTask"] as? String)
      ?: (result["task"] as? String)
      ?: (arguments["content"] as? String)
      ?: ""

    @Suppress("UNCHECKED_CAST")
    val tasks: List<String> = (result["tasks"] as? List<String>) ?: emptyList()
    val currentIndex: Int = (result["currentIndex"] as? Number)?.toInt() ?: 0
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "task" to taskName,
        "tasks" to tasks,
        "currentIndex" to currentIndex,
        "totalTasks" to tasks.size
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    @Suppress("UNCHECKED_CAST")
    val tasks: List<String> = (content.fieldMap["tasks"] as? List<String>) ?: emptyList()
    val currentIndex: Int = (content.fieldMap["currentIndex"] as? Number)?.toInt() ?: 0
    val taskName: String = (content.fieldMap["task"] as? String).orEmpty()
    val textColor = JewelTheme.globalColors.text.normal
    val infoColor = JewelTheme.globalColors.text.info
    val dimmerColor = JewelTheme.globalColors.text.disabled
    val bodyStyle = rememberGradumParagraphTextStyle()

    var isExpanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
      ) {
        Icon(
          contentDescription = null,
          key = AllIconsKeys.Actions.Report
        )
        Text(
          color = textColor,
          style = bodyStyle,
          text = message(LABEL_KEY),
          fontWeight = FontWeight.Medium
        )
        if (taskName.isNotBlank()) {
          Text(
            maxLines = 1,
            text = taskName,
            color = infoColor,
            style = bodyStyle,
            overflow = TextOverflow.Ellipsis
          )
        }
        Icon(
          key =
            if (isExpanded) AllIconsKeys.General.ChevronDown
            else AllIconsKeys.General.ChevronRight,
          contentDescription = null
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        if (tasks.isNotEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
          ) {
            Spacer(modifier = Modifier.height(GradumSpacing.sml))
            tasks.forEachIndexed { index, task ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
              ) {
                when {
                  index < currentIndex ->
                    Icon(
                      contentDescription = null,
                      key = AllIconsKeys.General.GreenCheckmark
                    )

                  index == currentIndex ->
                    Icon(
                      contentDescription = null,
                      key = AllIconsKeys.Vcs.Arrow_right
                    )

                  else -> Text(
                    color = infoColor,
                    text = "${index + 1}.",
                    style = JewelTheme.editorTextStyle
                  )
                }
                Text(
                  text = task,
                  maxLines = 1,
                  style = bodyStyle,
                  color = if (index < currentIndex) dimmerColor else textColor,
                  overflow = TextOverflow.Ellipsis,
                  textDecoration = if (index < currentIndex) TextDecoration.LineThrough else null
                )
              }
            }
          }
        }
      }
    }
  }

  companion object {
    const val ALIAS: String = "Completed"
    const val LABEL_KEY: String = "gradum.tool.completed"
  }
}
