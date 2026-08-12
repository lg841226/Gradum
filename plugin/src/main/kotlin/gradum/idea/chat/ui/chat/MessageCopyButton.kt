/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageCopyButton.kt  2026-08-12 12:38:25 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import gradum.idea.chat.model.ChatMessage
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.Duration.Companion.milliseconds

/**
 * Copy button with tooltip.
 *
 * Shows a check icon briefly after clicking, then reverts. Long messages
 * (over 200 chars) bypass the clipboard and become context attachments
 * via [onCopyAsContext] so the user can paste them back into the
 * conversation as ground truth.
 */
@Composable
fun MessageCopyButton(
  message: ChatMessage,
  isCopied: Boolean,
  onCopy: () -> Unit,
  onReset: () -> Unit,
  onCopyAsContext: (String) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val scope = rememberCoroutineScope()
  val textToCopy = if (message.isUserMessage) message.content else message.fullContent

  Tooltip(modifier = modifier, tooltip = { Text(text = message("gradum.copy.tooltip")) }) {
    IconButton(
      onClick = {
        copyToClipboard(
          scope = scope,
          text = textToCopy,
          onCopied = onCopy,
          onReset = onReset
        )
      },
      enabled = textToCopy.isNotBlank()
    ) {
      Icon(
        key = if (isCopied) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy,
        contentDescription = message("gradum.copy")
      )
    }
  }
}

/**
 * Copies [text] to the system clipboard and fires callbacks.
 *
 * @param onCopied  Invoked immediately after the copy succeeds.
 * @param onReset   Invoked after [delayMillis] to revert any visual scanState.
 */
fun copyToClipboard(
  text: String,
  onCopied: () -> Unit,
  onReset: () -> Unit,
  scope: CoroutineScope,
  delayMillis: Long = 1000
) {
  val clipboard = Toolkit.getDefaultToolkit().systemClipboard
  val stringSelection = StringSelection(text)
  clipboard.setContents(stringSelection, null)

  onCopied()

  scope.launch {
    delay(delayMillis.milliseconds); onReset()
  }
}
