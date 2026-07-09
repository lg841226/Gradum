/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SavedRenderer.kt  2026-07-09 17:19:52 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.saved

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.OpenInEditorButton
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.formatBytes
import gradum.idea.chat.ui.chat.skill.spi.ToolCallAction
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.icons.GradumIcons
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Default renderer for the server-side `save_file` skill (alias
 * "Saved"). Shows the file name plus a human-readable size suffix
 * (e.g. "Build.kt 4.2 KB") when the tool result included a
 * `sizeBytes` value, and offers an `OpenInEditor` action.
 */
class SavedRenderer : ToolCallRenderer {

    override fun alias(): String = ALIAS

    override fun iconKey(): IconKey = GradumIcons.Save

    override fun labelKey(): String = LABEL_KEY

    override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
        val filePath: String = (arguments["path"] as? String).orEmpty()
        val sizeBytes: Long = (result["sizeBytes"] as? Number)?.toLong() ?: 0L
        val actions: MutableList<ToolCallAction> = mutableListOf()
        if (filePath.isNotBlank())
            actions.add(ToolCallAction.OpenInEditor(path = filePath))

        return ToolCallContent(
            alias = ALIAS,
            fields = mapOf(
                "filePath" to filePath,
                "sizeBytes" to sizeBytes,
            ),
            actions = actions,
        )
    }

    @Composable
    override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
        val filePath: String = (content.fields["filePath"] as? String).orEmpty()
        val sizeBytes: Long = (content.fields["sizeBytes"] as? Number)?.toLong() ?: 0L
        val fileName: String = filePath.substringAfterLast('/')
        val sizeText: String? = if (sizeBytes > 0L) formatBytes(sizeBytes) else null
        val displayText: String =
            if (sizeText != null) "$fileName $sizeText" else fileName

        ToolCallCapsule(
            iconKey = GradumIcons.Save,
            success = !ctx.isError,
            errorDetail = ctx.errorDetail.orEmpty(),
            trailingText = displayText,
            errorMessage = ctx.errorDetail.orEmpty(),
            label = message(LABEL_KEY),
            trailingIcon = {
                OpenInEditorButton(
                    path = filePath,
                    onClick = {
                        ctx.onOpenInEditor?.invoke(filePath, 0, 0)
                    }
                )
            }
        )
    }

    companion object {
        const val ALIAS: String = "Saved"
        const val LABEL_KEY: String = "gradum.tool.saved"
    }
}
