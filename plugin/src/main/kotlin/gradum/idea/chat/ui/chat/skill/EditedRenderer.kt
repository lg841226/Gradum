/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditedRenderer.kt  2026-07-09 18:40:20 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.skill.internal.*
import gradum.idea.chat.ui.chat.skill.spi.ToolCallAction
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `edit_file` skill (alias
 * "Edited"). Shows the file name, a `+linesAdded` / `-linesRemoved`
 * delta, and offers a `ViewDiff` action when the tool result carried
 * an `originalContent` + `modifiedContent` payload (so the chat panel
 * can launch an inline diff viewer).
 */
class EditedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Edit

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val filePath: String = (arguments["path"] as? String).orEmpty()
    val linesAdded: Int = (result["linesAdded"] as? Number)?.toInt() ?: 0
    val linesRemoved: Int = (result["linesRemoved"] as? Number)?.toInt() ?: 0
    val originalContent: String? = result["originalContent"] as? String
    val modifiedContent: String? = result["modifiedContent"] as? String
    val hasDiffPayload: Boolean = originalContent != null && modifiedContent != null
    val actionList: MutableList<ToolCallAction> = mutableListOf()
    if (filePath.isNotBlank()) actionList.add(ToolCallAction.OpenInEditor(filePath = filePath))
    if (hasDiffPayload) actionList.add(ToolCallAction.ViewDiff(filePath = filePath, diffType = "default"))

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "filePath" to filePath,
        "linesAdded" to linesAdded,
        "linesRemoved" to linesRemoved,
        "hasDiffPayload" to hasDiffPayload,
        "originalContent" to originalContent,
        "modifiedContent" to modifiedContent
      ),
      actionList = actionList
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val filePath: String = (content.fieldMap["filePath"] as? String).orEmpty()
    val fileName: String = filePath.substringAfterLast('/')
    val linesAdded: Int = (content.fieldMap["linesAdded"] as? Number)?.toInt() ?: 0
    val linesRemoved: Int = (content.fieldMap["linesRemoved"] as? Number)?.toInt() ?: 0
    val hasDiffPayload: Boolean = content.fieldMap["hasDiffPayload"] == true
    val originalContent: String? = content.fieldMap["originalContent"] as? String
    val modifiedContent: String? = content.fieldMap["modifiedContent"] as? String
    val addedColor = linesAddedColor()
    val errorColor = toolCallErrorColor()

    ToolCallCapsule(
      iconKey = GradumIcons.Edit,
      success = !ctx.isError,
      errorDetail = ctx.errorDetail.orEmpty(),
      trailingText = fileName,
      errorMessage = ctx.errorDetail.orEmpty(),
      label = message(LABEL_KEY),
      trailingIcon = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
        ) {
          if (linesAdded > 0) Text(text = "+$linesAdded", color = addedColor)
          if (linesRemoved > 0) Text(text = "-$linesRemoved", color = errorColor)
          if (!ctx.isError && hasDiffPayload && originalContent != null && modifiedContent != null) {
            ViewDiffButton(
              onClick = {
                ctx.onViewDiff?.invoke(filePath, originalContent, modifiedContent)
              }
            )
          } else {
            OpenInEditorButton(
              filePath = filePath,
              onClick = {
                ctx.onOpenInEditor?.invoke(filePath, 0, 0)
              }
            )
          }
        }
      }
    )
  }

  companion object {
    const val ALIAS: String = "Edited"
    const val LABEL_KEY: String = "gradum.tool.edited"
  }
}
