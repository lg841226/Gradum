/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PlannedRenderer.kt  2026-08-07 16:04:18 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `to_do` skill when the LLM
 * is **adding** new tasks (alias "Planned"). Falls back to a
 * single-line capsule with a `taskCount` summary; expanded task
 * lists live in the chat's TodoSkill panel, not on the tool row.
 */
class PlannedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.NumberList

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    @Suppress("UNCHECKED_CAST")
    val taskList: List<String> = (result["tasks"] as? List<String>)
      ?: (arguments["tasks"] as? List<String>) ?: emptyList()
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "taskCount" to taskList.size,
        "firstTask" to taskList.firstOrNull().orEmpty()
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val taskCount: Int = (content.fieldMap["taskCount"] as? Number)?.toInt() ?: 0
    val firstTask: String = (content.fieldMap["firstTask"] as? String).orEmpty()
    val displayText: String = if (taskCount > 1) "$firstTask (+${taskCount - 1})" else firstTask

    ToolCallCapsule(
      success = !ctx.isError,
      errorDetail = ctx.errorDetail.orEmpty(),
      trailingText = displayText,
      errorMessage = ctx.errorDetail.orEmpty(),
      toolDetails = ctx.toolDetails.orEmpty(),
      label = message(LABEL_KEY),
      iconKey = GradumIcons.NumberList
    )
  }

  companion object {
    const val ALIAS: String = "Planned"
    const val LABEL_KEY: String = "gradum.tool.planned"
  }
}
