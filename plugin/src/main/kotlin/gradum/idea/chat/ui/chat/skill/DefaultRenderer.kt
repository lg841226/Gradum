/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DefaultRenderer.kt  2026-07-29 18:32:58 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
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

  override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
    val aliasName: String = (arguments["alias"] as? String)
      ?: (result["alias"] as? String)
      ?: WILDCARD_ALIAS
    return ToolCallContent(
      aliasName = aliasName,
      fieldMap = emptyMap()
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    ToolCallCapsule(
      success = !ctx.isError,
      modifier = Modifier,
      errorDetail = ctx.errorDetail.orEmpty(),
      errorMessage = ctx.errorDetail.orEmpty(),
      toolDetails = ctx.toolDetails.orEmpty(),
      iconKey = AllIconsKeys.Nodes.Plugin,
      label = content.aliasName
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
