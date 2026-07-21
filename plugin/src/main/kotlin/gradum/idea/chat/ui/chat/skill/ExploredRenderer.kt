/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.icons.GradumIcons
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
    val projectRoot: String = (arguments["projectRoot"] as? String)
      ?: (arguments["project_root"] as? String) ?: ""
    val scanDepth: Int = (result["depth"] as? Number)?.toInt() ?: 0
    val projectName: String = projectRoot.substringAfterLast('/')
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "projectRoot" to projectRoot,
        "depth" to scanDepth,
        "projectName" to projectName
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val scanDepth: Int = (content.fieldMap["depth"] as? Number)?.toInt() ?: 0
    val displayText: String = message("gradum.tool.depth", scanDepth)

    ToolCallCapsule(
      success = !ctx.isError,
      errorDetail = ctx.errorDetail.orEmpty(),
      trailingText = displayText,
      errorMessage = ctx.errorDetail.orEmpty(),
      toolDetails = ctx.toolDetails.orEmpty(),
      label = message(LABEL_KEY),
      iconKey = GradumIcons.Explore
    )
  }

  companion object {
    const val ALIAS: String = "Explored"
    const val LABEL_KEY: String = "gradum.tool.explored"
  }
}
