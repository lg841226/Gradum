/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadRenderer.kt  2026-08-04 00:38:23 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.skill.internal.OpenInEditorButton
import gradum.idea.chat.ui.chat.skill.spi.ToolCallAction
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Default renderer for the server-side `read_file` skill (alias
 * "Read"). Shows the file name and an optional `lines N-M` suffix
 * (resolved from the `lineRange` field in the tool result, e.g.
 * `"12-37"`), and offers an `OpenInEditor` action that jumps to
 * the start line.
 */
class ReadRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = AllIconsKeys.General.Show

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val filePath: String = (arguments["path"] as? String).orEmpty()
    val startLine: Int? = (result["startLine"] as? Number)?.toInt()
      ?: parseLineRangeStart(result["lineRange"] as? String)
    val endLine: Int? = (result["endLine"] as? Number)?.toInt()
      ?: parseLineRangeEnd(result["lineRange"] as? String)
    val actionList: MutableList<ToolCallAction> = mutableListOf()
    if (filePath.isNotBlank()) {
      actionList.add(
        ToolCallAction.OpenInEditor(
          filePath = filePath,
          endLine = endLine ?: 0,
          startLine = startLine ?: 0
        )
      )
    }
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "endLine" to endLine,
        "filePath" to filePath,
        "startLine" to startLine
      ),
      actionList = actionList
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val filePath: String = (content.fieldMap["filePath"] as? String).orEmpty()
    val startLine: Int? = (content.fieldMap["startLine"] as? Number)?.toInt()
    val endLine: Int? = (content.fieldMap["endLine"] as? Number)?.toInt()
    val fileName: String = filePath.substringAfterLast('/')
    val lineText: String = when {
      startLine != null && endLine != null -> message("gradum.tool.line.range", startLine, endLine)
      startLine != null -> message("gradum.tool.line.single", startLine)
      else -> ""
    }
    val textColor = JewelTheme.globalColors.text.normal
    val infoColor = JewelTheme.globalColors.text.info
    val disabledColor = JewelTheme.globalColors.text.disabled

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(AllIconsKeys.General.Show, contentDescription = null)
      Text(
        color = textColor,
        text = message(LABEL_KEY),
        fontWeight = FontWeight.Medium
      )
      Text(
        maxLines = 1,
        text = fileName,
        color = infoColor,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.horizontalScroll(rememberScrollState())
      )
      if (lineText.isNotEmpty()) {
        Text(
          maxLines = 1,
          text = lineText,
          color = disabledColor,
          overflow = TextOverflow.Ellipsis
        )
      }
      OpenInEditorButton(
        filePath = filePath,
        onClick = {
          ctx.onOpenInEditor?.invoke(
            filePath,
            (startLine ?: 0).coerceAtLeast(1),
            (endLine ?: 0).coerceAtLeast(1)
          )
        }
      )
    }
  }

  private fun parseLineRangeStart(lineRange: String?): Int? =
    parseLineRangePair(lineRange).first

  private fun parseLineRangeEnd(lineRange: String?): Int? =
    parseLineRangePair(lineRange).second

  private fun parseLineRangePair(lineRange: String?): Pair<Int?, Int?> {
    if (lineRange.isNullOrBlank()) return null to null
    val rangeParts: List<String> = lineRange.split("-")
    return when (rangeParts.size) {
      2 -> rangeParts[0].toIntOrNull() to rangeParts[1].toIntOrNull()
      1 -> rangeParts[0].toIntOrNull() to null
      else -> null to null
    }
  }

  companion object {
    const val ALIAS: String = "Read"
    const val LABEL_KEY: String = "gradum.tool.read"
  }
}
