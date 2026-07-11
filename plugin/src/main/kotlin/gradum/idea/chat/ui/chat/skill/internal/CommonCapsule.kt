@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat.skill.internal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.copyToClipboard
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val REASON_MAX_WIDTH_DP: Dp = 200.dp

/**
 * Shared one-line "capsule" used by every built-in default renderer
 * to render a tool call row. Lives in `skill/internal/` because
 * third-party renderers should not import it — they own their own
 * composables. Only the Gradum plugin's bundled renderers (Ran,
 * Edited, Read, Saved, Explored, Planned, Completed, Default) and
 * tests depend on this composable.
 */
@Composable
internal fun ToolCallCapsule(
  label: String,
  iconKey: IconKey,
  success: Boolean,
  errorDetail: String = "",
  errorMessage: String = "",
  trailingText: String = "",
  modifier: Modifier = Modifier,
  trailingIcon: @Composable RowScope.() -> Unit = {}
) {
  val clipboardScope = rememberCoroutineScope()
  var showErrorPopup by remember { mutableStateOf(false) }
  var isCopied by remember { mutableStateOf(false) }
  val hasError = !success && errorMessage.isNotBlank()
  val textColor = JewelTheme.globalColors.text.normal
  val infoColor = JewelTheme.globalColors.text.info

  Row(
    modifier = modifier
      .fillMaxWidth()
      .then(if (hasError) Modifier.clickable { showErrorPopup = true } else Modifier),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
  ) {
    Icon(iconKey, contentDescription = null)
    Text(
      text = label,
      fontWeight = FontWeight.Medium,
      color = textColor
    )

    if (trailingText.isNotBlank()) {
      Text(
        maxLines = 1,
        text = trailingText,
        overflow = TextOverflow.Ellipsis,
        color = infoColor,
        modifier = Modifier
          .horizontalScroll(rememberScrollState())
          .widthIn(max = REASON_MAX_WIDTH_DP)
      )
    }

    trailingIcon()

    if (!success)
      Icon(
        key = AllIconsKeys.Status.FailedInProgress,
        contentDescription = message("gradum.tool.failed")
      )
  }

  if (hasError && showErrorPopup) {
    PopupMenu(
      onDismissRequest = { showErrorPopup = false; true },
      horizontalAlignment = Alignment.Start
    ) {
      passiveItem {
        Row(
          modifier = Modifier
            .padding(
              horizontal = GradumSpacing.md,
              vertical = GradumSpacing.xs
            ),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
          Icon(
            key = AllIconsKeys.Status.FailedInProgress,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
          )
          Column {
            Text(text = errorMessage, color = textColor)
            Spacer(modifier = Modifier.height(GradumSpacing.xs))
            Link(
              text = if (isCopied) message("gradum.error.copied") else message("gradum.error.copy.hint"),
              onClick = {
                if (!isCopied)
                  copyToClipboard(
                    scope = clipboardScope,
                    text = errorDetail,
                    onCopied = { isCopied = true },
                    onReset = { isCopied = false }
                  )
              }
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
