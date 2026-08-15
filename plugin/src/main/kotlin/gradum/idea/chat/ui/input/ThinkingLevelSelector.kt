/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThinkingLevelSelector.kt  2026-08-14 14:30:00 Changed by gwy
 */
package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text

/**
 * Dropdown for picking a [ThinkingLevel] (Low / Medium / High),
 * rendered next to the model selector in [ModelSelectorBar].
 *
 * **Visual contract.** Same chevron / `SelectorButton` shape as the
 * model selector and the permission selector, so the three buttons
 * read as a single row at a glance.
 *
 * **Selected emphasis.** The currently-picked row in the popup is
 * rendered in [FontWeight.Bold]; the other rows are regular weight.
 * Keeps the user's choice scannable when the menu is open.
 *
 * **Centered rows.** All popup rows are horizontally centered. The
 * three level names are short and equal-width-ish, so a centered
 * layout reads as a tidy compact group rather than a left-aligned
 * stack hugging the gutter.
 *
 * **Tooltip.** The selector button surfaces a one-liner per level
 * ("the model will think lightly / with enhanced reasoning /
 * deeply") so a curious user can hover the closed button to learn
 * what each level does in plain English without opening the menu.
 *
 * **Always enabled.** The hint is a plain prompt suffix and works on
 * any model, so the dropdown is never grayed out. The previous
 * capability-gated design (disable when `reasoning = false`) was
 * removed because the gating contradicts the prompt-injection
 * approach — a non-reasoning model still benefits from the hint,
 * it just yields less.
 */
@Composable
fun ThinkingLevelSelector(
  selectedLevel: ThinkingLevel,
  onSelect: (ThinkingLevel) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  var showMenu by remember { mutableStateOf(false) }
  val dismiss: () -> Unit = { showMenu = false }

  Box(modifier = modifier) {
    SelectorButton(
      text = thinkingButtonLabel(selectedLevel),
      onClick = { if (enabled) showMenu = true },
      contentDescription = thinkingTooltip(selectedLevel),
      isButtonEnabled = enabled,
    )
    if (showMenu) {
      PopupMenu(
        onDismissRequest = { dismiss(); true },
        horizontalAlignment = Alignment.Start
      ) {
        passiveItem {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = GradumSpacing.sm),
            horizontalArrangement = Arrangement.Center
          ) {
            Text(
              text = message("gradum.thinking"),
              fontWeight = FontWeight.SemiBold
            )
          }
        }
        ThinkingLevel.entries.forEach { level ->
          selectableItem(
            selected = level == selectedLevel,
            onClick = { onSelect(level); dismiss() }
          ) {
            ThinkingLevelRow(level = level, isSelected = level == selectedLevel)
          }
        }
      }
    }
  }
}

@Composable
private fun ThinkingLevelRow(level: ThinkingLevel, isSelected: Boolean) {
  Text(
    text = thinkingLabel(level),
    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
    textAlign = TextAlign.Center,
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        vertical = GradumSpacing.xs,
        horizontal = GradumSpacing.md
      )
  )
}

/**
 * Label shown on the [SelectorButton] itself. The "思考：" / "Thinking:"
 * prefix mirrors the user's locale and keeps the button scannable
 * alongside the model and permission buttons.
 */
private fun thinkingButtonLabel(level: ThinkingLevel): String =
  message("gradum.thinking.selector.label", thinkingLabel(level))

/**
 * One-liner shown in the closed-button tooltip. Tells the user what
 * the model will *do* with this level, not what the level is named —
 * the level name is already on the button face, the tooltip is the
 * place for the "so what".
 */
private fun thinkingTooltip(level: ThinkingLevel): String = message(
  when (level) {
    ThinkingLevel.LOW -> "gradum.thinking.tooltip.low"
    ThinkingLevel.MEDIUM -> "gradum.thinking.tooltip.medium"
    ThinkingLevel.HIGH -> "gradum.thinking.tooltip.high"
  }
)

private fun thinkingLabel(level: ThinkingLevel): String = message(
  when (level) {
    ThinkingLevel.LOW -> "gradum.thinking.low"
    ThinkingLevel.MEDIUM -> "gradum.thinking.medium"
    ThinkingLevel.HIGH -> "gradum.thinking.high"
  }
)
