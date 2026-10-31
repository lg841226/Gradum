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
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.badgeStyle
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

  val result: TurnEventLoader.LoadResult? = loadResult
  if (isLoading) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      GroupHeader(
        text = message("gradum.settings.statistics.title"),
        modifier = Modifier.weight(1f)
      )
      Badge(style = JewelTheme.badgeStyle.graySecondary) {
        Text(text = message("gradum.settings.statistics.beta"))
      }
    }
    Box(
      modifier = modifier
        .fillMaxWidth()
        .padding(start = SETTINGS_GROUP_INDENT),
      contentAlignment = Alignment.Center
    ) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp))
    }
    return
  }
  if (result == null || result.error != null || result.turns.isEmpty()) return

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    GroupHeader(
      text = message("gradum.settings.statistics.title"),
      modifier = Modifier.weight(1f)
    )
    Badge(style = JewelTheme.badgeStyle.graySecondary) {
      Text(text = message("gradum.settings.statistics.beta"))
    }
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = SETTINGS_GROUP_INDENT),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    TimelineContent(
      turns = result.turns,
      currentTurnIndex = currentTurnIndex,
      onTurnChange = { newIndex ->
        currentTurnIndex = newIndex
      },
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

  val zoomTickStepsMs = listOf(4000L, 2000L, 200L, 100L, 50L)
  var zoomIndex by remember { mutableIntStateOf(3) }

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
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  onPrevious: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
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
