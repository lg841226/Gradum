/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SelectorButton.kt  2026-07-29 22:01:18 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.chat.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A button styled as a selector with a chevron icon and optional tooltip.
 */
@Composable
fun SelectorButton(
  text: String,
  onClick: () -> Unit,
  contentDescription: String,
  isButtonEnabled: Boolean = true,
  color: Color = JewelTheme.globalColors.text.info
) {
  Tooltip(tooltip = { Text(text = contentDescription) }) {
    IconButton(onClick = onClick, enabled = isButtonEnabled) {
      Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = GradumSpacing.sm)
      ) {
        Text(
          text = text,
          color = if (isButtonEnabled) color
          else JewelTheme.globalColors.text.disabled
        )
        Icon(
          key = AllIconsKeys.General.ChevronDown,
          contentDescription = contentDescription
        )
      }
    }
  }
}
