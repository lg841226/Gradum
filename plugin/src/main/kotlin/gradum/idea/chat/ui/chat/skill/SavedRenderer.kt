/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SavedRenderer.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import gradum.idea.chat.ui.chat.skill.internal.OpenInEditorButton
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.formatBytes
import gradum.idea.chat.ui.chat.skill.spi.ToolCallAction
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `save_file` skill (alias
 * "Saved"). Shows the file name plus a human-readable size suffix
 * (e.g. "Build.kt 4.2 KB") when the tool result included a
 * `sizeBytes` value, and offers an `OpenInEditor` action.
 */
class SavedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Save

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val filePath: String = (arguments["path"] as? String).orEmpty()
    val sizeBytes: Long = (result["sizeBytes"] as? Number)?.toLong() ?: 0L
    val actionList: MutableList<ToolCallAction> = mutableListOf()
    if (filePath.isNotBlank()) actionList.add(ToolCallAction.OpenInEditor(filePath = filePath))

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "filePath" to filePath,
        "sizeBytes" to sizeBytes
      ),
      actionList = actionList
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val filePath: String = (content.fieldMap["filePath"] as? String).orEmpty()
    val sizeBytes: Long = (content.fieldMap["sizeBytes"] as? Number)?.toLong() ?: 0L
    val fileName: String = filePath.substringAfterLast('/')
    val sizeText: String? = if (sizeBytes > 0L) formatBytes(sizeBytes) else null
    val displayText: String = if (sizeText != null) "$fileName $sizeText" else fileName

    ToolCallCapsule(
      label = message(LABEL_KEY),
      iconKey = GradumIcons.Save,
      success = !ctx.isError,
      errorDetail = ctx.errorDetail.orEmpty(),
      toolDetails = ctx.toolDetails.orEmpty(),
      errorMessage = ctx.errorDetail.orEmpty(),
      trailingText = displayText,
      trailingIcon = {
        OpenInEditorButton(
          filePath = filePath,
          onClick = {
            ctx.onOpenInEditor?.invoke(filePath, 0, 0)
          }
        )
      }
    )
  }

  companion object {
    const val ALIAS: String = "Saved"
    const val LABEL_KEY: String = "gradum.tool.saved"
  }
}
