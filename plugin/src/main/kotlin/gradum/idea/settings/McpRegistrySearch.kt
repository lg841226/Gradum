@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.chat.ui.common.verticalEdgeFade
import gradum.idea.mcp.McpRegistryClient
import gradum.idea.mcp.McpRegistryResult
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

private const val MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP: Int = 320
private const val MCP_REGISTRY_SCROLLBAR_RESERVE_DP: Int = 18

// Search box for the official online MCP registry: a width capped field
// centered in the section, with the search action living inside it as a
// trailing icon (a spinner while a request is in flight). Keystrokes are
// debounced by [MCP_INPUT_DEBOUNCE_MS], and tapping the icon re-runs the
// current query manually; either way the blocking HTTP call runs on
// [Dispatchers.IO] and surfaces as a transient error line, an
// empty result line, or a scrollable result panel capped at
// [MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP]; each cut-off edge fades only while
// content is still hidden beyond it and clears when that edge is reached.
// Three dropdowns to the field's left narrow the hits by
// importability, package ecosystem, and publish window before they reach
// the panel. Choosing a result hands it to [onAdd],
// which writes a new entry into settings.json. Remote only servers render
// with their import disabled.
@Composable
internal fun McpRegistrySearch(onAdd: (McpRegistryResult) -> Unit) {
  val searchState: TextFieldState = remember { TextFieldState() }
  val query: String = searchState.text.toString().trim()
  var searchTick: Int by remember { mutableStateOf(value = 0) }
  var failed: Boolean by remember { mutableStateOf(value = false) }
  var ecosystem: String? by remember { mutableStateOf(value = null) }
  var searching: Boolean by remember { mutableStateOf(value = false) }
  var importableOnly: Boolean by remember { mutableStateOf(value = false) }
  var publishedWithin: String? by remember { mutableStateOf(value = null) }
  var results: List<McpRegistryResult>? by remember { mutableStateOf(value = null) }

  LaunchedEffect(query, searchTick) {
    if (query.isEmpty()) {
      results = null
      failed = false
      searching = false
      return@LaunchedEffect
    }
    delay(duration = MCP_INPUT_DEBOUNCE_MS.milliseconds)
    searching = true
    results = null
    failed = false
    val fetched: List<McpRegistryResult>? =
      withContext(context = Dispatchers.IO) {
        runCatching { McpRegistryClient.search(query = query) }.getOrNull()
      }
    results = fetched ?: emptyList()
    failed = fetched == null
    searching = false
  }

  Column {
    val anyLabel: String = message("gradum.settings.mcp.registry.filter.any")
    Box(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(space = GradumSpacing.sml),
        verticalAlignment = Alignment.CenterVertically
      ) {
        McpRegistryFilterDropdown(
          label = message("gradum.settings.mcp.registry.filter.importable"),
          selected = importableOnly,
          anyValue = false,
          options = listOf(
            false to anyLabel,
            true to message("gradum.settings.mcp.registry.filter.only")
          ),
          onSelect = { importableOnly = it }
        )
        McpRegistryFilterDropdown(
          label = message("gradum.settings.mcp.registry.filter.ecosystem"),
          selected = ecosystem,
          anyValue = null,
          options = listOf(
            null to anyLabel,
            "npm" to "npm",
            "pypi" to "pypi",
            "oci" to "oci",
            "nuget" to "nuget"
          ),
          onSelect = { ecosystem = it }
        )
        McpRegistryFilterDropdown(
          label = message("gradum.settings.mcp.registry.filter.published"),
          selected = publishedWithin,
          anyValue = null,
          options =
            listOf(
              null to anyLabel,
              "30d" to message("gradum.settings.mcp.registry.filter.last30d"),
              "1y" to message("gradum.settings.mcp.registry.filter.last1y")
            ),
          onSelect = { publishedWithin = it }
        )
        TextField(
          state = searchState,
          outline = Outline.None,
          textStyle = JewelTheme.editorTextStyle,
          modifier = Modifier.width(MCP_FIELD_WIDTH_DP.dp),
          placeholder = { Text(text = message("gradum.settings.mcp.registry.search")) },
          trailingIcon = {
            if (searching) {
              CircularProgressIndicator(modifier = Modifier.size(size = 16.dp))
            } else {
              Tooltip(
                tooltip = { Text(text = message("gradum.settings.mcp.registry.search.button")) }
              ) {
                Icon(
                  key = GradumIcons.Search,
                  contentDescription = message("gradum.settings.mcp.registry.search.button"),
                  modifier = Modifier.clickable { searchTick++ }
                )
              }
            }
          }
        )
      }
    }
    Spacer(Modifier.height(GradumSpacing.sml))

    val list: List<McpRegistryResult>? = results
    when {
      query.isEmpty() || list == null -> Unit
      failed -> Text(
        color = JewelTheme.globalColors.text.info,
        text = message("gradum.settings.mcp.registry.error")
      )

      else -> {
        val cutoff: String? = publishedCutoff(within = publishedWithin)
        val filtered: List<McpRegistryResult> = list.filter { result ->
          result.matchesFilters(
            cutoff = cutoff,
            ecosystem = ecosystem,
            importableOnly = importableOnly
          )
        }
        if (filtered.isEmpty()) {
          Text(
            color = JewelTheme.globalColors.text.info,
            text = message("gradum.settings.mcp.registry.empty")
          )
        } else {
          McpRegistryResults(list = filtered, onAdd = onAdd)
        }
      }
    }
  }
}

