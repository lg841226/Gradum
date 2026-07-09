/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PlannedRenderer.kt  2026-07-09 17:19:52 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.planned

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `to_do` skill when the LLM
 * is **adding** new tasks (alias "Planned"). Falls back to a
 * single-line capsule with a `taskCount` summary; expanded task
 * lists live in the chat's TodoSkill panel, not on the tool row.
 */
class PlannedRenderer : ToolCallRenderer {

    override fun alias(): String = ALIAS

    override fun iconKey(): IconKey = GradumIcons.NumberList

    override fun labelKey(): String = LABEL_KEY

    override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
        @Suppress("UNCHECKED_CAST")
        val tasks: List<String> = (result["tasks"] as? List<String>)
            ?: (arguments["tasks"] as? List<String>)
            ?: emptyList()
        return ToolCallContent(
            alias = ALIAS,
            fields = mapOf(
                "taskCount" to tasks.size,
                "firstTask" to tasks.firstOrNull().orEmpty(),
            ),
        )
    }

    @Composable
    override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
        val taskCount: Int = (content.fields["taskCount"] as? Number)?.toInt() ?: 0
        val firstTask: String = (content.fields["firstTask"] as? String).orEmpty()
        val displayText: String =
            if (taskCount > 1) "$firstTask (+${taskCount - 1})" else firstTask

        ToolCallCapsule(
            success = !ctx.isError,
            errorDetail = ctx.errorDetail.orEmpty(),
            trailingText = displayText,
            errorMessage = ctx.errorDetail.orEmpty(),
            label = message(LABEL_KEY),
            iconKey = GradumIcons.NumberList,
        )
    }

    companion object {
        const val ALIAS: String = "Planned"
        const val LABEL_KEY: String = "gradum.tool.planned"
    }
}
