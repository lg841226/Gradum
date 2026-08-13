/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SearchedRenderer.kt  2026-08-13 20:18:35 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

class SearchedRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Web

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val query: String = (arguments["query"] as? String).orEmpty()
    val totalResults: Int = (result["results"] as? List<*>)?.size ?: 0

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = linkedMapOf(
        "query" to query,
        "totalResults" to totalResults
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val query: String = (content.fieldMap["query"] as? String).orEmpty()
    val totalResults: Int = (content.fieldMap["totalResults"] as? Number)?.toInt() ?: 0
    val textColor = JewelTheme.globalColors.text.normal
    val infoColor = JewelTheme.globalColors.text.info
    val disabledColor = JewelTheme.globalColors.text.disabled

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(GradumIcons.Web, contentDescription = null)
      Text(
        color = textColor,
        text = message(LABEL_KEY),
        fontWeight = FontWeight.Medium
      )
      Text(
        text = query,
        maxLines = 1,
        color = infoColor,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.horizontalScroll(rememberScrollState())
      )
      Text(
        maxLines = 1,
        color = disabledColor,
        overflow = TextOverflow.Ellipsis,
        text = message(LABEL_KEY_DISPLAY, totalResults)
      )
    }
  }

  companion object {
    const val ALIAS: String = "Searched"
    const val LABEL_KEY: String = "gradum.tool.searched"
    const val LABEL_KEY_DISPLAY: String = "gradum.tool.search.web.display"
  }
}
