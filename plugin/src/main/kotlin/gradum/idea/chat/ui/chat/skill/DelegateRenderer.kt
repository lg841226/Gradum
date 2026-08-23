/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DelegateRenderer.kt  2026-08-23 17:46:24 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
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

  private val logger: Logger = Logger.getInstance("#gradum.idea.chat.ui.chat.skill.DelegateRenderer")

  override fun alias(): String = message("gradum.tool.delegate")

  override fun parseContent(
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    return ToolCallContent(
      fieldMap = arguments,
      aliasName = message("gradum.tool.delegate")
    )
  }

  override fun iconKey() = AllIconsKeys.Nodes.Related

  override fun rendersWhilePending(): Boolean = true

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val isPending: Boolean = content.fieldMap["pending"] as? Boolean ?: true
    val startTimestamp: Long = (content.fieldMap["startTimestamp"] as? Number)?.toLong() ?: 0L
    val transcriptMarkdown: String = content.fieldMap["transcriptMarkdown"] as? String ?: ""
    val title: String = content.fieldMap["title"] as? String ?: ""
    logger.warn("DelegateRenderer transcriptMarkdown length=${transcriptMarkdown.length} title='$title'")

    var currentTimeMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isPending) {
      if (isPending) {
        while (true) {
          delay(1000L.milliseconds)
          currentTimeMillis = System.currentTimeMillis()
        }
      }
    }

    val elapsedSeconds: Long =
      if (isPending) (currentTimeMillis - startTimestamp) / 1000
      else 0L

    val durationText: String = if (isPending) {
      val min = (elapsedSeconds / 60).toInt()
      val sec = (elapsedSeconds % 60).toInt()
      if (min > 0) message("gradum.tool.delegate.duration", min, sec)
      else message("gradum.tool.delegate.duration.seconds", sec)
    } else ""

    ToolCallCapsule(
      label = message("gradum.tool.delegate"),
      success = !isPending,
      trailingText = title,
      iconKey = AllIconsKeys.Nodes.Services,
      modifier = Modifier.clickable { ctx.onSubChatClick?.invoke(transcriptMarkdown, "", title) },
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
