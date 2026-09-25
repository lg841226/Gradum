/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommonCapsule.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.internal

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.PluginConfig
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalColorPalette
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val REASON_MAX_WIDTH_DP: Dp = 200.dp
private const val TOOL_DETAILS_RESULT_MAX_CHARS = PluginConfig.TOOL_DETAILS_RESULT_MAX_CHARS

/** Error metadata for a failed tool call capsule. */
data class ToolCallErrorInfo(
  val detail: String = "",
  val message: String = "",
  val toolDetails: String = "",
)

/**
 * Shared one-line "capsule" used by every built-in default renderer
 * to render a tool call row. Lives in `skill/internal/` because
 * third-party renderers should not import it — they own their own
 * composables. Only the Gradum plugin's bundled renderers (Ran,
 * Edited, Read, Saved, Explored, Planned, Completed, Default) and
 * tests depend on this composable.
 *
 * Error display — when [success] is `false` and [message] is
 * non-blank, a trailing `Status.FailedInProgress` failure icon is
 * rendered as a clickable button. Clicking it copies
 * errorInfo.toolDetails to the clipboard (defaulting to
 * errorInfo.detail when no richer info is available); once copied the
 * icon swaps to `Actions.Checked` until it resets. toolDetails is
 * built by [formatToolDetails] in `ToolCallBlock` from the underlying
 * `RenderBlock.ToolCall` (alias + arguments + result + error message
 * + error detail) so the user can paste a full debug snapshot without
 * us hand-curating per-renderer error strings.
 */
@Composable
internal fun ToolCallCapsule(
  label: String,
  iconKey: IconKey,
  success: Boolean,
  trailingText: String = "",
  modifier: Modifier = Modifier,
  trailingIcon: @Composable RowScope.() -> Unit = {},
  errorInfo: ToolCallErrorInfo = ToolCallErrorInfo(),
) {
  val infoColor = JewelTheme.globalColors.text.info
  val textColor = JewelTheme.globalColors.text.normal
  val clipboardScope = rememberCoroutineScope()
  val bodyStyle = rememberGradumParagraphTextStyle()
  val copyPayload: String = errorInfo.toolDetails.ifBlank { errorInfo.detail }
  val hasError = !success && copyPayload.isNotBlank()
  var isCopied by remember { mutableStateOf(value = false) }

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
  ) {
    Icon(iconKey, contentDescription = null)
    Text(
      text = label,
      style = bodyStyle,
      color = textColor,
      fontWeight = FontWeight.Medium
    )

    if (trailingText.isNotBlank()) {
      Text(
        maxLines = 1,
        color = infoColor,
        style = bodyStyle,
        text = trailingText,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = REASON_MAX_WIDTH_DP)
      )
    }

    trailingIcon()

    if (hasError) {
      IconTooltipButton(
        tooltip =
          if (isCopied) message(key = "gradum.error.copied")
          else message(key = "gradum.tool.copy.details"),
        iconKey =
          if (isCopied) AllIconsKeys.Actions.Checked
          else AllIconsKeys.Status.FailedInProgress,
        onClick = {
          if (!isCopied && copyPayload.isNotBlank()) {
            copyToClipboard(
              text = copyPayload,
              scope = clipboardScope,
              onCopied = { isCopied = true },
              onReset = { isCopied = false }
            )
          }
        },
        enabled = copyPayload.isNotBlank(),
        contentDescription = ""
      )
    }
  }
}

@Composable
internal fun toolCallErrorColor(): Color = JewelTheme.globalColors.text.error

@Composable
internal fun linesAddedColor(): Color = LocalColorPalette.current.greenOrNull(index = 5)
  ?: JewelTheme.globalColors.text.info

/**
 * Format a tool call's full debug info as a human-readable,
 * copy-friendly string. Used by the error popup's "Copy details"
 * action so the user can paste a complete snapshot of what the
 * model asked the tool to do, what the tool returned, and what went
 * wrong — without us hand-curating per-renderer error strings.
 *
 * Sections are emitted in this order, each one omitted when its
 * source is blank:
 *
 *   Tool: <alias>
 *   Arguments:
 *     <key>: <value>
 *     ...
 *   Result: <truncated to TOOL_DETAILS_RESULT_MAX_CHARS chars>
 *   Error: <errorMessage>
 *   Detail:
 *     <errorDetail>
 *
 * `arguments` values are rendered via `toString()` — the map's value
 * type is `Any?` (kotlinx-serialization round-trip), and we don't
 * pull `kotlinx-serialization-json` into the renderer layer to
 * pretty-print them. The output is still copyable into a chat /
 * issue / log for triage.
 */
internal fun formatToolDetails(
  alias: String,
  result: String,
  errorDetail: String,
  errorMessage: String,
  arguments: Map<String, Any?>
): String {
  val stringBuilder = StringBuilder()
  stringBuilder.append("Tool: ").appendLine(value = alias)
  if (arguments.isNotEmpty()) {
    stringBuilder.appendLine(value = "Arguments:")
    for ((key, value) in arguments)
      stringBuilder.append("  ").append(key).append(": ").appendLine(value)
  }
  if (result.isNotBlank()) {
    val truncated: String = if (result.length > TOOL_DETAILS_RESULT_MAX_CHARS) {
      result.take(n = TOOL_DETAILS_RESULT_MAX_CHARS) + "... (truncated)"
    } else result
    stringBuilder.append("Result: ").appendLine(value = truncated)
  }
  if (errorMessage.isNotBlank())
    stringBuilder.append("Error: ").appendLine(value = errorMessage)
  if (errorDetail.isNotBlank()) {
    stringBuilder.appendLine(value = "Detail:")
    stringBuilder.appendLine(value = errorDetail)
  }
  return stringBuilder.toString().trimEnd()
}
