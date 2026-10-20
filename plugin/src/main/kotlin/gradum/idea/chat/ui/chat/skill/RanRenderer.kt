package gradum.idea.chat.ui.chat.skill

import androidx.compose.runtime.Composable
import gradum.idea.chat.ui.chat.skill.internal.OpenInEditorButton
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.ToolCallErrorInfo
import gradum.idea.chat.ui.chat.skill.spi.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Badge
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.theme.badgeStyle

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
    arguments: Map<String, Any?>, result: Map<String, Any?>
  ): ToolCallContent {
    val shellCommand: String = arguments.string(key = "command")
    val reasonText: String = arguments.string(key = "reason")
    val isBackground: Boolean = arguments["background"] as? Boolean ?: false
    val actionList: MutableList<ToolCallAction> = mutableListOf()
    if (shellCommand.isNotBlank()) actionList.add(ToolCallAction.CopyToClipboard(payload = shellCommand))

    return ToolCallContent(
      aliasName = ALIAS,
      fieldMap = mapOf(
        "command" to shellCommand,
        "reason" to reasonText,
        "background" to isBackground
      ),
      actionList = actionList
    )
  }

  @Composable
  override fun render(content: ToolCallContent, ctx: ToolCallRenderContext) {
    val shellCommand: String = (content.fieldMap["command"] as? String).orEmpty()
    val reasonText: String = (content.fieldMap["reason"] as? String).orEmpty()
    val isBackground: Boolean = content.fieldMap["background"] as? Boolean ?: false
    ToolCallCapsule(
      label = message(LABEL_KEY),
      iconKey = GradumIcons.Ran,
      success = !ctx.isError,
      trailingText = reasonText,
      trailingIcon = {
        if (isBackground) {
          Badge(style = JewelTheme.badgeStyle.blueSecondary) {
            Text(text = message("gradum.tool.background"))
          }
        }
        OpenInEditorButton(
          filePath = shellCommand,
          onClick = {
            ctx.onOpenInEditor?.invoke(shellCommand, 0, 0)
          }
        )
      },
      errorInfo = ToolCallErrorInfo(
        detail = ctx.errorDetail.orEmpty(),
        message = ctx.errorDetail.orEmpty(),
        toolDetails = ctx.toolDetails.orEmpty()
      )
    )
  }

  companion object {
    const val ALIAS: String = "Ran"
    const val LABEL_KEY: String = "gradum.tool.ran"
  }
}
