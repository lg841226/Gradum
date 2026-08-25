/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploredRenderer.kt  2026-08-25 19:29:24 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.ToolCallErrorInfo
import gradum.idea.chat.ui.chat.skill.spi.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `explore_project` skill
 * (alias "Explored"). Shows the project directory's basename and
 * a "depth N" suffix indicating how many levels the scan went.
 */
class ExploredRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Explore

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val projectRoot: String = arguments.string(key = "projectRoot")
      .ifEmpty { arguments.string(key = "project_root") }
    val scanDepth: Int = result.int(key = "depth")
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "projectRoot" to projectRoot,
        "depth" to scanDepth
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val scanDepth: Int = (content.fieldMap["depth"] as? Number)?.toInt() ?: 0
    val displayText: String = message("gradum.tool.depth", scanDepth)

    ToolCallCapsule(
      label = message(LABEL_KEY),
      iconKey = GradumIcons.Explore,
      success = !ctx.isError,
      trailingText = displayText,
      errorInfo = ToolCallErrorInfo(
        detail = ctx.errorDetail.orEmpty(),
        message = ctx.errorDetail.orEmpty(),
        toolDetails = ctx.toolDetails.orEmpty()
      )
    )
  }

  companion object {
    const val ALIAS: String = "Explored"
    const val LABEL_KEY: String = "gradum.tool.explored"
  }
}
