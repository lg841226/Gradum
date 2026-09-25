/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AskCard.kt  2026-09-25 11:37:17 Changed by gwy
 */

@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.AskChoice
import gradum.idea.chat.model.AskChoiceMeaning
import gradum.idea.chat.model.AskPrompt
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val askIconSlotDp: Int = 16
private const val askArrowSlotDp: Int = 16
private const val ASK_FADE_IN_MS: Int = 150
private const val askInputWidthDp: Int = 160
private const val askOptionNumberWidthDp: Int = 24

/**
 * Renders the agent-initiated question card. Shows title/details, then the
 * interaction body (discrete choice buttons, or a free-text field + send
 * button). A response POSTs the answer back to the server and locks the card
 * into an "answered" state so it cannot be submitted twice. Fades in quickly
 * when first shown.
 */
@Composable
fun AskCard(
  block: RenderBlock.AskInteraction,
  onRespondToAsk: suspend (
    sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean
  ) -> Unit
) {
  val scope: CoroutineScope = rememberCoroutineScope()
  val paragraphStyle: TextStyle = rememberGradumParagraphTextStyle()
  val optionStyle: TextStyle = paragraphStyle
  val globalColors: GlobalColors = LocalGlobalColors.current
  val editorTextStyle: TextStyle = JewelTheme.editorTextStyle
  val detailStyle: TextStyle = JewelTheme.typography.editorTextStyle

  var responded: Boolean by remember { mutableStateOf(value = false) }
  val titleStyle: TextStyle = paragraphStyle.copy(fontWeight = FontWeight.SemiBold)
  val optionNumberStyle: TextStyle = paragraphStyle.copy(
    color = globalColors.text.info,
    fontFamily = editorTextStyle.fontFamily
  )

  val alpha = remember { Animatable(initialValue = 0f) }
  LaunchedEffect(Unit) {
    alpha.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = ASK_FADE_IN_MS))
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { this.alpha = alpha.value },
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    AskDivider(color = globalColors.text.disabled)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.General.Warning,
        modifier = Modifier.width(askIconSlotDp.dp)
      )
      if (block.title.isNotBlank()) Text(text = block.title, style = titleStyle)
    }
    if (block.details.isNotBlank()) {
      Row(
        modifier = Modifier
          .padding(start = askIconSlotDp.dp + GradumSpacing.sml)
          .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = block.details,
          style = detailStyle,
          maxLines = 1,
          softWrap = false
        )
      }
    }

    Spacer(modifier = Modifier.height(GradumSpacing.md))

    when (val prompt: AskPrompt = block.prompt) {
      is AskPrompt.Choices -> {
        val defaultIndex: Int = prompt.choices.indexOfFirst { it.id == block.default }
          .coerceAtLeast(0)
        var selectedIndex: Int by remember { mutableStateOf(defaultIndex) }
        val submit: (AskChoice) -> Unit = { option: AskChoice ->
          if (!responded) {
            responded = true
            scope.launch {
              onRespondToAsk(block.sessionId, block.requestId, option.id, null, false)
            }
          }
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { keyEvent: KeyEvent ->
              if (responded) return@onPreviewKeyEvent false
              if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              when (keyEvent.key) {
                Key.DirectionUp -> {
                  selectedIndex =
                    (selectedIndex - 1 + prompt.choices.size) % prompt.choices.size
                  true
                }

                Key.DirectionDown -> {
                  selectedIndex = (selectedIndex + 1) % prompt.choices.size
                  true
                }

                Key.Enter -> {
                  submit(prompt.choices[selectedIndex])
                  true
                }

                else -> false
              }
            },
          verticalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
        ) {
          Text(
            style = titleStyle,
            text = message("gradum.ask.static.options")
          )
          prompt.choices.forEachIndexed { index: Int, option: AskChoice ->
            val selected: Boolean = index == selectedIndex
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !responded) { selectedIndex = index },
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
            ) {
              if (selected) {
                Icon(
                  contentDescription = null,
                  key = AllIconsKeys.Vcs.Arrow_right,
                  modifier = Modifier.width(askArrowSlotDp.dp)
                )
              } else {
                Spacer(modifier = Modifier.width(askArrowSlotDp.dp))
              }
              Box(
                modifier = Modifier.width(askOptionNumberWidthDp.dp),
                contentAlignment = Alignment.CenterEnd
              ) {
                Text(
                  maxLines = 1,
                  text = "${index + 1}.",
                  style =
                    if (selected) optionNumberStyle
                    else optionNumberStyle.copy(color = globalColors.text.disabled)
                )
              }
              Text(
                style = optionStyle,
                text = askChoiceLabel(option)
              )
            }
          }
        }
      }

      is AskPrompt.Input -> {
        val inputState: TextFieldState = remember(key1 = block.default) {
          TextFieldState(initialText = block.default)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
          TextField(
            state = inputState,
            modifier = Modifier.width(askInputWidthDp.dp),
            enabled = !responded,
            placeholder =
              if (prompt.placeholder.isNotBlank()) {
                { Text(text = prompt.placeholder) }
              } else null
          )
          OutlinedButton(
            enabled = !responded && inputState.text.isNotBlank(),
            onClick = {
              responded = true
              val submitted: String = inputState.text.toString()
              scope.launch {
                onRespondToAsk(block.sessionId, block.requestId, null, submitted, false)
              }
            }
          ) {
            Text(text = message("gradum.ask.static.send"))
          }
        }
      }
    }

    AskDivider(color = globalColors.text.disabled)
  }
}

/**
 * Maps a choice to its localized button label. A server-supplied `labelKey`
 * wins (lets the server pin write/read-specific copy); otherwise the choice
 * falls back to the semantic-code mapping shared by all ask cards.
 */
private fun askChoiceLabel(option: AskChoice): String {
  val labelKey: String? = option.labelKey
  if (!labelKey.isNullOrBlank()) return message(labelKey)
  return when (option.semantics) {
    AskChoiceMeaning.ALLOW_ONCE -> message("gradum.ask.choice.allow_once")
    AskChoiceMeaning.ALLOW_ALWAYS -> message("gradum.ask.choice.allow_always")
    AskChoiceMeaning.REJECT -> message("gradum.ask.choice.reject")
    else -> option.semantics
  }
}

/** A 1-dp dashed horizontal divider used to frame the ask card. */
@Composable
private fun AskDivider(color: Color) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .drawBehind {
        drawLine(
          color = color,
          start = Offset(x = 0f, y = 0f),
          end = Offset(x = size.width, y = 0f),
          strokeWidth = 1.dp.toPx(),
          pathEffect = PathEffect.dashPathEffect(
            intervals = floatArrayOf(2.dp.toPx(), 2.dp.toPx())
          )
        )
      }
  )
}
