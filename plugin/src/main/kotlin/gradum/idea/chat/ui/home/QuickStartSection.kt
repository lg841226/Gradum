/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * QuickStartSection.kt  2026-07-29 12:04:37 Changed by gwy
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
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.icons.GradumIcons
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
  textState: TextFieldState,
  suggestionVariants: List<Int>,
  onRefreshSuggestions: () -> Unit,
  modifier: Modifier = Modifier
) {
  val featureIcons = remember {
    listOf(GradumIcons.FeatChat, GradumIcons.FeatQuestion, GradumIcons.FeatCode, GradumIcons.FeatText)
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
    Spacer(modifier = Modifier.height(GradumSpacing.lg))
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.xs)
    ) {
      repeat(4) { categoryIndex ->
        SuggestionCard(
          textState = textState,
          suggestionVariants = suggestionVariants,
          featureIcons = featureIcons,
          categoryIndex = categoryIndex,
        )
      }
    }
  }
}

@Composable
private fun SuggestionCard(
  textState: TextFieldState,
  suggestionVariants: List<Int>,
  featureIcons: List<PathIconKey>,
  categoryIndex: Int,
  modifier: Modifier = Modifier
) {
  val suggestionText = message("gradum.suggestion.$categoryIndex.${suggestionVariants[categoryIndex]}")
  val interactionSource = remember { MutableInteractionSource() }
  val isHovered by interactionSource.collectIsHoveredAsState()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .padding(vertical = GradumSpacing.sm)
      .hoverable(interactionSource)
      .clickable { textState.edit { replace(0, length, suggestionText) } }
      .clip(RoundedCornerShape(6.dp))
      .background(
        if (isHovered) JewelTheme.globalColors.text.info
          .copy(alpha = 0.08f) else Color.Transparent
      )
      .padding(horizontal = GradumSpacing.md, vertical = 6.dp)
  ) {
    Icon(
      key = featureIcons[categoryIndex],
      contentDescription = null
    )
    Spacer(modifier = Modifier.width(GradumSpacing.md))
    Text(
      modifier = Modifier.weight(1f),
      text = suggestionText,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Icon(
      key = AllIconsKeys.General.ArrowRight,
      contentDescription = message("gradum.use.suggestion")
    )
  }
}
