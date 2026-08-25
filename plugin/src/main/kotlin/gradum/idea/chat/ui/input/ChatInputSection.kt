/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatInputSection.kt  2026-08-25 22:12:07 Changed by gwy
 */

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.utils.GradumSpacing

/**
 * Wraps [ChatInputPanel] with a model selector bar below it.
 */
@Composable
fun ChatInputSection(
  state: ChatInputState,
  actions: ChatInputActions,
  textState: TextFieldState,
  modifier: Modifier = Modifier,
  hasSentMessage: Boolean = false,
  selectedPermission: String = PermissionMode.READONLY,
) {
  Column(
    modifier = modifier.widthIn(max = 680.dp),
  ) {
    ChatInputPanel(
      state = state,
      actions = actions,
      textState = textState,
      hasSentMessage = hasSentMessage,
      roundedCornerShape = RoundedCornerShape(size = 6.dp),
      selectedPermission = selectedPermission
    )
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    ModelSelectorBar(
      selectedModel = state.selectedModel,
      models = state.models,
      pinnedModels = state.pinnedModels,
      thinkingLevel = state.thinkingLevel,
      onRefreshModels = actions.onRefreshModels,
      onTogglePin = actions.onTogglePin,
      onSelectModel = actions.onSelectModel,
      onSelectThinkingLevel = actions.onSelectThinkingLevel
    )
  }
}
