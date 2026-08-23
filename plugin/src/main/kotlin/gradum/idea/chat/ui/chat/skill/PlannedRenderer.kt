/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PlannedRenderer.kt  2026-08-23 13:59:00 Changed by gwy
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
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Renderer for the server-side `to_do` skill (alias "Planned").
 *
 * Renders a collapsible header row (icon + "Planned" + task count +
 * chevron), matching the web-search row pattern: the task list
 * expands/collapses under the header.
 *
 * Task states inside the expanded list:
 * - Current task (index == `currentIndex`): spinner
 *   ([CircularProgressIndicator]).
 * - Completed task (index < `currentIndex`): green checkmark
 *   ([AllIconsKeys.General.GreenCheckmark]).
 * - Pending task: plain bullet.
 */
class PlannedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.BulletList

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
    @Suppress("UNCHECKED_CAST")
    val taskList: List<String> = (result["tasks"] as? List<String>)
      ?: (arguments["tasks"] as? List<String>) ?: emptyList()
    val currentIndex: Int = (result["currentIndex"] as? Number)?.toInt() ?: 0
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "tasks" to taskList,
        "currentIndex" to currentIndex,
        "totalTasks" to taskList.size,
        "firstTask" to taskList.firstOrNull().orEmpty()
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    @Suppress("UNCHECKED_CAST")
    val tasks: List<String> = (content.fieldMap["tasks"] as? List<String>) ?: emptyList()
    val currentIndex: Int = (content.fieldMap["currentIndex"] as? Number)?.toInt() ?: 0
    val firstTask: String = (content.fieldMap["firstTask"] as? String).orEmpty()
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
        Icon(GradumIcons.BulletList, contentDescription = null)
        Text(
          color = textColor,
          style = bodyStyle,
          text = message(LABEL_KEY),
          fontWeight = FontWeight.Medium
        )
        Text(
          maxLines = 1,
          text = firstTask,
          color = infoColor,
          style = bodyStyle,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          maxLines = 1,
          style = bodyStyle,
          color = dimmerColor,
          overflow = TextOverflow.Ellipsis,
          text = message(LABEL_KEY_LISTS, tasks.size)
        )
        Icon(
          contentDescription = null,
          key = if (isExpanded) AllIconsKeys.General.ChevronDown
          else AllIconsKeys.General.ChevronRight
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
                    CircularProgressIndicator(
                      modifier = Modifier.size(16.dp)
                    )

                  else ->
                    Text(
                      color = infoColor,
                      text = "${index + 1}.",
                      style = JewelTheme.editorTextStyle
                    )
                }
                Text(
                  text = task,
                  maxLines = 1,
                  style = bodyStyle,
                  overflow = TextOverflow.Ellipsis,
                  color = if (index < currentIndex) dimmerColor else textColor,
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
    const val ALIAS: String = "Planned"
    const val LABEL_KEY: String = "gradum.tool.planned"
    const val LABEL_KEY_LISTS: String = "gradum.tool.planned.lists"
  }
}
