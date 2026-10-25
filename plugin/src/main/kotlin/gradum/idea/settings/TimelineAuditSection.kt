package gradum.idea.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumBundle.message
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

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

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    TurnNavigationRow(
      turn = turn,
      totalCount = totalTurns,
      currentIndex = currentTurnIndex,
      onPrevious = {
        if (currentTurnIndex > 0) onTurnChange(currentTurnIndex - 1)
      },
      onNext = {
        if (currentTurnIndex < totalTurns - 1) onTurnChange(currentTurnIndex + 1)
      },
    )

    TurnTimeline(
      turn = turn,
      modifier = Modifier.fillMaxWidth()
    )
  }
}

@Composable
private fun TurnNavigationRow(
  turn: TurnEventLoader.Turn,
  totalCount: Int,
  currentIndex: Int,
  onNext: () -> Unit,
  onPrevious: () -> Unit
) {
  val userMessage = turn.userMessage.replace("\n", " ").trim()
  val duration = TurnEventLoader.formatDuration(turn.durationMs)
  val wallClock = TurnEventLoader.formatWallClock(turn.turnStartMs)

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
        key = AllIconsKeys.Actions.Back,
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
        key = AllIconsKeys.Actions.Forward,
        contentDescription = message("gradum.settings.statistics.next")
      )
    }

    Spacer(modifier = Modifier.weight(1f))

    val summary = message("gradum.settings.statistics.summary", wallClock, duration)
    Text(
      maxLines = 1,
      text = summary,
      overflow = TextOverflow.Ellipsis
    )

    Text(
      maxLines = 1,
      text = userMessage,
      overflow = TextOverflow.Ellipsis,
      color = JewelTheme.globalColors.text.info
    )
  }
}
