/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * CommonCapsule.kt  2026-08-23 21:12:19 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.internal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.chat.copyToClipboard
import gradum.idea.PluginConfig
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val REASON_MAX_WIDTH_DP: Dp = 200.dp
private val TOOL_DETAILS_RESULT_MAX_CHARS = PluginConfig.TOOL_DETAILS_RESULT_MAX_CHARS

/** Error metadata for a failed tool call capsule. */
data class ToolCallErrorInfo(
  val detail: String = "",
  val toolDetails: String = "",
  val message: String = ""
)

/**
 * Shared one-line "capsule" used by every built-in default renderer
 * to render a tool call row. Lives in `skill/internal/` because
 * third-party renderers should not import it — they own their own
 * composables. Only the Gradum plugin's bundled renderers (Ran,
 * Edited, Read, Saved, Explored, Planned, Completed, Default) and
 * tests depend on this composable.
 *
 * Error display — when [success] is `false` and [errorInfo.message] is
 * non-blank, the trailing status icon (`Status.FailedInProgress`)
 * becomes clickable and opens a minimal `PopupMenu` with a single
 * `selectableItem`:
 *
 *   [error icon]  Copy details / Copied
 *
 * "Copy details" copies errorInfo.toolDetails to the clipboard, defaulting
 * to errorInfo.detail when no richer info is available. toolDetails
 * is built by [formatToolDetails] in `ToolCallBlock` from the
 * underlying `RenderBlock.ToolCall` (alias + arguments + result +
 * error message + error detail) so the user can paste a full debug
 * snapshot without us hand-curating per-renderer error strings.
 * The error message itself is NOT shown in the popup — keep the
 * popup to one clickable row so it reads as a single "copy this
 * failure" action.
 */
@Composable
internal fun ToolCallCapsule(
  label: String,
  iconKey: IconKey,
  success: Boolean,
  errorInfo: ToolCallErrorInfo = ToolCallErrorInfo(),
  trailingText: String = "",
  modifier: Modifier = Modifier,
  trailingIcon: @Composable RowScope.() -> Unit = {}
) {
  val textColor = JewelTheme.globalColors.text.normal
  val infoColor = JewelTheme.globalColors.text.info
  val clipboardScope = rememberCoroutineScope()
  val bodyStyle = rememberGradumParagraphTextStyle()
  val hasError = !success && errorInfo.message.isNotBlank()
  var isCopied by remember { mutableStateOf(false) }
  var showErrorPopup by remember { mutableStateOf(false) }
  val copyPayload: String = errorInfo.toolDetails.ifBlank { errorInfo.detail }

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
      Icon(
        contentDescription = message("gradum.tool.error.open"),
        key = AllIconsKeys.Status.FailedInProgress,
        modifier = Modifier.clickable { showErrorPopup = true }
      )
    }
  }

  if (hasError && showErrorPopup) {
    PopupMenu(
      onDismissRequest = { showErrorPopup = false; true },
      horizontalAlignment = Alignment.Start
    ) {
      if (copyPayload.isNotBlank()) {
        selectableItem(
          selected = false,
          onClick = {
            showErrorPopup = false
            if (!isCopied) {
              copyToClipboard(
                scope = clipboardScope,
                text = copyPayload,
                onCopied = { isCopied = true },
                onReset = { isCopied = false }
              )
            }
          }
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = GradumSpacing.md, vertical = GradumSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
          ) {
            Icon(
              contentDescription = null,
              key = AllIconsKeys.General.Copy,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = if (isCopied) message("gradum.error.copied")
              else message("gradum.tool.copy.details")
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun toolCallErrorColor(): androidx.compose.ui.graphics.Color =
  JewelTheme.globalColors.text.error

@Composable
internal fun linesAddedColor(): androidx.compose.ui.graphics.Color =
  org.jetbrains.jewel.foundation.theme.LocalColorPalette.current.greenOrNull(5)
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
  arguments: Map<String, Any?>,
  result: String,
  errorMessage: String,
  errorDetail: String
): String {
  val sb = StringBuilder()
  sb.append("Tool: ").appendLine(alias)
  if (arguments.isNotEmpty()) {
    sb.appendLine("Arguments:")
    for ((key, value) in arguments) {
      sb.append("  ").append(key).append(": ").appendLine(value)
    }
  }
  if (result.isNotBlank()) {
    val truncated: String = if (result.length > TOOL_DETAILS_RESULT_MAX_CHARS) {
      result.take(TOOL_DETAILS_RESULT_MAX_CHARS) + "... (truncated)"
    } else result
    sb.append("Result: ").appendLine(truncated)
  }
  if (errorMessage.isNotBlank()) {
    sb.append("Error: ").appendLine(errorMessage)
  }
  if (errorDetail.isNotBlank()) {
    sb.appendLine("Detail:")
    sb.appendLine(errorDetail)
  }
  return sb.toString().trimEnd()
}

/**
 * Format a byte count into a human-readable string. Picks the
 * largest unit that still gives a value >= 1, with one decimal for
 * KB / MB / GB. Used by the built-in `SavedRenderer` (defined in
 * the `skill/` package, not imported here to keep this internal
 * file free of cross-package deps) to render the saved file's
 * size on the tool call capsule.
 */
internal fun formatBytes(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val kbValue: Double = bytes / 1024.0
  if (kbValue < 1024.0) return "%.1f KB".format(kbValue)
  val mbValue: Double = kbValue / 1024.0
  if (mbValue < 1024.0) return "%.1f MB".format(mbValue)
  val gbValue: Double = mbValue / 1024.0
  return "%.1f GB".format(gbValue)
}