// Scrollable results panel for [McpRegistrySearch]: capped at
// [MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP], with a directional edge fade on
// whichever cut still hides content and a scrollbar on the right.
@Composable
private fun McpRegistryResults(
  list: List<McpRegistryResult>,
  onAdd: (McpRegistryResult) -> Unit
) {
  val scrollState = rememberScrollState()
  val scrollable: Boolean =
    scrollState.canScrollForward || scrollState.canScrollBackward
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = MCP_REGISTRY_RESULTS_MAX_HEIGHT_DP.dp)
      .then(
        if (scrollable)
          Modifier.verticalEdgeFade(
            fadeTop = scrollState.canScrollBackward,
            fadeBottom = scrollState.canScrollForward
          )
        else Modifier
      )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(scrollState)
        .padding(
          top = GradumSpacing.sm,
          start = GradumSpacing.sm,
          bottom = GradumSpacing.sm,
          end =
            if (scrollable) MCP_REGISTRY_SCROLLBAR_RESERVE_DP.dp
            else GradumSpacing.sm
        ),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
    ) {
      list.forEach { result ->
        McpRegistryResultRow(result = result, onAdd = onAdd)
      }
    }
    if (scrollable) {
      VerticalScrollbar(
        scrollState = scrollState,
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .fillMaxHeight()
      )
    }
  }
}

// Compact filter dropdown for the registry search: a [SelectorButton]
// whose face shows the filter name while [anyValue] is selected and the
// chosen option afterward (the tooltip always names the filter),
// opening a [PopupMenu] with a header row and one selectable row per
// option (the selected row stays bold).
@Composable
private fun <T> McpRegistryFilterDropdown(
  selected: T,
  anyValue: T,
  label: String,
  onSelect: (T) -> Unit,
  options: List<Pair<T, String>>
) {
  var showMenu by remember { mutableStateOf(value = false) }
  val dismiss: () -> Unit = { showMenu = false }

  Box {
    SelectorButton(
      text =
        if (selected == anyValue) label
        else options.first { option -> option.first == selected }.second,
      onClick = { showMenu = true },
      contentDescription = label,
      color =
        if (selected == anyValue) JewelTheme.globalColors.text.info
        else JewelTheme.globalColors.text.normal
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
              .padding(
                vertical = GradumSpacing.sm,
                horizontal = GradumSpacing.md
              )
          ) {
            Text(fontWeight = FontWeight.SemiBold, text = label)
          }
        }
        options.forEach { (value, text) ->
          selectableItem(
            selected = value == selected,
            onClick = { onSelect(value); dismiss() }
          ) {
            Text(
              fontWeight =
                if (value == selected) FontWeight.SemiBold
                else FontWeight.Normal,
              text = text,
              modifier = Modifier
                .fillMaxWidth()
                .padding(
                  vertical = GradumSpacing.xs,
                  horizontal = GradumSpacing.md
                )
            )
          }
        }
      }
    }
  }
}

// Publish-time filter cutoff as a `yyyy-MM` month string, or null when
// [within] picks no window. Month granularity matches the registry's
// publishedAt field, so the comparison stays a plain string order.
private fun publishedCutoff(within: String?): String? {
  val days: Long =
    when (within) {
      "30d" -> 30L
      "1y" -> 365L
      else -> return null
    }
  return LocalDate.now()
    .minusDays(days)
    .format(DateTimeFormatter.ofPattern("yyyy-MM"))
}

private fun McpRegistryResult.matchesFilters(
  cutoff: String?, ecosystem: String?, importableOnly: Boolean
): Boolean =
  when {
    importableOnly && command == null -> false
    ecosystem != null && !registryType.equals(ecosystem, ignoreCase = true) -> false
    cutoff != null && (publishedAt == null || publishedAt < cutoff) -> false
    else -> true
  }
