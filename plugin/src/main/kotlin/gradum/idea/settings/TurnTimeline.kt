package gradum.idea.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

@Composable
fun TurnTimeline(
  turn: TurnEventLoader.Turn, modifier: Modifier = Modifier
) {
  val gap = 12.dp
  val rowHeight = 32.dp
  val rulerHeight = 20.dp
  val minBlockWidth = 8.dp
  val blockCornerRadius = 2.dp
  val blockVerticalInset = 8.dp
  val blockHeightDp = rowHeight - blockVerticalInset * 2
  val tickStepMs = 100L
  val tickSpacing = 80.dp
  val thinkLabel = "Thinking"

  val turnDurationMs = turn.durationMs.coerceAtLeast(1)
  val trackAliases = turn.events.map { it.alias }.distinct()
  val trackLabels = trackAliases + thinkLabel

  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val labelStyle = JewelTheme.typography.labelTextStyle
  val labelColumnWidth = remember(trackLabels, textMeasurer, labelStyle) {
    val widestPx = trackLabels.maxOfOrNull { label ->
      textMeasurer.measure(label, style = labelStyle).size.width
    } ?: 0
    with(density) { widestPx.toDp() }
  }

  val failedBlockColor = JewelTheme.globalColors.text.error.copy(alpha = 0.8f)
  val normalBlockColor = JewelTheme.globalColors.outlines.focused.copy(alpha = 0.6f)
  val thinkBlockColor = JewelTheme.globalColors.text.disabled.copy(alpha = 0.8f)
  val rulerColor = JewelTheme.globalColors.text.disabled
  val gridColor = JewelTheme.globalColors.borders.normal

  val sortedEvents = turn.events.sortedBy { it.startMs }
  val thinkSegments = buildList {
    var cursor = turn.turnStartMs
    for ((endMs, startMs) in sortedEvents) {
      if (startMs > cursor) add(cursor to startMs)
      if (endMs > cursor) cursor = endMs
    }
    if (cursor < turn.turnEndMs) add(cursor to turn.turnEndMs)
  }

  val tickCount = (turnDurationMs / tickStepMs).coerceAtLeast(1)
  val trackWidth = with(density) { (tickCount * tickSpacing.toPx()).toDp() }
  val tickMarksMs = remember(turnDurationMs, tickStepMs) {
    buildList {
      var tickMs = 0L
      while (tickMs <= turnDurationMs) {
        add(tickMs)
        tickMs += tickStepMs
      }
    }
  }

  val scrollState = rememberScrollState()

  Row(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.width(labelColumnWidth)) {
      Spacer(modifier = Modifier.height(rulerHeight))
      for (alias in trackAliases) {
        TrackLabel(alias, labelColumnWidth, rowHeight, labelStyle)
      }
      TrackLabel(thinkLabel, labelColumnWidth, rowHeight, labelStyle)
      Spacer(modifier = Modifier.height(rulerHeight))
    }

    Column(modifier = Modifier.weight(1f)) {
      Row(
        modifier = Modifier
          .padding(start = gap)
          .horizontalScroll(scrollState)
      ) {
        Column(modifier = Modifier.width(trackWidth)) {
          Canvas(
            modifier = Modifier
              .width(trackWidth)
              .height(rulerHeight)
          ) {
            val canvasWidth = size.width
            for (tickMs in tickMarksMs) {
              val tickX = tickMs.toFloat() / turnDurationMs * canvasWidth
              if (tickMs > 0L && tickMs % 1000L == 0L) {
                drawText(
                  textMeasurer = textMeasurer,
                  text = "${tickMs / 1000L}s",
                  topLeft = Offset(tickX + 4.dp.toPx(), 0f),
                  style = labelStyle.copy(color = rulerColor)
                )
              }
              drawLine(
                color = gridColor,
                start = Offset(tickX, rulerHeight.toPx() * 0.7f),
                end = Offset(tickX, rulerHeight.toPx())
              )
            }
          }

          for (alias in trackAliases) {
            val eventsInTrack = turn.events.filter { it.alias == alias }
            val tracks = calculateTrackAssignments(eventsInTrack)

            Canvas(
              modifier = Modifier
                .width(trackWidth)
                .height(rowHeight)
            ) {
              val canvasWidth = size.width
              for (tickMs in tickMarksMs) {
                val gridX = tickMs.toFloat() / turnDurationMs * canvasWidth
                drawLine(
                  color = gridColor,
                  start = Offset(gridX, 0f),
                  end = Offset(gridX, size.height)
                )
              }

              for (event in eventsInTrack) {
                val trackIndex = tracks[event] ?: 0
                val yOffset = blockVerticalInset.toPx() + trackIndex * (blockHeightDp.toPx() + 2.dp.toPx())

                val leftPercent = (event.startMs - turn.turnStartMs).toFloat() / turnDurationMs
                val widthPercent = event.durationMs.toFloat() / turnDurationMs

                val x = leftPercent * canvasWidth
                val rawW = widthPercent * canvasWidth
                val minWidthPx = minBlockWidth.toPx()

                if (rawW < minWidthPx) {
                  val diamondSize = 10.dp.toPx()
                  val diamondCenter = Offset(x + minWidthPx / 2f, yOffset + diamondSize / 2f)
                  rotate(degrees = 45f, pivot = diamondCenter) {
                    drawRoundRect(
                      style = Stroke(width = 1.dp.toPx()),
                      size = Size(diamondSize, diamondSize),
                      color = if (event.isFailed) failedBlockColor else normalBlockColor,
                      cornerRadius = CornerRadius(blockCornerRadius.toPx(), blockCornerRadius.toPx()),
                      topLeft = Offset(diamondCenter.x - diamondSize / 2f, diamondCenter.y - diamondSize / 2f)
                    )
                  }
                  continue
                }

                val isFailed = event.isFailed
                val blockColor = if (isFailed) failedBlockColor else normalBlockColor
                val style = if (isFailed) Stroke(width = 1.dp.toPx()) else Fill

                drawRoundRect(
                  style = style,
                  color = blockColor,
                  topLeft = Offset(x, yOffset),
                  size = Size(rawW, blockHeightDp.toPx()),
                  cornerRadius = CornerRadius(blockCornerRadius.toPx(), blockCornerRadius.toPx())
                )

                val durationText = "${event.durationMs}ms"
                val durationLayout = textMeasurer.measure(durationText, style = labelStyle)
                val textWidthPx = durationLayout.size.width
                if (rawW >= textWidthPx + 8.dp.toPx()) {
                  val textColor = blockColor.copy(alpha = 1f)
                  drawText(
                    textLayoutResult = durationLayout,
                    color = textColor,
                    topLeft = Offset(
                      x + (rawW - textWidthPx) / 2f,
                      yOffset + (blockHeightDp.toPx() - durationLayout.size.height) / 2f
                    )
                  )
                }
              }
            }
          }

          Canvas(
            modifier = Modifier
              .width(trackWidth)
              .height(rowHeight)
          ) {
            val canvasWidth = size.width
            for (tickMs in tickMarksMs) {
              val gridX = tickMs.toFloat() / turnDurationMs * canvasWidth
              drawLine(
                color = gridColor,
                start = Offset(gridX, 0f),
                end = Offset(gridX, size.height)
              )
            }
            for ((segmentStartMs, segmentEndMs) in thinkSegments) {
              val x = (segmentStartMs - turn.turnStartMs).toFloat() / turnDurationMs * canvasWidth
              val w = (segmentEndMs - segmentStartMs).toFloat() / turnDurationMs * canvasWidth
              if (w <= 0f) continue
              drawRoundRect(
                style = Fill,
                color = thinkBlockColor,
                topLeft = Offset(x, blockVerticalInset.toPx()),
                size = Size(w, blockHeightDp.toPx()),
                cornerRadius = CornerRadius(blockCornerRadius.toPx(), blockCornerRadius.toPx())
              )
            }
          }

          Canvas(
            modifier = Modifier
              .width(trackWidth)
              .height(rulerHeight)
          ) {
            val canvasWidth = size.width
            for (tickMs in tickMarksMs) {
              val tickX = tickMs.toFloat() / turnDurationMs * canvasWidth
              if (tickMs > 0L) {
                drawText(
                  textMeasurer = textMeasurer,
                  text = "${tickMs}ms",
                  topLeft = Offset(tickX + 4.dp.toPx(), 0f),
                  style = labelStyle.copy(color = rulerColor)
                )
              }
              drawLine(
                color = gridColor,
                start = Offset(tickX, 0f),
                end = Offset(tickX, rulerHeight.toPx())
              )
            }
          }
        }
      }

      HorizontalScrollbar(
        scrollState = scrollState,
        modifier = Modifier
          .padding(start = gap)
          .fillMaxWidth()
      )
    }
  }
}

