package gradum.idea.chat.ui.chat.skill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import gradum.idea.chat.model.ChatMessage
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
    val subChatMessages: List<ChatMessage> =
      (content.fieldMap["subChatMessages"] as? List<*>)?.filterIsInstance<ChatMessage>() ?: emptyList()
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


    val durationText: String = if (startTimestamp <= 0L) {
      ""
    } else {
      val elapsedSeconds: Long = ((endTimestamp ?: currentTimeMillis) - startTimestamp) / 1000
      if (elapsedSeconds > 0) {
        val min = (elapsedSeconds / 60).toInt()
        val sec = (elapsedSeconds % 60).toInt()
        if (min > 0) message("gradum.tool.delegate.duration", min, sec)
        else message("gradum.tool.delegate.duration.seconds", sec)
      } else ""
    }

    val canOpenSubChat: Boolean = subChatMessages.isNotEmpty() || startTimestamp > 0L

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
      modifier = Modifier.clickable(enabled = canOpenSubChat) {
        ctx.onSubChatClick?.invoke(subChatMessages, title)
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

        if (canOpenSubChat) {
          Icon(
            contentDescription = null,
            key = AllIconsKeys.General.ChevronRight
          )
        }
      }
    )
  }
}
