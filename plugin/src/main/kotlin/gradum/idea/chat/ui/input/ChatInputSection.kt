/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatInputSection.kt  2026-07-29 10:54:42 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

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
import gradum.idea.chat.ui.GradumSpacing
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * Wraps [ChatInputPanel] with a model selector bar below it.
 */
@Composable
fun ChatInputSection(
  state: ChatInputState,
  actions: ChatInputActions,
  textState: TextFieldState,
  selectedPermission: String = "read_only",
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.widthIn(max = 680.dp),
  ) {
    ChatInputPanel(
      state = state,
      actions = actions,
      roundedCornerShape = RoundedCornerShape(6.dp),
      textState = textState,
      selectedPermission = selectedPermission
    )
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    ModelSelectorBar(
      selectedModel = state.selectedModel,
      isAutoSelected = state.isAutoSelected,
      models = state.models,
      pinnedModels = state.pinnedModels,
      onSelectAuto = actions.onSelectAuto,
      onTogglePin = actions.onTogglePin,
      onSelectModel = actions.onSelectModel
    )
  }
}
