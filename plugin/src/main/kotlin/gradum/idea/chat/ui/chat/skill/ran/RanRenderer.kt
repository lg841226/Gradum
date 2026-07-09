/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RanRenderer.kt  2026-07-09 18:00:00 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.ran

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.OpenInEditorButton
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallAction
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `run_cmd` skill (alias "Ran").
 *
 * Parses the command + the model's one-line `reason` ("read package.json
 * to confirm the Gradle version") from the `arguments` payload and
 * exposes an `OpenInEditor` action whose target is the command string
 * (Gradum's existing open-in-editor handler treats a shell script path
 * as a temp file to inspect). Also exposes `CopyToClipboard` so the
 * user can grab the command directly.
 */
class RanRenderer : ToolCallRenderer {

    override fun alias(): String = ALIAS

    override fun iconKey(): IconKey = GradumIcons.Ran

    override fun labelKey(): String = LABEL_KEY

    override fun parseContent(
        arguments: Map<String, Any?>,
        result: Map<String, Any?>,
    ): ToolCallContent {
        val command: String = (arguments["command"] as? String).orEmpty()
        val reason: String = (arguments["reason"] as? String).orEmpty()
        val actions: MutableList<ToolCallAction> = mutableListOf()
        if (command.isNotBlank()) {
            actions.add(ToolCallAction.CopyToClipboard(payload = command))
        }
        return ToolCallContent(
            alias = ALIAS,
            fields = mapOf(
                "command" to command,
                "reason" to reason,
            ),
            actions = actions,
        )
    }

    @Composable
    override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
        val command: String = (content.fields["command"] as? String).orEmpty()
        val reason: String = (content.fields["reason"] as? String).orEmpty()
        ToolCallCapsule(
            success = !ctx.isError,
            errorDetail = ctx.errorDetail.orEmpty(),
            errorMessage = ctx.errorDetail.orEmpty(),
            trailingText = reason,
            iconKey = GradumIcons.Ran,
            label = message(LABEL_KEY),
            trailingIcon = {
                OpenInEditorButton(
                    path = command,
                    onClick = {
                        ctx.onOpenInEditor?.invoke(command, 0, 0)
                    }
                )
            }
        )
    }

    companion object {
        const val ALIAS: String = "Ran"
        const val LABEL_KEY: String = "gradum.tool.ran"
    }
}
