package gradum.idea.chat.ui.chat.skill

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.chat.ui.chat.skill.spi.string
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Renderer for the server-side `mcp_tools` skill (alias "MCP Tools").
 *
 * Mirrors the web-search capsule: a single-line header
 * `(bullet) MCP Tool [query] N tools >` that expands/collapses to
 * reveal the tool list. The leading thumbnail is a gray unordered-list
 * bullet (the same dot Gradum Markdown uses) instead of a favicon.
 *
 * Two shapes are handled, matching the server result:
 * - No query: a grouped directory listing. Each group is a section
 *   header followed by its tools.
 * - With query: the materialized matches, listed flat.
 */
class McpToolsRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun labelKey(): String = LABEL_KEY

  override fun iconKey(): IconKey = GradumIcons.BulletList

  override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
    val query: String = arguments.string(key = "query")

    @Suppress("UNCHECKED_CAST")
    val groups: List<Map<String, Any>> = (result["groups"] as? List<Map<String, Any>>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    val tools: List<Map<String, Any>> = (result["tools"] as? List<Map<String, Any>>) ?: emptyList()

    val total: Int = (result["exposed_tools"] as? Number)?.toInt()
      ?: (result["matched"] as? Number)?.toInt()
      ?: tools.size

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = linkedMapOf(
        "query" to query,
        "groups" to groups,
        "tools" to tools,
        "total" to total
      )
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val query: String = (content.fieldMap["query"] as? String).orEmpty()
    val total: Int = (content.fieldMap["total"] as? Number)?.toInt() ?: 0
    val infoColor = JewelTheme.globalColors.text.info
    val textColor = JewelTheme.globalColors.text.normal
    val disabledColor = JewelTheme.globalColors.text.disabled
    val bodyStyle = rememberGradumParagraphTextStyle()

    @Suppress("UNCHECKED_CAST")
    val groups: List<Map<String, Any>> = (content.fieldMap["groups"] as? List<Map<String, Any>>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    val tools: List<Map<String, Any>> = (content.fieldMap["tools"] as? List<Map<String, Any>>) ?: emptyList()

    var isExpanded by remember { mutableStateOf(value = true) }

    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
      ) {
        Icon(
          contentDescription = null,
          key = AllIconsKeys.General.ExternalTools
        )
        Text(
          color = textColor,
          style = bodyStyle,
          text = message(LABEL_KEY),
          fontWeight = FontWeight.Medium
        )
        if (query.isNotBlank()) {
          Text(
            text = query,
            maxLines = 1,
            color = infoColor,
            style = bodyStyle,
            overflow = TextOverflow.Ellipsis
          )
        }
        Text(
          maxLines = 1,
          style = bodyStyle,
          color = disabledColor,
          overflow = TextOverflow.Ellipsis,
          text = message(key = LABEL_KEY_DISPLAY, total)
        )
        Icon(
          key =
            if (isExpanded) AllIconsKeys.General.ChevronDown
            else AllIconsKeys.General.ChevronRight,
          contentDescription = null
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        val capHeight: Boolean = total > MCP_LIST_SCROLL_THRESHOLD
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .then(
              if (capHeight) Modifier.heightIn(max = MCP_LIST_MAX_HEIGHT_DP)
              else Modifier
            )
        ) {
          val scrollState = rememberScrollState()
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .then(if (capHeight) Modifier.verticalScroll(scrollState) else Modifier)
              .padding(start = GradumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
          ) {
            Spacer(modifier = Modifier.height(GradumSpacing.sml))
            if (groups.isNotEmpty()) {
              groups.forEach { group ->
                val groupName: String = (group["group"] as? String).orEmpty()

                @Suppress("UNCHECKED_CAST")
                val groupTools: List<Map<String, Any>> =
                  (group["tools"] as? List<Map<String, Any>>) ?: emptyList()
                if (groupName.isNotBlank()) {
                  Text(
                    text = groupName,
                    style = bodyStyle,
                    color = disabledColor,
                    fontWeight = FontWeight.Medium
                  )
                }
                groupTools.forEach { tool ->
                  ToolRow(infoColor, textColor, bodyStyle, tool)
                }
              }
            } else if (tools.isNotEmpty()) {
              tools.forEach { tool ->
                ToolRow(infoColor, textColor, bodyStyle, tool)
              }
            }
          }
          if (capHeight) {
            VerticalScrollbar(
              scrollState = scrollState,
              modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
            )
          }
        }
      }
    }
  }

  @Composable
  private fun ToolRow(
    infoColor: Color,
    textColor: Color,
    bodyStyle: TextStyle,
    tool: Map<String, Any>
  ) {
    val name: String = (tool["name"] as? String).orEmpty()
    val description: String = (tool["description"] as? String).orEmpty()
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        key = GradumIcons.Mcp,
        contentDescription = null
      )
      Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs)) {
        Text(
          text = name,
          style = bodyStyle,
          color = textColor,
          fontWeight = FontWeight.Medium
        )
        if (description.isNotBlank()) {
          Text(
            color = infoColor,
            style = bodyStyle,
            text = description,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }
  }

  companion object {
    const val ALIAS: String = "MCP Tools"
    const val LABEL_KEY: String = "gradum.tool.mcp"
    const val LABEL_KEY_DISPLAY: String = "gradum.tool.mcp.tools.display"

    /** Height cap for the expanded tool list; taller lists scroll with a native scrollbar. */
    val MCP_LIST_MAX_HEIGHT_DP: Dp = 560.dp

    /** The height cap and scrollbar only engage once the tool count exceeds this. */
    const val MCP_LIST_SCROLL_THRESHOLD: Int = 20
  }
}
