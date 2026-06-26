/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WelcomeScreen.kt  2026-06-26 23:55:00 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.ui.input.ChatInputSection
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * Landing screen shown before the user has sent any message. Renders a
 * centered brand header and a single chat input section.
 */
@Composable
fun WelcomeScreen(
    inputState: ChatInputState,
    inputActions: ChatInputActions,
    textState: TextFieldState,
    onRefreshModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column {
                Text(
                    text = message("gradum.brand.name"),
                    color = JewelTheme.globalColors.outlines.focused,
                    style = JewelTheme.typography.h2TextStyle.copy(
                        fontFamily = JewelTheme.typography.editorTextStyle.fontFamily
                    ),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(text = message("gradum.welcome.title"), style = JewelTheme.typography.h2TextStyle)
            }
            Spacer(Modifier.height(20.dp))
            ChatInputSection(
                modifier = Modifier.widthIn(max = 600.dp),
                state = inputState,
                actions = inputActions,
                textState = textState,
                onRefreshModels = onRefreshModels
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = message("gradum.disclaimer"),
                style = JewelTheme.typography.small,
                fontFamily = JewelTheme.typography.editorTextStyle.fontFamily,
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}
