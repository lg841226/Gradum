/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadRenderer.kt  2026-07-09 17:19:52 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.read

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.chat.skill.internal.OpenInEditorButton
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.spi.ToolCallAction
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Default renderer for the server-side `read_file` skill (alias
 * "Read"). Shows the file name and an optional `lines N-M` suffix
 * (resolved from the `lineRange` field in the tool result, e.g.
 * `"12-37"`), and offers an `OpenInEditor` action that jumps to
 * the start line.
 */
class ReadRenderer : ToolCallRenderer {

    override fun alias(): String = ALIAS

    override fun iconKey(): IconKey = AllIconsKeys.General.Show

    override fun labelKey(): String = LABEL_KEY

    override fun parseContent(arguments: Map<String, Any?>, result: Map<String, Any?>): ToolCallContent {
        val filePath: String = (arguments["path"] as? String).orEmpty()
        val startLine: Int? = (result["startLine"] as? Number)?.toInt()
            ?: parseLineRangeStart(result["lineRange"] as? String)
        val endLine: Int? = (result["endLine"] as? Number)?.toInt()
            ?: parseLineRangeEnd(result["lineRange"] as? String)
        val actions: MutableList<ToolCallAction> = mutableListOf()
        if (filePath.isNotBlank()) {
            actions.add(
                ToolCallAction.OpenInEditor(
                    path = filePath,
                    startLine = startLine ?: 0,
                    endLine = endLine ?: 0
                )
            )
        }
        return ToolCallContent(
            alias = ALIAS,
            fields = mapOf(
                "filePath" to filePath,
                "startLine" to startLine,
                "endLine" to endLine,
            ),
            actions = actions,
        )
    }

    @Composable
    override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
        val filePath: String = (content.fields["filePath"] as? String).orEmpty()
        val startLine: Int? = (content.fields["startLine"] as? Number)?.toInt()
        val endLine: Int? = (content.fields["endLine"] as? Number)?.toInt()
        val fileName: String = filePath.substringAfterLast('/')
        val lineText: String = when {
            startLine != null && endLine != null ->
                message("gradum.tool.line.range", startLine, endLine)

            startLine != null -> message("gradum.tool.line.single", startLine)
            else -> ""
        }
        val displayText: String =
            if (lineText.isNotEmpty()) "$fileName $lineText" else fileName

        ToolCallCapsule(
            success = !ctx.isError,
            modifier = Modifier,
            errorDetail = ctx.errorDetail.orEmpty(),
            trailingText = displayText,
            errorMessage = ctx.errorDetail.orEmpty(),
            label = message(LABEL_KEY),
            iconKey = AllIconsKeys.General.Show,
            trailingIcon = {
                OpenInEditorButton(
                    path = filePath,
                    startLine = startLine,
                    endLine = endLine,
                    onClick = {
                        ctx.onOpenInEditor?.invoke(
                            filePath,
                            (startLine ?: 0).coerceAtLeast(1),
                            (endLine ?: 0).coerceAtLeast(1),
                        )
                    }
                )
            }
        )
    }

    private fun parseLineRangeStart(lineRange: String?): Int? =
        parseLineRangePair(lineRange).first

    private fun parseLineRangeEnd(lineRange: String?): Int? =
        parseLineRangePair(lineRange).second

    private fun parseLineRangePair(lineRange: String?): Pair<Int?, Int?> {
        if (lineRange.isNullOrBlank()) return null to null
        val parts: List<String> = lineRange.split("-")
        return when (parts.size) {
            2 -> parts[0].toIntOrNull() to parts[1].toIntOrNull()
            1 -> parts[0].toIntOrNull() to null
            else -> null to null
        }
    }

    companion object {
        const val ALIAS: String = "Read"
        const val LABEL_KEY: String = "gradum.tool.read"
    }
}