@Composable
private fun TrackLabel(
  label: String,
  columnWidth: Dp,
  rowHeight: Dp,
  labelStyle: TextStyle
) {
  Box(
    modifier = Modifier
      .width(columnWidth)
      .height(rowHeight),
    contentAlignment = Alignment.CenterEnd
  ) {
    Text(
      text = label,
      softWrap = false,
      style = labelStyle,
      textAlign = TextAlign.End
    )
  }
}

/**
 * Calculates track assignments for overlapping events of one tool.
 * When two blocks overlap in time, the later one shifts down one track.
 */
private fun calculateTrackAssignments(
  events: List<TurnEventLoader.TurnEvent>
): Map<TurnEventLoader.TurnEvent, Int> {
  val trackMap = mutableMapOf<TurnEventLoader.TurnEvent, Int>()
  val trackEndTimes = mutableListOf<Long>()

  for (event in events.sortedBy { it.startMs }) {
    var assignedTrack = 0
    for (trackIndex in trackEndTimes.indices) {
      if (event.startMs >= trackEndTimes[trackIndex]) {
        assignedTrack = trackIndex
        break
      }
      assignedTrack = trackIndex + 1
    }

    while (trackEndTimes.size <= assignedTrack) {
      trackEndTimes.add(0L)
    }

    trackEndTimes[assignedTrack] = event.endMs
    trackMap[event] = assignedTrack
  }

  return trackMap
}
