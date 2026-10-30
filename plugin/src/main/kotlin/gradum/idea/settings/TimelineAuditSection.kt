package gradum.idea.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

@Composable
fun StatisticsAnalysisSection(modifier: Modifier = Modifier) {
  val uiScope = rememberCoroutineScope()
  var isLoading by remember { mutableStateOf(false) }
  var currentTurnIndex by remember { mutableStateOf(0) }
  var loadResult by remember { mutableStateOf<TurnEventLoader.LoadResult?>(null) }

  if (!isLoading && loadResult == null) {
    isLoading = true
    TurnEventLoader.loadTurnsAsync(uiScope) { result ->
      loadResult = result
      isLoading = false
      if (result.turns.isNotEmpty()) {
        currentTurnIndex = result.turns.lastIndex
      }
    }
  }

  GroupHeader(text = message("gradum.settings.statistics.title"))

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = SETTINGS_GROUP_INDENT),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    if (isLoading) {
      LoadingState()
    } else {
      loadResult?.let { result ->
        when {
          result.error != null -> ErrorState(result.error)
          result.turns.isEmpty() -> EmptyState()
          else ->
            TimelineContent(
              turns = result.turns,
              currentTurnIndex = currentTurnIndex,
              onTurnChange = { newIndex ->
                currentTurnIndex = newIndex
              },
            )
        }
      }
    }
  }
}

@Composable
private fun LoadingState() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = message("gradum.settings.statistics.loading"),
      color = JewelTheme.globalColors.text.info
    )
  }
}

@Composable
private fun ErrorState(error: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = message("gradum.settings.statistics.error", error),
      color = JewelTheme.globalColors.text.error,
    )
  }
}

@Composable
private fun EmptyState() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      color = JewelTheme.globalColors.text.info,
      text = message("gradum.settings.statistics.empty")
    )
  }
}

@Composable
private fun TimelineContent(
  currentTurnIndex: Int,
  onTurnChange: (Int) -> Unit,
  turns: List<TurnEventLoader.Turn>
) {
  val turn = turns[currentTurnIndex]
  val totalTurns = turns.size

  // Zoom levels ordered coarse -> fine; index 0 is the most zoomed out.
  val zoomTickStepsMs = listOf(4000L, 2000L, 200L, 100L, 50L)
  var zoomIndex by remember { mutableIntStateOf(3) }

  // A very short turn has nothing to gain from the coarsest levels; clamp
  // zooming out so the timeline always keeps at least 6 ticks of content.
  val minTicks = 6L
  val coarsestIndex = zoomTickStepsMs
    .indexOfFirst { step -> turn.durationMs / step >= minTicks }
    .let { if (it == -1) zoomTickStepsMs.lastIndex else it }

  Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.ml)) {
    TurnNavigationRow(
      totalCount = totalTurns,
      currentIndex = currentTurnIndex,
      zoomInEnabled = zoomIndex < zoomTickStepsMs.lastIndex,
      zoomOutEnabled = zoomIndex > coarsestIndex,
      onZoomIn = { if (zoomIndex < zoomTickStepsMs.lastIndex) zoomIndex += 1 },
      onZoomOut = { if (zoomIndex > coarsestIndex) zoomIndex -= 1 },
      onPrevious = {
        if (currentTurnIndex > 0)
          onTurnChange(currentTurnIndex - 1)
      },
      onNext = {
        if (currentTurnIndex < totalTurns - 1)
          onTurnChange(currentTurnIndex + 1)
      },
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.ml)
    ) {
      Text(
        fontWeight = FontWeight.Medium,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
        style = JewelTheme.typography.h4TextStyle,
        text = turn.userMessage.replace("\n", " ").trim()
      )

      Text(
        text = TurnEventLoader.formatWallClock(turn.turnStartMs),
        color = JewelTheme.globalColors.text.info
      )
    }

    TurnTimeline(
      turn = turn,
      tickStepMs = zoomTickStepsMs[zoomIndex.coerceAtLeast(coarsestIndex)],
      modifier = Modifier.fillMaxWidth()
    )
  }
}

@Composable
private fun TurnNavigationRow(
  totalCount: Int,
  currentIndex: Int,
  zoomInEnabled: Boolean,
  zoomOutEnabled: Boolean,
  onNext: () -> Unit,
  onPrevious: () -> Unit,
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    val prevEnabled = currentIndex > 0
    IconButton(
      onClick = onPrevious,
      enabled = prevEnabled,
    ) {
      Icon(
        key = AllIconsKeys.Actions.Play_back,
        contentDescription = message("gradum.settings.statistics.prev")
      )
    }

    Text(text = "${currentIndex + 1} / $totalCount")

    val nextEnabled = currentIndex < totalCount - 1
    IconButton(
      onClick = onNext,
      enabled = nextEnabled
    ) {
      Icon(
        key = AllIconsKeys.Actions.Play_forward,
        contentDescription = message("gradum.settings.statistics.next")
      )
    }

    Spacer(modifier = Modifier.weight(1f))

    IconButton(
      onClick = onZoomOut,
      enabled = zoomOutEnabled
    ) {
      Icon(
        key = AllIconsKeys.General.ZoomOut,
        contentDescription = message("gradum.settings.statistics.zoom.out")
      )
    }

    IconButton(
      onClick = onZoomIn,
      enabled = zoomInEnabled
    ) {
      Icon(
        key = AllIconsKeys.General.ZoomIn,
        contentDescription = message("gradum.settings.statistics.zoom.in")
      )
    }
  }
}
