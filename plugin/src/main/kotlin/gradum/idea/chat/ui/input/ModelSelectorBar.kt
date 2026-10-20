package gradum.idea.chat.ui.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import gradum.idea.PluginConfig
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.chat.ui.common.IconTooltipButton
import gradum.idea.chat.ui.common.SelectorButton
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.LocalBadgeStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Cap on the model selector popup height. The local Ollama host on a
 * developer machine can easily surface 30+ entries (every pulled tag
 * counts as a row); without a cap the popup pushes the entire chat
 * area off-screen. 360dp comfortably fits ~6 rows and keeps the
 * selected row visible while scrolling.
 *
 * **Why no `verticalScroll` here.** Jewel `PopupMenu` already wraps
 * its `MenuContent` in an internal `verticalScroll`-backed Column
 * (visible in the disassembled `MenuKt.MenuContent` at
 * `ScrollKt.verticalScroll$default`). Stacking a second one on the
 * outside `Modifier` makes the inner modifier receive
 * `Constraints.Infinity` and throws
 * `IllegalStateException: Vertically scrollable component was
 * measured with an infinity maximum height constraints` the moment
 * the menu opens. The internal scroll container respects the
 * `heightIn(max=...)` cap, which is all we need.
 */
private val MODEL_MENU_MAX_HEIGHT = 300.dp

