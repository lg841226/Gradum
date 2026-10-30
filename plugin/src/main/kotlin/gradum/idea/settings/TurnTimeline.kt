@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumBundle.message
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LocalTooltipStyle
import org.jetbrains.jewel.ui.typography

@Composable
fun TurnTimeline(
  turn: TurnEventLoader.Turn,
  tickStepMs: Long = 100L,
  modifier: Modifier = Modifier
) {
  val gap = 12.dp
  val rowHeight = 32.dp
  val rulerHeight = 20.dp
  val minBlockWidth = 8.dp
  val blockCornerRadius = 2.dp
  val blockVerticalInset = 8.dp
  val blockHeightDp = rowHeight - blockVerticalInset * 2
  val tickSpacing = 80.dp

  val turnDurationMs = turn.durationMs.coerceAtLeast(1)
  val trackAliases = turn.events.map { it.alias }.distinct()

  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val labelStyle = JewelTheme.typography.labelTextStyle
  val labelColumnWidth = remember(trackAliases, textMeasurer, labelStyle) {
    val widestPx = trackAliases.maxOfOrNull { label ->
      textMeasurer.measure(label, style = labelStyle).size.width
    } ?: 0
    with(density) { widestPx.toDp() }
  }

  val failedBlockColor = JewelTheme.globalColors.text.error
  val normalBlockColor = JewelTheme.globalColors.outlines.focused
  val rulerColor = JewelTheme.globalColors.text.disabled
  val gridColor = JewelTheme.globalColors.borders.normal

  var hoveredEvent by remember { mutableStateOf<TurnEventLoader.TurnEvent?>(null) }

  val pxPerMs = with(density) { tickSpacing.toPx() } / tickStepMs
  val trackWidth = with(density) { (turnDurationMs * pxPerMs).toDp() }
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
              val tickX = tickMs * pxPerMs
              if (tickMs > 0L && tickMs % 1000L == 0L) {
                val layout = textMeasurer.measure(
                  "${tickMs / 1000L}s",
                  style = labelStyle.copy(color = rulerColor)
                )
                if (tickX + 4.dp.toPx() + layout.size.width <= canvasWidth) {
                  drawText(layout, topLeft = Offset(tickX + 4.dp.toPx(), 0f))
                }
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

            val hitRegions: List<Pair<TurnEventLoader.TurnEvent, Rect>> = remember(
              eventsInTrack, tracks, pxPerMs, blockHeightDp, blockVerticalInset, turn.turnStartMs
            ) {
              buildList {
                for (event in eventsInTrack) {
                  val trackIndex = tracks[event] ?: continue
                  val topPx = with(density) { blockVerticalInset.toPx() + trackIndex * (blockHeightDp.toPx() + 2.dp.toPx()) }
                  val leftPx = (event.startMs - turn.turnStartMs) * pxPerMs
                  val widthPx = (event.durationMs * pxPerMs)
                    .coerceAtLeast(with(density) { minBlockWidth.toPx() })
                  add(event to Rect(leftPx, topPx, leftPx + widthPx, topPx + with(density) { blockHeightDp.toPx() }))
                }
              }
            }

            TooltipArea(
              modifier = Modifier
                .width(trackWidth)
                .height(rowHeight)
                .pointerInput(hitRegions) {
                  awaitPointerEventScope {
                    while (true) {
                      val pointerEvent = awaitPointerEvent()
                      if (pointerEvent.type == PointerEventType.Exit) {
                        hoveredEvent = null
                      } else {
                        val position = pointerEvent.changes.first().position
                        hoveredEvent = hitRegions
                          .firstOrNull { (_, rect) -> rect.contains(position) }
                          ?.first
                      }
                    }
                  }
                },
              tooltip = {
                val hovered = hoveredEvent ?: return@TooltipArea
                val tooltipStyle = LocalTooltipStyle.current
                val tooltipText =
                  if (hovered.isFailed) {
                    message("gradum.timeline.tooltip.failure", hovered.durationMs)
                  } else {
                    message("gradum.timeline.tooltip.success", hovered.durationMs)
                  }
                Text(text = tooltipText, color = tooltipStyle.colors.content)
              }
            ) {
              Canvas(
                modifier = Modifier
                  .width(trackWidth)
                  .height(rowHeight)
              ) {
                val canvasWidth = size.width
                for (tickMs in tickMarksMs) {
                  val gridX = tickMs * pxPerMs
                  drawLine(
                    color = gridColor,
                    start = Offset(gridX, 0f),
                    end = Offset(gridX, size.height)
                  )
                }

                for (event in eventsInTrack) {
                  val trackIndex = tracks[event] ?: 0
                  val yOffset = blockVerticalInset.toPx() + trackIndex * (blockHeightDp.toPx() + 2.dp.toPx())

                  val rawX = (event.startMs - turn.turnStartMs) * pxPerMs
                  val rawW = event.durationMs * pxPerMs
                  val minWidthPx = minBlockWidth.toPx()

                  if (rawW < minWidthPx) {
                    val diamondSize = 10.dp.toPx()
                    val endMarginPx = 16.dp.toPx()
                    val centerUpperBound = canvasWidth - diamondSize / 2f - endMarginPx
                    val diamondCenter = Offset(
                      x = (rawX + minWidthPx / 2f).coerceAtMost(centerUpperBound),
                      y = yOffset + diamondSize / 2f
                    )
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
                  val endMarginPx = 16.dp.toPx()
                  val x = rawX.coerceAtMost(canvasWidth - minWidthPx - endMarginPx)
                  val blockWidth = rawW.coerceAtLeast(minWidthPx)
                  val blockHeight = blockHeightDp.toPx()

                  drawRoundRect(
                    style = style,
                    color = blockColor,
                    topLeft = Offset(x, yOffset),
                    size = Size(blockWidth, blockHeight),
                    cornerRadius = CornerRadius(blockCornerRadius.toPx(), blockCornerRadius.toPx())
                  )

                  if (isFailed) {
                    val outline = Path().apply {
                      addRoundRect(
                        RoundRect(
                          rect = Rect(x, yOffset, x + blockWidth, yOffset + blockHeight),
                          cornerRadius = CornerRadius(blockCornerRadius.toPx(), blockCornerRadius.toPx())
                        )
                      )
                    }
                    clipPath(outline) {
                      val hatchStep = 4.dp.toPx()
                      var hatchX = 0f
                      while (hatchX < blockWidth + blockHeight) {
                        drawLine(
                          color = failedBlockColor,
                          start = Offset(x + hatchX, yOffset + blockHeight),
                          end = Offset(x + hatchX - blockHeight, yOffset),
                          strokeWidth = 1.dp.toPx()
                        )
                        hatchX += hatchStep
                      }
                    }
                  }
                }
              }
            }
          }

          Canvas(
            modifier = Modifier
              .width(trackWidth)
              .height(rulerHeight)
          ) {
            val canvasWidth = size.width
            for (tickMs in tickMarksMs) {
              val tickX = tickMs * pxPerMs
              if (tickMs > 0L) {
                val layout = textMeasurer.measure(
                  "${tickMs}ms",
                  style = labelStyle.copy(color = rulerColor)
                )
                if (tickX + 4.dp.toPx() + layout.size.width <= canvasWidth) {
                  drawText(layout, topLeft = Offset(tickX + 4.dp.toPx(), 0f))
                }
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
