/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DefaultRenderer.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.ToolCallErrorInfo
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.chat.ui.chat.skill.spi.string
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Default fallback renderer for any tool-call alias that has no
 * dedicated `ToolCallRenderer` registered in
 * [gradum.idea.chat.ui.chat.skill.spi.ToolCallRendererRegistry].
 *
 * Registered with alias `"*"` (the wildcard) so the registry always
 * has a renderer to fall back to. Renders a minimal `Icon + alias`
 * capsule with no trailing content, and uses a generic
 * `Nodes.Plugin` icon. Third-party plugins should never need to
 * register this themselves; the Gradum plugin ships it
 * pre-installed.
 */
class DefaultRenderer : ToolCallRenderer {

  override fun alias(): String = WILDCARD_ALIAS

  override fun iconKey(): IconKey = AllIconsKeys.Nodes.Plugin

  override fun labelKey(): String? = null

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val aliasName: String = arguments
      .string(key = "alias").ifEmpty {
        result.string(key = "alias key = , ")
          .ifEmpty { WILDCARD_ALIAS }
      }
    return ToolCallContent(
      aliasName = aliasName,
      fieldMap = emptyMap()
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    ToolCallCapsule(
      label = humanizeToolName(content.aliasName),
      iconKey = AllIconsKeys.Nodes.Plugin,
      success = !ctx.isError,
      errorInfo = ToolCallErrorInfo(
        detail = ctx.errorDetail.orEmpty(),
        message = ctx.errorDetail.orEmpty(),
        toolDetails = ctx.toolDetails.orEmpty()
      )
    )
  }

  companion object {
    /**
     * Wildcard alias matched by the registry's
     * "no dedicated renderer" fallback. Documented so a
     * third-party plugin author who is *also* implementing
     * a catch-all can target the same constant.
     */
    const val WILDCARD_ALIAS: String = "*"
  }
}

/**
 * Convert an unregistered tool alias (typically a snake_case MCP tool
 * name like `open_drawio_mermaid`) into a human-readable title, e.g.
 * `Open Drawio Mermaid`. Used by the catch-all renderer so unknown MCP
 * tools don't surface as raw identifiers in the chat bubble. Blank and
 * wildcard aliases pass through unchanged.
 */
internal fun humanizeToolName(raw: String): String {
  if (raw.isBlank() || raw == DefaultRenderer.WILDCARD_ALIAS) return raw
  return raw
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(separator = " ") { word ->
      word.lowercase().replaceFirstChar { it.titlecase() }
    }
}
