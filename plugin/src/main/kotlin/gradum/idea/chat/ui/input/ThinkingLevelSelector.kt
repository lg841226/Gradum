package gradum.idea.chat.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Dropdown for picking the model's thinking strength, rendered next to
 * the model selector in [ModelSelectorBar].
 *
 * Two vocabularies, one control:
 * - models with graded depth (`supportsLevels = true`) offer
 *   Low / Medium / High;
 * - models with a binary switch (`supportsLevels = false`) offer the
 *   Reasoning / Instant pair, mapped to [ThinkingLevel.MEDIUM] and
 *   [ThinkingLevel.OFF].
 *
 * Visual contract. Same chevron / `SelectorButton` shape as the
 * model selector and the permission selector, so the three buttons
 * read as a single row at a glance.
 *
 * Selected emphasis. The currently-picked row in the popup is
 * rendered in [FontWeight.SemiBold]; the other rows are regular weight.
 * Keeps the user's choice scannable when the menu is open.
 *
 * Centered rows. All popup rows are horizontally centered. The
 * labels are short and equal-width-ish, so a centered layout reads as
 * a tidy compact group rather than a left-aligned stack hugging the
 * gutter.
 *
 * Tooltip. The selector button surfaces a one-liner per option so a
 * curious user can hover the closed button to learn what the current
 * choice does in plain English without opening the menu.
 *
 * Capability-gated. The caller hides this selector for models that
 * cannot think at all (`reasoning = false`) and passes
 * `supportsLevels = false` for thinking models that expose no depth
 * levels, which swaps the menu to the on/off pair.
 */
@Composable
fun ThinkingLevelSelector(
  selectedLevel: ThinkingLevel,
  modifier: Modifier = Modifier,
  supportsLevels: Boolean = true,
  onSelect: (ThinkingLevel) -> Unit
) {
  var showMenu by remember { mutableStateOf(false) }
  val dismiss: () -> Unit = { showMenu = false }

  val options: List<ThinkingOption> =
    if (supportsLevels) {
      listOf(
        ThinkingOption(
          level = ThinkingLevel.LOW,
          label = message("gradum.thinking.low"),
          tooltip = message("gradum.thinking.tooltip.low")
        ),
        ThinkingOption(
          level = ThinkingLevel.MEDIUM,
          label = message("gradum.thinking.medium"),
          tooltip = message("gradum.thinking.tooltip.medium")
        ),
        ThinkingOption(
          level = ThinkingLevel.HIGH,
          label = message("gradum.thinking.high"),
          tooltip = message("gradum.thinking.tooltip.high")
        )
      )
    } else {
      listOf(
        ThinkingOption(
          level = ThinkingLevel.MEDIUM,
          label = message("gradum.thinking.on"),
          tooltip = message("gradum.thinking.tooltip.on")
        ),
        ThinkingOption(
          level = ThinkingLevel.OFF,
          label = message("gradum.thinking.off"),
          tooltip = message("gradum.thinking.tooltip.off")
        )
      )
    }
  val selectedOption: ThinkingOption = options.firstOrNull { it.level == selectedLevel } ?: options.first()

  Box(modifier = modifier) {
    SelectorButton(
      text = selectedOption.label,
      onClick = { showMenu = true },
      contentDescription = selectedOption.tooltip
    )
    if (showMenu) {
      PopupMenu(
        onDismissRequest = { dismiss(); true },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        passiveItem {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(
                vertical = GradumSpacing.sm,
                horizontal = GradumSpacing.lrl
              )
          ) {
            Text(
              text = message(if (supportsLevels) "gradum.thinking.level" else "gradum.thinking.mode"),
              fontWeight = FontWeight.SemiBold
            )
          }
        }
        options.forEach { option ->
          selectableItem(
            selected = option.level == selectedOption.level,
            onClick = { onSelect(option.level); dismiss() }
          ) {
            ThinkingLevelRow(label = option.label, isSelected = option.level == selectedOption.level)
          }
        }
      }
    }
  }
}

@Composable
private fun ThinkingLevelRow(label: String, isSelected: Boolean) {
  Row {
    Text(
      text = label,
      textAlign = TextAlign.Center,
      fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = GradumSpacing.xs)
    )
  }
}

private data class ThinkingOption(
  val level: ThinkingLevel,
  val label: String,
  val tooltip: String
)
