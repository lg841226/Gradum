/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DelegateRenderer.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.ToolCallErrorInfo
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderer
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Duration.Companion.milliseconds

/**
 * Renders the delegate-task capsule row. The capsule shows the task title
 * (info color) and elapsed duration (disabled color) while the sub-agent
 * is running. When clicked (after completion), the chat panel switches to
 * a read-only sub-chat view showing the sub-agent's full conversation.
 */
class DelegateRenderer : ToolCallRenderer {

  override fun alias(): String = ALIAS

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = arguments
    )
  }

  override fun iconKey() = AllIconsKeys.Nodes.Services

  override fun rendersWhilePending(): Boolean = true

  override fun labelKey(): String = "gradum.tool.delegate"

  companion object {
    const val ALIAS: String = "Delegate"
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val title: String = content.fieldMap["title"] as? String ?: ""
    val isPending: Boolean = content.fieldMap["pending"] as? Boolean ?: true
    val transcriptMarkdown: String = content.fieldMap["transcriptMarkdown"] as? String ?: ""
    val startTimestamp: Long = (content.fieldMap["startTimestamp"] as? Number)?.toLong() ?: 0L
    val endTimestamp: Long? =
      if (isPending) null
      else (content.fieldMap["endTimestamp"] as? Number)?.toLong()

    var currentTimeMillis by remember { mutableStateOf(value = System.currentTimeMillis()) }
    LaunchedEffect(key1 = isPending) {
      if (isPending) {
        while (true) {
          delay(duration = 1000L.milliseconds)
          currentTimeMillis = System.currentTimeMillis()
        }
      }
    }

    val elapsedSeconds: Long = ((endTimestamp ?: currentTimeMillis) - startTimestamp) / 1000

    val durationText: String = if (elapsedSeconds > 0) {
      val min = (elapsedSeconds / 60).toInt()
      val sec = (elapsedSeconds % 60).toInt()
      if (min > 0) message("gradum.tool.delegate.duration", min, sec)
      else message("gradum.tool.delegate.duration.seconds", sec)
    } else ""

    ToolCallCapsule(
      label = message("gradum.tool.delegate"),
      iconKey =
        if (isPending) AllIconsKeys.Nodes.Services
        else AllIconsKeys.Actions.Checked,
      success = !isPending && !ctx.isError,
      errorInfo = ToolCallErrorInfo(
        detail = ctx.errorDetail.orEmpty(),
        message = ctx.errorDetail.orEmpty(),
        toolDetails = ctx.toolDetails.orEmpty()
      ),
      trailingText = title,
      modifier = Modifier.clickable {
        ctx.onSubChatClick?.invoke(transcriptMarkdown, "", title)
      },
      trailingIcon = {
        if (durationText.isNotBlank()) {
          Text(
            maxLines = 1,
            text = durationText,
            overflow = TextOverflow.Ellipsis,
            style = rememberGradumParagraphTextStyle(),
            color = JewelTheme.globalColors.text.disabled,
            modifier = Modifier.padding(end = GradumSpacing.sml)
          )
        }
        Icon(
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          key = AllIconsKeys.General.ChevronRight
        )
      }
    )
  }
}
