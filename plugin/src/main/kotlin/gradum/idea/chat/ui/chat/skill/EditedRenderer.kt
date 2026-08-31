/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditedRenderer.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import gradum.idea.chat.ui.chat.skill.internal.*
import gradum.idea.chat.ui.chat.skill.spi.*
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `edit_file` skill (alias
 * "Edited"). Shows the file name, a `+linesAdded` / `-linesRemoved`
 * delta, and offers a `ViewDiff` action when the tool result carried
 * an `originalContent` + `modifiedContent` payload (so the chat panel
 * can launch an inline diff viewer).
 *
 * When the call itself failed (with an `error.message`), an inline
 * collapsible errors panel is rendered next to the diff button. The
 * panel mirrors
 * [gradum.idea.chat.ui.chat.ThinkingIndicator]'s "expand to reveal"
 * pattern: collapsed by default on a successful edit, auto-expanded
 * when the call failed so the model — and the user — see the
 * failure detail without an extra click.
 */
class EditedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Edit

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val filePath: String = arguments.string(key = "path")
    val linesAdded: Int = result.int(key = "linesAdded")
    val linesRemoved: Int = result.int(key = "linesRemoved")
    val originalContent: String? = result["originalContent"] as? String
    val modifiedContent: String? = result["modifiedContent"] as? String
    val hasDiffPayload: Boolean = originalContent != null && modifiedContent != null
    val isSuccess: Boolean = result.boolean(key = "success", defaultValue = true)
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
        "isSuccess" to isSuccess,
        "error" to result["error"],
        "originalContent" to originalContent,
        "modifiedContent" to modifiedContent
      ),
      actionList = actionList
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val filePath: String = (content.fieldMap["filePath"] as? String).orEmpty()
    val fileName: String = filePath.substringAfterLast(delimiter = '/')
    val linesAdded: Int = (content.fieldMap["linesAdded"] as? Number)?.toInt() ?: 0
    val linesRemoved: Int = (content.fieldMap["linesRemoved"] as? Number)?.toInt() ?: 0
    val hasDiffPayload: Boolean = content.fieldMap["hasDiffPayload"] == true
    val isSuccess: Boolean = (content.fieldMap["isSuccess"] as? Boolean) ?: true
    val originalContent: String? = content.fieldMap["originalContent"] as? String
    val modifiedContent: String? = content.fieldMap["modifiedContent"] as? String
    val addedColor = linesAddedColor()
    val errorColor = toolCallErrorColor()
    val isError: Boolean = !isSuccess || ctx.isError
    val bodyStyle = rememberGradumParagraphTextStyle()

    ToolCallCapsule(
      label = message(LABEL_KEY),
      iconKey = GradumIcons.Edit,
      success = !isError,
      trailingText = fileName,
      trailingIcon = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
        ) {
          if (linesAdded > 0) Text(text = "+$linesAdded", color = addedColor, style = bodyStyle)
          if (linesRemoved > 0) Text(text = "-$linesRemoved", color = errorColor, style = bodyStyle)
          if (!isError && hasDiffPayload && originalContent != null && modifiedContent != null) {
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
      },
      errorInfo = ToolCallErrorInfo(
        detail = ctx.errorDetail.orEmpty(),
        message = ctx.errorDetail.orEmpty(),
        toolDetails = ctx.toolDetails.orEmpty()
      )
    )
  }

  companion object {
    const val ALIAS: String = "Edited"
    const val LABEL_KEY: String = "gradum.tool.edited"
  }
}
