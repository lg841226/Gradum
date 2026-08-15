/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SettingsCheckboxRow.kt  2026-08-15 19:34:06 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun SettingCheckboxRow(
  label: String, checked: Boolean, enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Checkbox(
      checked = checked,
      enabled = enabled,
      onCheckedChange = onCheckedChange
    )
    Spacer(Modifier.width(GradumSpacing.sm))
    Text(
      text = label,
      color = if (enabled) LocalContentColor.current
      else JewelTheme.globalColors.text.disabled,
      modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) }
    )
  }
}
