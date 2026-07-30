/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CompletedRenderer.kt  2026-07-29 18:32:58 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Default renderer for the server-side `to_do` skill when the LLM
 * **marks a task done** (alias "Completed"). Single-line capsule
 * with the completed task name.
 */
class CompletedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = AllIconsKeys.Actions.Checked

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
    val taskName: String = (arguments["task"] as? String)
      ?: (result["task"] as? String)
      ?: (arguments["content"] as? String)
      ?: ""
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf("task" to taskName)
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val taskName: String = (content.fieldMap["task"] as? String).orEmpty()
    ToolCallCapsule(
      success = !ctx.isError,
      errorDetail = ctx.errorDetail.orEmpty(),
      trailingText = taskName,
      errorMessage = ctx.errorDetail.orEmpty(),
      toolDetails = ctx.toolDetails.orEmpty(),
      label = message(LABEL_KEY),
      iconKey = AllIconsKeys.Actions.Checked
    )
  }

  companion object {
    const val ALIAS: String = "Completed"
    const val LABEL_KEY: String = "gradum.tool.completed"
  }
}
