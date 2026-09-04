/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * MessageTimestamp.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gradum.idea.chat.model.formatTimestamp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * Centered timestamp separator between message groups.
 */
@Composable
fun MessageTimestamp(timestamp: Long, modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Text(
      text = formatTimestamp(timestamp),
      style = JewelTheme.typography.medium,
      color = JewelTheme.globalColors.text.info
    )
  }
}
