/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CompletedRenderer.kt  2026-07-09 18:00:00 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.completed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `to_do` skill when the LLM
 * **marks a task done** (alias "Completed"). Single-line capsule
 * with the completed task name.
 */
class CompletedRenderer : ToolCallRenderer {

    override fun alias(): String = ALIAS

    override fun iconKey(): IconKey = AllIconsKeys.Actions.Checked

    override fun labelKey(): String = LABEL_KEY

    override fun parseContent(
        arguments: Map<String, Any?>,
        result: Map<String, Any?>,
    ): ToolCallContent {
        val task: String = (arguments["task"] as? String)
            ?: (result["task"] as? String)
            ?: (arguments["content"] as? String)
            ?: ""
        return ToolCallContent(
            alias = ALIAS,
            fields = mapOf("task" to task),
        )
    }

    @Composable
    override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
        val task: String = (content.fields["task"] as? String).orEmpty()
        ToolCallCapsule(
            success = !ctx.isError,
            errorDetail = ctx.errorDetail.orEmpty(),
            trailingText = task,
            errorMessage = ctx.errorDetail.orEmpty(),
            label = message(LABEL_KEY),
            iconKey = AllIconsKeys.Actions.Checked,
        )
    }

    companion object {
        const val ALIAS: String = "Completed"
        const val LABEL_KEY: String = "gradum.tool.completed"
    }
}
