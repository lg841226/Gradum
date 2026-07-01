/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatInputSection.kt  2026-06-30 23:35:47 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
    onRefreshModels: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ChatInputPanel(
            state = state,
            actions = actions,
            roundedCornerShape = RoundedCornerShape(6.dp),
            textState = textState
        )
        Spacer(modifier = Modifier.height(GradumSpacing.md))
        ModelSelectorBar(
            models = state.models,
            selectedModel = state.selectedModel,
            pinnedModels = state.pinnedModels,
            isAutoSelected = state.isAutoSelected,
            onRefresh = onRefreshModels,
            onSelectModel = actions.onSelectModel,
            onTogglePin = actions.onTogglePin,
            onSelectAuto = actions.onSelectAuto
        )
    }
}
