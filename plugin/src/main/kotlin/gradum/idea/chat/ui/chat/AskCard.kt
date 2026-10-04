@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val askIconSlotDp: Int = 16
private const val askArrowSlotDp: Int = 16
private const val ASK_FADE_IN_MS: Int = 150
private const val ASK_BORDER_BEAM_MS: Int = 2600
private const val askInputWidthDp: Int = 160
private const val askOptionNumberWidthDp: Int = 24
private val askBorderCorner: Dp = 6.dp
private val askBeamStroke: Dp = 2.dp
private val askBeamHalo: Dp = 5.dp
private const val askBeamHaloAlpha: Float = 0.2f
private const val askBeamSteps: Int = 24
private const val askBeamHeadStep: Int = 8
private const val askBeamHeadAlpha: Float = 0.95f
private const val askBeamTailDecay: Float = 0.55f
private const val askBeamFrontFade: Float = 0.47f
private const val askBeamFrontDecay: Float = 0.30f

/**
 * Renders the agent-initiated question card. Shows title/details, then the
 * interaction body (discrete choice buttons, or a free-text field + send
 * button). A response POSTs the answer back to the server, locks the card
 * against double submission, and invokes [onResponded] so the parent can drop
 * the card from the layout entirely. Fades in quickly when first shown; while
 * awaiting a response a blue beam sweeps around the rounded border.
 */
@Composable
fun AskCard(
  block: RenderBlock.AskInteraction,
  onRespondToAsk: suspend (
    sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean
  ) -> Unit,
  onResponded: () -> Unit = {}
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

  val beamTransition: InfiniteTransition = rememberInfiniteTransition(label = "ask_border_beam")
  val beamPhase: Float by beamTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = ASK_BORDER_BEAM_MS, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "ask_beam_phase"
  )

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { this.alpha = alpha.value }
      .border(
        width = 1.dp,
        color = globalColors.borders.disabled,
        shape = RoundedCornerShape(size = askBorderCorner)
      )
      .drawWithContent {
        drawContent()
        if (!responded) {
          drawAskBorderBeam(
            phase = beamPhase,
            cornerRadiusPx = askBorderCorner.toPx(),
            beamColor = globalColors.outlines.focused
          )
        }
      }
      .padding(all = GradumSpacing.md),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
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
        val defaultIndex: Int =
          (prompt.choices.indexOfFirst { it.semantics == block.default }
            .takeIf { index -> index >= 0 }
            ?: prompt.choices.indexOfFirst { it.semantics == AskChoiceMeaning.REJECT })
            .coerceAtLeast(0)
        var selectedIndex: Int by remember { mutableStateOf(defaultIndex) }
        val submit: (AskChoice) -> Unit = { option: AskChoice ->
          if (!responded) {
            responded = true
            onResponded()
            scope.launch {
              onRespondToAsk(block.sessionId, block.requestId, option.semantics, null, false)
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
              onResponded()
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

/**
 * Draws the animated blue beam that travels around the card's rounded
 * border. A sweep gradient - a compact arc of light anchored at the
 * card center - is painted onto the border path while the canvas is
 * rotated by the current phase; the path itself is counter-rotated
 * point by point so the rounded rectangle stays axis-aligned while the
 * gradient's bright arc circles the frame. The gradient's color wheel
 * carries a fading tail behind the head, producing a comet that loops
 * the border while the card awaits a response.
 */
private fun DrawScope.drawAskBorderBeam(
  phase: Float, cornerRadiusPx: Float, beamColor: Color
) {
  val width = size.width
  val height = size.height
  val straightW = width - 2f * cornerRadiusPx
  val straightH = height - 2f * cornerRadiusPx
  if (straightW <= 0f || straightH <= 0f) return

  val centerX = width / 2f
  val centerY = height / 2f
  val angleDegrees = phase * 360f
  val angleRad: Double = phase * 2.0 * PI
  val cosA = cos(angleRad).toFloat()
  val sinA = sin(angleRad).toFloat()

  fun unrotate(x: Float, y: Float): Offset {
    val dx = x - centerX
    val dy = y - centerY
    return Offset(
      x = centerX + dx * cosA + dy * sinA,
      y = centerY - dx * sinA + dy * cosA
    )
  }

  fun Path.unrotatedMoveTo(x: Float, y: Float) {
    val point = unrotate(x, y)
    moveTo(point.x, point.y)
  }

  fun Path.unrotatedLineTo(x: Float, y: Float) {
    val point = unrotate(x, y)
    lineTo(point.x, point.y)
  }

  fun Path.unrotatedCubicTo(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    x3: Float, y3: Float
  ) {
    val p1 = unrotate(x1, y1)
    val p2 = unrotate(x2, y2)
    val p3 = unrotate(x3, y3)
    cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
  }

  val k = 0.5523f * cornerRadiusPx
  val path = Path().apply {
    unrotatedMoveTo(cornerRadiusPx, 0f)
    unrotatedLineTo(width - cornerRadiusPx, 0f)
    unrotatedCubicTo(
      width - cornerRadiusPx + k, 0f,
      width, cornerRadiusPx - k,
      width, cornerRadiusPx
    )
    unrotatedLineTo(width, height - cornerRadiusPx)
    unrotatedCubicTo(
      width, height - cornerRadiusPx + k,
      width - cornerRadiusPx + k, height,
      width - cornerRadiusPx, height
    )
    unrotatedLineTo(cornerRadiusPx, height)
    unrotatedCubicTo(
      cornerRadiusPx - k, height,
      0f, height - cornerRadiusPx + k,
      0f, height - cornerRadiusPx
    )
    unrotatedLineTo(0f, cornerRadiusPx)
    unrotatedCubicTo(
      0f, cornerRadiusPx - k,
      cornerRadiusPx - k, 0f,
      cornerRadiusPx, 0f
    )
    close()
  }

  val beamColors: List<Color> = List(askBeamSteps) { index: Int ->
    val distance = index - askBeamHeadStep
    val trailAlpha: Float =
      when {
        distance == 0 -> askBeamHeadAlpha
        distance > 0 -> {
          var alpha = askBeamHeadAlpha * askBeamFrontFade
          repeat(times = distance - 1) { alpha *= askBeamFrontDecay }
          alpha
        }

        else -> {
          var alpha = askBeamHeadAlpha * askBeamTailDecay
          repeat(times = -distance - 1) { alpha *= askBeamTailDecay }
          alpha
        }
      }
    beamColor.copy(alpha = trailAlpha.coerceIn(minimumValue = 0f, maximumValue = 1f))
  }
  val beamBrush = Brush.sweepGradient(
    colors = beamColors,
    center = Offset(x = centerX, y = centerY)
  )

  drawIntoCanvas { canvas ->
    canvas.save()
    canvas.translate(centerX, centerY)
    canvas.rotate(angleDegrees)
    canvas.translate(-centerX, -centerY)
    this@drawAskBorderBeam.drawPath(
      path = path,
      brush = beamBrush,
      alpha = askBeamHaloAlpha,
      style = Stroke(width = askBeamHalo.toPx())
    )
    this@drawAskBorderBeam.drawPath(
      path = path,
      brush = beamBrush,
      style = Stroke(width = askBeamStroke.toPx())
    )
    canvas.restore()
  }
}
