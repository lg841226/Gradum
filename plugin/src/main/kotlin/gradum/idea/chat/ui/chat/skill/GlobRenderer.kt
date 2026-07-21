/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
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
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

class GlobRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Search

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val pattern: String = (arguments["pattern"] as? String).orEmpty()
    val totalFiles: Int = (result["total_files"] as? Number)?.toInt() ?: 0

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = linkedMapOf(
        "pattern" to pattern,
        "totalFiles" to totalFiles
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val pattern: String = (content.fieldMap["pattern"] as? String).orEmpty()
    val totalFiles: Int = (content.fieldMap["totalFiles"] as? Number)?.toInt() ?: 0
    val textColor = JewelTheme.globalColors.text.normal
    val infoColor = JewelTheme.globalColors.text.info
    val disabledColor = JewelTheme.globalColors.text.disabled

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(GradumIcons.Search, contentDescription = null)
      Text(
        color = textColor,
        text = message(LABEL_KEY),
        fontWeight = FontWeight.Medium
      )
      Text(
        text = pattern,
        maxLines = 1,
        color = infoColor,
        overflow = TextOverflow.Ellipsis,
        style = JewelTheme.editorTextStyle,
        modifier = Modifier.horizontalScroll(rememberScrollState())
      )
      Text(
        maxLines = 1,
        color = disabledColor,
        overflow = TextOverflow.Ellipsis,
        text = message(LABEL_KEY_DISPLAY, totalFiles)
      )
    }
  }

  companion object {
    const val ALIAS: String = "Glob"
    const val LABEL_KEY: String = "gradum.tool.glob"
    const val LABEL_KEY_DISPLAY: String = "gradum.tool.search.display"
  }
}
