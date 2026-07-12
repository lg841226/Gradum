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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * When the result carries `syntaxErrors` (the file compiled but
 * produced compiler issues) or the call itself failed (with an
 * `error.message`), an inline collapsible errors panel is rendered
 * next to the diff button. The panel mirrors
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
    val filePath: String = (arguments["path"] as? String).orEmpty()
    val linesAdded: Int = (result["linesAdded"] as? Number)?.toInt() ?: 0
    val linesRemoved: Int = (result["linesRemoved"] as? Number)?.toInt() ?: 0
    val originalContent: String? = result["originalContent"] as? String
    val modifiedContent: String? = result["modifiedContent"] as? String
    val hasDiffPayload: Boolean = originalContent != null && modifiedContent != null
    val isSuccess: Boolean = (result["success"] as? Boolean) ?: true
    @Suppress("UNCHECKED_CAST")
    val syntaxErrors: List<Map<String, Any?>> =
      (result["syntaxErrors"] as? List<Map<String, Any?>>) ?: emptyList()
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
        "syntaxErrors" to syntaxErrors,
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
    val fileName: String = filePath.substringAfterLast('/')
    val linesAdded: Int = (content.fieldMap["linesAdded"] as? Number)?.toInt() ?: 0
    val linesRemoved: Int = (content.fieldMap["linesRemoved"] as? Number)?.toInt() ?: 0
    val hasDiffPayload: Boolean = content.fieldMap["hasDiffPayload"] == true
    val isSuccess: Boolean = (content.fieldMap["isSuccess"] as? Boolean) ?: true
    val originalContent: String? = content.fieldMap["originalContent"] as? String
    val modifiedContent: String? = content.fieldMap["modifiedContent"] as? String
    val addedColor = linesAddedColor()
    val errorColor = toolCallErrorColor()
    val isError: Boolean = !isSuccess || ctx.isError

    val errors: List<SyntaxErrorEntry> = rememberSyntaxErrors(
      mapOf(
        "success" to isSuccess,
        "syntaxErrors" to content.fieldMap["syntaxErrors"],
        "error" to content.fieldMap["error"],
      )
    )

    // Auto-expand when the tool call failed so the model and the
    // user see the error without a second click; collapse by
    // default for a clean successful edit row.
    var errorsExpanded: Boolean by remember { mutableStateOf(isError) }

    Column {
      ToolCallCapsule(
        iconKey = GradumIcons.Edit,
        success = !isError,
        errorDetail = ctx.errorDetail.orEmpty(),
        errorMessage = ctx.errorDetail.orEmpty(),
        trailingText = fileName,
        label = message(LABEL_KEY),
        trailingIcon = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
          ) {
            if (linesAdded > 0) Text(text = "+$linesAdded", color = addedColor)
            if (linesRemoved > 0) Text(text = "-$linesRemoved", color = errorColor)
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
            if (errors.isNotEmpty()) {
              ErrorsToggleButton(
                errors = errors,
                isExpanded = errorsExpanded,
                onToggle = { errorsExpanded = !errorsExpanded }
              )
            }
          }
        }
      )

      ErrorsPanel(
        errors = errors,
        isExpanded = errorsExpanded,
        filePath = filePath,
        onLineClick = { line -> ctx.onOpenInEditor?.invoke(filePath, line, 0) }
      )
    }
  }

  companion object {
    const val ALIAS: String = "Edited"
    const val LABEL_KEY: String = "gradum.tool.edited"
  }
}
