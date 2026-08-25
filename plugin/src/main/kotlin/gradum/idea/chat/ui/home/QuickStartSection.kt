/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * QuickStartSection.kt  2026-08-25 22:08:43 Changed by gwy
 */

package gradum.idea.chat.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Displays a quick-start section with suggestion cards for common chat prompts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickStartSection(
  maxItems: Int = 4,
  textState: TextFieldState,
  modifier: Modifier = Modifier,
  suggestionVariants: List<Int>,
  onRefreshSuggestions: () -> Unit
) {
  val featureIcons: List<PathIconKey> = remember {
    listOf(
      GradumIcons.FeatChat, GradumIcons.FeatQuestion,
      GradumIcons.FeatCode, GradumIcons.FeatText
    )
  }

  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = message("gradum.quick.start"),
        style = JewelTheme.typography.h4TextStyle
      )
      Spacer(modifier = Modifier.width(GradumSpacing.md))
      Tooltip(tooltip = { Text(text = message("gradum.refresh")) }) {
        IconButton(
          onClick = onRefreshSuggestions,
        ) {
          Icon(
            key = AllIconsKeys.Actions.Refresh,
            contentDescription = message("gradum.refresh")
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(GradumSpacing.sml))
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs)
    ) {
      listOf(0, 1, 2, 3)
        .take(n = maxItems)
        .forEach { categoryIndex: Int ->
          SuggestionCard(
            textState = textState,
            featureIcons = featureIcons,
            categoryIndex = categoryIndex,
            suggestionVariants = suggestionVariants
          )
        }
    }
  }
}

@Composable
private fun SuggestionCard(
  categoryIndex: Int,
  textState: TextFieldState,
  modifier: Modifier = Modifier,
  suggestionVariants: List<Int>,
  featureIcons: List<PathIconKey>
) {
  val suggestionText: String = message(
    key = "gradum.suggestion.$categoryIndex.${suggestionVariants[categoryIndex]}"
  )
  val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
  val isHovered: Boolean by interactionSource.collectIsHoveredAsState()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .padding(vertical = GradumSpacing.sm)
      .hoverable(interactionSource)
      .clickable { textState.edit { replace(start = 0, end = 0, suggestionText) } }
      .clip(shape = RoundedCornerShape(size = 6.dp))
      .background(
        color =
          if (isHovered) JewelTheme.globalColors.text.info.copy(alpha = 0.08f)
          else Color.Transparent
      )
      .padding(
        horizontal = GradumSpacing.md,
        vertical = GradumSpacing.sml
      )
  ) {
    Icon(
      contentDescription = null,
      key = featureIcons[categoryIndex]
    )
    Spacer(modifier = Modifier.width(GradumSpacing.md))
    Text(
      maxLines = 1,
      text = suggestionText,
      modifier = Modifier.weight(1f),
      overflow = TextOverflow.Ellipsis
    )
    Icon(
      key = AllIconsKeys.General.ArrowRight,
      contentDescription = message("gradum.use.suggestion")
    )
  }
}