@Composable
fun ModelSelectorBar(
  modifier: Modifier = Modifier,
  selectedModel: ModelInfo? = null,
  models: List<ModelInfo> = emptyList(),
  pinnedModels: List<ModelInfo> = emptyList(),
  thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
  onRefreshModels: () -> Unit = {},
  onTogglePin: (ModelInfo) -> Unit = {},
  onSelectModel: (ModelInfo?) -> Unit = {},
  onSelectThinkingLevel: (ThinkingLevel) -> Unit = {}
) {
  var showModelMenu by remember { mutableStateOf(false) }
  val dismiss: () -> Unit = { showModelMenu = false }

  val supportsThinking: Boolean = selectedModel?.reasoning == true
  val supportsThinkingLevels: Boolean = selectedModel?.reasoningLevels == true
  // A model that cannot think at all gets no thinking control; one that thinks
  // on a binary switch keeps the selector but grayed, with a tooltip saying so.
  // While no model is selected yet (pre-load) the selector stays visible but
  // disabled, so the toolbar does not jump once the roster arrives.
  val showThinkingSelector: Boolean = selectedModel == null || supportsThinking
  val thinkingDisabledTooltip: String? =
    if (supportsThinking && !supportsThinkingLevels) message("gradum.thinking.tooltip.unsupported") else null

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box {
      SelectorButton(
        onClick = {
          onRefreshModels()
          showModelMenu = true
        },
        isButtonEnabled = models.isNotEmpty(),
        contentDescription = message("gradum.model.select"),
        text = resolveSelectorText(selectedModel)
      )
      if (showModelMenu) {
        PopupMenu(
          horizontalAlignment = Alignment.Start,
          onDismissRequest = { dismiss(); true },
          modifier = Modifier.heightIn(max = MODEL_MENU_MAX_HEIGHT)
        ) {
          buildMenu(
            models = models,
            pinnedModels = pinnedModels,
            selectedModel = selectedModel,
            onTogglePin = { onTogglePin(it); dismiss() },
            onSelectModel = { onSelectModel(it); dismiss() }
          )
        }
      }
    }
    if (showThinkingSelector) {
      Spacer(modifier = Modifier.width(GradumSpacing.xs))
      ThinkingLevelSelector(
        enabled = supportsThinkingLevels,
        selectedLevel = thinkingLevel,
        disabledTooltip = thinkingDisabledTooltip,
        onSelect = onSelectThinkingLevel,
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    ExternalLink(
      text = message("gradum.feedback"),
      onClick = { BrowserUtil.browse("https://github.com/lg841226/Gradum") }
    )
  }
}


private fun MenuScope.buildMenu(
  models: List<ModelInfo>,
  pinnedModels: List<ModelInfo>,
  selectedModel: ModelInfo?,
  onTogglePin: (ModelInfo) -> Unit,
  onSelectModel: (ModelInfo) -> Unit
) {
  passiveItem {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = GradumSpacing.sm),
      horizontalArrangement = Arrangement.Center
    ) {
      Text(
        text = message("gradum.model"),
        fontWeight = FontWeight.SemiBold
      )
    }
  }

  val unpinnedModels = models.filter { model: ModelInfo ->
    pinnedModels.none { it.sameAs(other = model) }
  }

  if (pinnedModels.isNotEmpty()) {
    separator()
    passiveItem {
      Text(
        text = message("gradum.model.pinned"),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            vertical = GradumSpacing.sm,
            horizontal = GradumSpacing.md
          )
      )
    }
    pinnedModels.forEach { pinned: ModelInfo ->
      selectableItem(
        selected = false,
        onClick = { onSelectModel(pinned) }
      ) {
        ModelItemRow(
          model = pinned,
          isPinned = true,
          onTogglePin = { onTogglePin(pinned) }
        )
      }
    }
  }

  if (unpinnedModels.isNotEmpty()) {
    if (pinnedModels.isNotEmpty()) separator()
    unpinnedModels.forEach { model: ModelInfo ->
      selectableItem(
        selected = selectedModel?.name == model.name
          && selectedModel.serverName == model.serverName,
        onClick = { onSelectModel(model) }
      ) {
        ModelItemRow(
          model = model,
          isPinned = false,
          onTogglePin = { onTogglePin(model) }
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelItemRow(model: ModelInfo, isPinned: Boolean, onTogglePin: () -> Unit) {
  val iconKey: IconKey? = resolveProviderIcon(model)
  val formatted: FormattedModelName = parseModelName(raw = model.name)
  val pinTip: String = if (isPinned) message("gradum.model.unpin") else message("gradum.model.pin")
  val pinIcon: IconKey = if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = GradumSpacing.xs),
    verticalAlignment = Alignment.Top
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        iconKey?.let {
          Icon(
            key = it,
            contentDescription = null,
            modifier = Modifier.size(GradumSpacing.lrl)
          )
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
        }
        Tooltip(tooltip = { Text(text = formatted.displayName) }) {
          Text(
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            text = clipModelName(formatted.displayName),
            color = JewelTheme.globalColors.text.normal,
            modifier = Modifier.weight(1f, fill = false)
          )
        }
        if (!model.available) {
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
          Tooltip(tooltip = { Text(text = message("gradum.model.unavailable")) }) {
            Icon(
              key = GradumIcons.Warning,
              contentDescription = message("gradum.model.unavailable")
            )
          }
        }
        val sizeBadge: String? =
          model.parameterSizeB?.let(::formatParameterSize) ?: formatted.parameterSize
        if (sizeBadge != null) {
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
          Badge(
            content = { Text(sizeBadge) },
            style = LocalBadgeStyle.current.graySecondary
          )
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (isCloudModel(model)) {
          Icon(
            key = GradumIcons.Cloud,
            contentDescription = message("gradum.cloud")
          )
          Spacer(modifier = Modifier.width(GradumSpacing.sm))
        }
        if (model.serverName.isNotBlank())
          Text(
            text = model.serverName,
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.info
          )
      }
    }
    IconTooltipButton(
      tooltip = pinTip,
      iconKey = pinIcon,
      onClick = onTogglePin,
      contentDescription = pinTip
    )
  }
}

/**
 * Renders the official parameter count as a badge label, trimming the
 * trailing `.0` (8.0 -> "8B", 7.6 -> "7.6B").
 */
private fun formatParameterSize(sizeB: Double): String =
  if (sizeB % 1.0 == 0.0) "${sizeB.toInt()}B" else "${sizeB}B"

private fun resolveSelectorText(selectedModel: ModelInfo?): String = when {
  selectedModel != null -> clipModelName(formatModelName(selectedModel.name))
  else -> message("gradum.model.none")
}

/** Caps a model display name at [MAX_MODEL_NAME_CHARS] characters for narrow selectors. */
private const val MAX_MODEL_NAME_CHARS: Int = PluginConfig.MAX_MODEL_NAME_CHARS

private fun clipModelName(name: String): String {
  val trimmed: String = name.trim()
  if (trimmed.length <= MAX_MODEL_NAME_CHARS) return trimmed
  return trimmed.take(n = MAX_MODEL_NAME_CHARS - 1) + "…"
}

private fun resolveProviderIcon(model: ModelInfo): IconKey? {
  val providerName: String = model.provider.lowercase().trim()
  if (providerName.isNotBlank()) {
    GradumIcons.resolveModelIcon(modelName = model.name)?.let { return it }
    return AllIconsKeys.Stub
  }
  return GradumIcons.resolveModelIcon(modelName = model.name)
}

private val CLOUD_SERVER_NAMES = setOf(
  "Zhipu BigModel", "DeepSeek", "MiniMax"
)

private fun isCloudHosted(model: ModelInfo): Boolean =
  model.serverName in CLOUD_SERVER_NAMES

/**
 * Cloud detection for the badge row. Primary signal is the official
 * `remote_host` the server read from Ollama `/api/tags`; the naming
 * convention (`:cloud` tag or `-cloud` suffix) only covers servers old
 * enough to predate that field. The core's ModelIdentity is not visible
 * from the plugin module, so this fallback is duplicated here on purpose.
 */
private fun isCloudModel(model: ModelInfo): Boolean {
  if (!model.remoteHost.isNullOrBlank()) return true
  if (isCloudHosted(model)) return true

  val lowerName: String = model.name.lowercase()
  if (lowerName.endsWith("-cloud")) return true
  return lowerName.substringAfter(':') == "cloud"
}
