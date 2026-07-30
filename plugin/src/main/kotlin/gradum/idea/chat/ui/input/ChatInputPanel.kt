/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatInputPanel.kt  2026-07-29 21:48:03 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.chat.SweepLightText
import gradum.idea.chat.ui.common.IconTooltipButton
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.Stroke
import org.jetbrains.jewel.foundation.modifier.border
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.focusOutline
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Duration.Companion.milliseconds

/**
 * The main chat input panel containing a text area and a toolbar.
 *
 * Long-paste-to-context: when the text state grows by more than 200 chars
 * in a single settled snapshot, treat the appended run as a context
 * attachment and strip it back out of the input.
 */
@Composable
fun ChatInputPanel(
  state: ChatInputState,
  actions: ChatInputActions,
  roundedCornerShape: RoundedCornerShape,
  textState: TextFieldState,
  selectedPermission: String = PermissionMode.READONLY,
  hasSentMessage: Boolean = false,
  modifier: Modifier = Modifier
) {
  val initialTextLength: Int = remember { textState.text.length }
  var previousTextLength by remember { mutableStateOf(initialTextLength) }
  LaunchedEffect(textState.text) {
    delay(50.milliseconds)

    val currentInputText: String = textState.text.toString()
    val baselineTextLength: Int = previousTextLength
    val appendedTextLength: Int = currentInputText.length - baselineTextLength

    if (appendedTextLength > 200) {
      val appendedText: String = currentInputText.substring(baselineTextLength)
      if (appendedText.isNotBlank()) {
        actions.onPasteAsContext(appendedText)
        textState.edit { delete(baselineTextLength, currentInputText.length) }
      }
    }
    previousTextLength = textState.text.length
  }

  Box(
    modifier = modifier
      .onFocusChanged { actions.onFocusChange(it.hasFocus) }
      .thenIf(!state.isFocused) {
        border(
          width = 1.dp,
          shape = roundedCornerShape,
          alignment = Stroke.Alignment.Inside,
          color = JewelTheme.globalColors.borders.normal
        )
      }
      .focusOutline(
        showOutline = state.isFocused,
        outlineShape = roundedCornerShape
      )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(GradumSpacing.md)
    ) {
      if (state.pendingMessages.isNotEmpty()) {
        state.pendingMessages.forEach { pending ->
          val singleLineContent: String = pending.content.replace("\n", " ")
          val preview: String = truncateToCodePoints(singleLineContent)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(
                horizontal = GradumSpacing.sm,
                vertical = GradumSpacing.xs
              ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
          ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            SweepLightText(text = preview, modifier = Modifier.weight(1f))
            IconTooltipButton(
              tooltip = message("gradum.remove"),
              iconKey = AllIconsKeys.Actions.Close,
              contentDescription = message("gradum.remove"),
              onClick = { actions.onRemovePending(pending) },
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      TextArea(
        state = textState,
        placeholder = { Text(text = message("gradum.input.placeholder")) },
        undecorated = true,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 60.dp, max = 160.dp)
          .onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

            val isSubmitKey = keyEvent.key == Key.Enter &&
              (keyEvent.isMetaPressed || keyEvent.isCtrlPressed)
            if (!isSubmitKey) return@onPreviewKeyEvent false

            val modelSelected = state.selectedModel != null || state.isAutoSelected
            val isDebug = selectedPermission == PermissionMode.DEBUG
            val canSend = !state.isSending || !state.isPendingQueueFull
            if ((modelSelected || isDebug) && canSend) actions.onSend()

            true
          }
      )

      Spacer(modifier = Modifier.height(GradumSpacing.md))

      ChatToolbar(state = state, actions = actions, isTextNotEmpty = textState.text.isNotBlank(), hasSentMessage = hasSentMessage, selectedPermission = selectedPermission)

      AttachmentBar(attachedFiles = state.attachedFiles, onRemoveFile = actions.onRemoveFile)
    }
  }
}
