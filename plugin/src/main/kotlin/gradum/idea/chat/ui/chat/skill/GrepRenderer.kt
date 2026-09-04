/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GrepRenderer.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import gradum.idea.chat.ui.chat.skill.spi.*
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

class GrepRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun iconKey(): IconKey = GradumIcons.Search

  override fun labelKey(): String = LABEL_KEY

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val pattern: String = arguments.string(key = "pattern")
    val totalMatches: Int = result.int(key = "total_matches")

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = linkedMapOf(
        "pattern" to pattern,
        "totalMatches" to totalMatches
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val pattern: String = (content.fieldMap["pattern"] as? String).orEmpty()
    val totalMatches: Int = (content.fieldMap["totalMatches"] as? Number)?.toInt() ?: 0
    val textColor = JewelTheme.globalColors.text.normal
    val infoColor = JewelTheme.globalColors.text.info
    val disabledColor = JewelTheme.globalColors.text.disabled
    val bodyStyle = rememberGradumParagraphTextStyle()

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        key = GradumIcons.Search,
        contentDescription = null
      )
      Text(
        color = textColor,
        style = bodyStyle,
        text = message(LABEL_KEY),
        fontWeight = FontWeight.Medium
      )
      Text(
        text = pattern,
        maxLines = 1,
        color = infoColor,
        style = bodyStyle,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        maxLines = 1,
        style = bodyStyle,
        color = disabledColor,
        overflow = TextOverflow.Ellipsis,
        text = message(key = LABEL_KEY_DISPLAY, totalMatches)
      )
    }
  }

  companion object {
    const val ALIAS: String = "Grep"
    const val LABEL_KEY: String = "gradum.tool.grep"
    const val LABEL_KEY_DISPLAY: String = "gradum.tool.search.display"
  }
}
