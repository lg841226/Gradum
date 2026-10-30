@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.common.verticalEdgeFade
import gradum.idea.chat.ui.markdown.GradumMarkdown
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Collapsible thinking indicator for assistant messages.
 *
 * Renders the LLM's accumulated reasoning as Markdown inside a
 * muted-gray palette so the whole block reads as ephemeral context
 * rather than a finished reply.
 *
 * While the block is live the label sweeps and counts up ("Thought for
 * X.X s"); once [isTaskComplete] flips it freezes at the measured duration.
 * Blocks that were already finished when first composed (history reload,
 * session switch) have no client-side start time and fall back to the plain
 * "Thought" label.
 *
 * Parameters:
 * - thinking: The accumulated thinking content from the LLM.
 * - isTaskComplete: When true, the block has stopped streaming: the sweep
 *   stops and the elapsed time freezes.
 * - startCollapsed: Whether the body starts folded.
 * - onUrlClick: Forwarded to the Markdown renderer for inline links.
 */
@Composable
fun ThinkingIndicator(
  thinking: String,
  modifier: Modifier = Modifier,
  clock: ThinkingClock? = null,
  isTaskComplete: Boolean = false,
  startCollapsed: Boolean = false,
  onUrlClick: (String) -> Unit = {}
) {
  if (thinking.isBlank()) return
  var isExpanded: Boolean by remember { mutableStateOf(value = !startCollapsed) }

  val thinkingStyle = rememberGradumParagraphTextStyle().copy(
    color = LocalGlobalColors.current.text.info
  )
  val labelStyle: TextStyle = rememberGradumParagraphTextStyle().copy(
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Medium
  )

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.clickable { isExpanded = !isExpanded },
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.Nodes.Related
      )
      ThinkingHeaderLabel(isTaskComplete = isTaskComplete, style = labelStyle, clock = clock)
      Icon(
        contentDescription = null,
        key =
          if (isExpanded) AllIconsKeys.General.ChevronDown
          else AllIconsKeys.General.ChevronRight
      )
    }

    AnimatedVisibility(visible = isExpanded) {
      val capHeight: Boolean = thinking.trim().length > PREVIEW_MAX_CHARS
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .then(if (capHeight) Modifier.heightIn(max = THINKING_MAX_HEIGHT) else Modifier)
          .then(if (capHeight) Modifier.verticalEdgeFade() else Modifier)
      ) {
        val scrollState = rememberScrollState()
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .then(if (capHeight) Modifier.verticalScroll(scrollState) else Modifier)
        ) {
          Spacer(modifier = Modifier.height(GradumSpacing.md))

          GradumMarkdown(text = thinking) {
            thinkingMode = true
            animationEnabled = false
            this.onUrlClick = onUrlClick
            paragraphStyle = thinkingStyle
          }
        }
        if (capHeight) {
          VerticalScrollbar(
            scrollState = scrollState,
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .fillMaxHeight()
          )
        }
      }
    }
  }
}


@Composable
private fun ThinkingHeaderLabel(
  isTaskComplete: Boolean, style: TextStyle, clock: ThinkingClock? = null
) {
  val elapsedMs: Long? = rememberThinkingElapsedMs(isComplete = isTaskComplete, clock = clock)
  val label: String =
    if (elapsedMs != null)
      message(
        key = "gradum.thinking.duration",
        String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0)
      )
    else
      message("gradum.thinking")

  val labelContent: @Composable () -> Unit = {
    if (isTaskComplete) {
      Text(text = label, style = style)
    } else {
      SweepLightText(text = label, style = style)
    }
  }

  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val template: String = remember { message("gradum.thinking.duration", "00.0") }
  val minLabelWidth: Dp = remember(template, style, density) {
    with(density) {
      textMeasurer.measure(text = template, style = style).size.width.toDp()
    }
  }

  if (elapsedMs == null) {
    labelContent()
  } else {
    Box(modifier = Modifier.widthIn(min = minLabelWidth)) {
      labelContent()
    }
  }
}

/**
 * Client-side stopwatch for one thinking block.
 *
 * Hoisted above collapsible containers (a group body is disposed on
 * collapse) so a measured duration survives folding: the caller keeps one
 * clock per thinking block and hands it back on re-expansion. A clock that
 * was never started yields no duration, so history blocks keep the plain
 * "Thought" label.
 */
class ThinkingClock {
  var startedAtMs: Long = -1L
    private set
  var finishedAtMs: Long = -1L
    private set

  fun start() {
    if (startedAtMs < 0L) startedAtMs = System.currentTimeMillis()
  }

  fun finish() {
    if (startedAtMs < 0L) return
    if (finishedAtMs < 0L) finishedAtMs = System.currentTimeMillis()
  }
}

@Composable
private fun rememberThinkingElapsedMs(isComplete: Boolean, clock: ThinkingClock?): Long? {
  if (clock == null) return null
  var nowMs: Long by remember { mutableStateOf(value = System.currentTimeMillis()) }

  LaunchedEffect(key1 = isComplete) {
    if (isComplete) {
      clock.finish()
    } else {
      clock.start()
      while (true) {
        nowMs = System.currentTimeMillis()
        delay(100.milliseconds)
      }
    }
  }

  return when {
    clock.startedAtMs < 0L -> null
    clock.finishedAtMs >= 0L -> clock.finishedAtMs - clock.startedAtMs
    else -> (nowMs - clock.startedAtMs).coerceAtLeast(minimumValue = 0L)
  }
}

private const val PREVIEW_MAX_CHARS = 600
private val THINKING_MAX_HEIGHT = 300.dp
