/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageCopyButton.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
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
    onCopyAsContext: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    Tooltip(tooltip = { Text(message("gradum.copy.tooltip")) }) {
        IconButton(
            onClick = {
                if (message.content.length > 200) {
                    onCopyAsContext(message.content)
                } else {
                    copyToClipboard(
                        text = message.content,
                        onCopied = onCopy,
                        onReset = onReset,
                        scope = scope
                    )
                }
            },
            enabled = message.content.isNotBlank()
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
 * @param onReset   Invoked after [delayMillis] to revert any visual state.
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
