@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.ChatErrorCode
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.chat.skill.humanizeToolName
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.ToolCallErrorInfo
import gradum.idea.chat.ui.chat.skill.internal.formatToolDetails
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRendererRegistry
import gradum.idea.chat.ui.chat.skill.spi.parseJsonResult
import gradum.idea.chat.ui.common.formatTokenCount
import gradum.idea.chat.ui.common.verticalEdgeFade
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.chat.ui.markdown.GradumMarkdown
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.settings.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val PHASE_FADE_IN_MS: Int = 300

/** Everything [AssistantChatBubble] reads besides the message itself. */
data class BubbleState(
  val sendingPhase: String = "",
  val isLoading: Boolean = false,
  val showActions: Boolean = true,
  val actionsEnabled: Boolean = true,
  val dismissedAskRequestIds: Set<String> = emptySet(),
  val selectedPermission: String = PermissionMode.READONLY
)

// Opens a file region in the IDE editor.
private typealias OpenInEditorAction = (filePath: String, startLine: Int, endLine: Int) -> Unit

// Shows a before/after diff for one file.
private typealias ShowDiffAction = (filePath: String, originalContent: String, modifiedContent: String) -> Unit

// Opens the sub-chat conversation behind one tool-call fold.
private typealias OpenSubChatAction = (messages: List<ChatMessage>, title: String) -> Unit

// Resolves an in-flight ask_interaction card.
private typealias RespondToAskAction = suspend (
  sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean
) -> Unit

/** User-intent callbacks of [AssistantChatBubble], grouped so call sites stay flat. */
data class BubbleActions(
  val onRetry: () -> Unit = {},
  val onUrlClick: (String) -> Unit = {},
  val onDismissAsk: (String) -> Unit = {},
  val onSubChatClick: OpenSubChatAction? = null,
  val onViewDiff: ShowDiffAction = { _, _, _ -> },
  val onOpenInEditor: OpenInEditorAction = { _, _, _ -> },
  val onRespondToAsk: RespondToAskAction = { _, _, _, _, _ -> }
)

@Composable
fun AssistantChatBubble(
  message: ChatMessage,
  modifier: Modifier = Modifier,
  state: BubbleState = BubbleState(),
  actions: BubbleActions = BubbleActions()
) {
  val (sendingPhase, isLoading, showActions, actionsEnabled, dismissedAskRequestIds, selectedPermission) = state
  val (onRetry, onUrlClick, onDismissAsk, onSubChatClick, onViewDiff, onOpenInEditor, onRespondToAsk) = actions
  val renderBlocks: List<RenderBlock> = message.renderBlocks.filter { block ->
    block !is RenderBlock.AskInteraction || block.requestId !in dismissedAskRequestIds
  }
  val hasContent = renderBlocks.isNotEmpty()
  val isDebugMode = selectedPermission == PermissionMode.DEBUG

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start
  ) {
    Column(horizontalAlignment = Alignment.Start) {
      var hasContentBefore = false

      if (LocalShowModelName.current && message.modelName.isNotBlank()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
        ) {
          Icon(
            contentDescription = null,
            key = GradumIcons.ColorLogo
          )
          Text(
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            text =
              if (isDebugMode) message.modelName
              else formatModelName(raw = message.modelName)
          )
        }
        hasContentBefore = true
      }

      buildBubbleItems(renderBlocks).forEach { item: BubbleItem ->
        if (hasContentBefore) {
          Spacer(modifier = Modifier.height(GradumSpacing.lg))
        }

        hasContentBefore = true

        when (item) {
          is BubbleItem.ToolGroup ->
            key("toolgroup_${item.calls.first().first}") {
              ToolCallGroupSection(item, onOpenInEditor, onViewDiff, onSubChatClick)
            }

          is BubbleItem.Single -> key(item.block.key(item.index)) {
            when (val block: RenderBlock = item.block) {
              is RenderBlock.Thinking -> {
                val hasNonThinkingAfter: Boolean = renderBlocks
                  .drop(n = item.index + 1)
                  .any { it !is RenderBlock.Thinking }
                ThinkingBlock(block, isLoading, onUrlClick, hasResponseAfter = hasNonThinkingAfter)
              }

              is RenderBlock.ToolCall -> ToolCallBlock(
                block, onOpenInEditor, onViewDiff, onSubChatClick
              )

              is RenderBlock.Error -> ErrorBlock(block)
              is RenderBlock.Response -> ResponseBlock(block, onUrlClick)
              is RenderBlock.AskInteraction -> AskCard(
                block,
                onRespondToAsk,
                onResponded = { onDismissAsk(block.requestId) }
              )
            }
          }
        }
      }

      val contextTokenCount: Int = message.tokenUsage?.contextTokens ?: 0
      val totalTokenCount: Int = message.tokenUsage?.totalTokens ?: 0
      val showTokenStatus: Boolean =
        if (isDebugMode) isLoading
        else isLoading || totalTokenCount > 0

      if (showTokenStatus) {
        if (hasContentBefore) Spacer(modifier = Modifier.height(GradumSpacing.lg))
        hasContentBefore = true
        TokenStatusRow(
          isLoading = isLoading,
          sendingPhase =
            if (isLoading) sendingPhase
            else sendingPhase.takeIf { it.isNotBlank() } ?: message("gradum.done"),
          totalTokenCount = totalTokenCount,
          contextTokenCount = contextTokenCount
        )
      }

      if (showActions && !isLoading) {
        if (hasContentBefore) Spacer(modifier = Modifier.height(GradumSpacing.lg))
        MessageActionsRow(
          onRetry = onRetry,
          message = message,
          isLoading = false,
          hasContent = hasContent,
          actionsEnabled = actionsEnabled,
          selectedPermission = selectedPermission
        )
      }
      Spacer(Modifier.height(GradumSpacing.xxl))
    }
  }
}

private fun RenderBlock.key(index: Int): String =
  when (this) {
    is RenderBlock.Thinking -> "thinking_$index"
    is RenderBlock.ToolCall -> "tool_${index}_$alias"
    is RenderBlock.Response -> "response_$index"
    is RenderBlock.Error -> "error_$index"
    is RenderBlock.AskInteraction -> "ask_${index}_${requestId}"
  }

private sealed interface BubbleItem {
  data class Single(val block: RenderBlock, val index: Int) : BubbleItem
  data class ToolGroup(val calls: List<Pair<Int, RenderBlock.ToolCall>>) : BubbleItem
}

// Groups consecutive tool-call blocks into one [BubbleItem.ToolGroup] so the
// UI can fold the exploration phase into a single collapsible section while
// text, thinking, and answers stay in the main flow. Hidden pending calls
// (renderers that opt out of the pending state) are skipped exactly as in
// the flat path.
private fun buildBubbleItems(renderBlocks: List<RenderBlock>): List<BubbleItem> {
  val items: MutableList<BubbleItem> = mutableListOf()
  var collectedCalls: MutableList<Pair<Int, RenderBlock.ToolCall>> = mutableListOf()

  fun flushCalls() {
    if (collectedCalls.isNotEmpty()) {
      items.add(BubbleItem.ToolGroup(calls = collectedCalls.toList()))
      collectedCalls = mutableListOf()
    }
  }

  renderBlocks.forEachIndexed { index: Int, block: RenderBlock ->
    if (block is RenderBlock.ToolCall) {
      val hiddenWhilePending: Boolean = block.pending &&
        !(ToolCallRendererRegistry.find(aliasName = block.alias)?.rendersWhilePending() ?: false)
      if (!hiddenWhilePending) {
        collectedCalls.add(index to block)
      }
      return@forEachIndexed
    }
    flushCalls()
    items.add(BubbleItem.Single(block = block, index = index))
  }
  flushCalls()

  return items
}

// A collapsible "process" section holding a run of tool calls. The header
// shows the call count; the body expands with a short animation and lists
// the individual call blocks. A group that is still running starts
// expanded, a finished group starts collapsed.
@Composable
private fun ToolCallGroupSection(
  group: BubbleItem.ToolGroup,
  onOpenInEditor: (path: String, startLine: Int, endLine: Int) -> Unit,
  onViewDiff: (path: String, originalContent: String, modifiedContent: String) -> Unit,
  onSubChatClick: OpenSubChatAction?
) {
  val groupIsRunning: Boolean = group.calls.any { (_, toolCall) -> toolCall.pending }
  var isExpanded: Boolean by remember { mutableStateOf(groupIsRunning) }

  Column(verticalArrangement = Arrangement.spacedBy(GradumSpacing.lg)) {
    Row(
      modifier = Modifier
        .clickable { isExpanded = !isExpanded },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
    ) {
      Icon(
        key =
          if (isExpanded) AllIconsKeys.General.ChevronDown
          else AllIconsKeys.General.ChevronRight,
        contentDescription = null
      )
      Text(
        text = message(key = "gradum.tool.group", group.calls.size),
        style = rememberGradumParagraphTextStyle()
      )
    }

    AnimatedVisibility(visible = isExpanded) {
      Column(
        modifier = Modifier.padding(start = GradumSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
      ) {
        for ((index, toolCall) in group.calls) {
          key(toolCall.key(index)) {
            ToolCallBlock(toolCall, onOpenInEditor, onViewDiff, onSubChatClick)
          }
        }
      }
    }
  }
}

@Composable
private fun ThinkingBlock(
  block: RenderBlock.Thinking, isLoading: Boolean,
  onUrlClick: (String) -> Unit, hasResponseAfter: Boolean = false
) {
  ThinkingIndicator(
    onUrlClick = onUrlClick,
    thinking = block.content,
    isTaskComplete = !isLoading,
    hasResponseAfter = hasResponseAfter,
    startCollapsed = LocalCollapseThinkingByDefault.current
  )
}

@Composable
private fun ResponseBlock(
  block: RenderBlock.Response, onUrlClick: (String) -> Unit
) {
  GradumMarkdown(
    text = block.content,
    modifier = Modifier
  ) {
    this.onUrlClick = onUrlClick
    animationEnabled = false
    withSelection = true
  }
}

/**
 * Renders the parse-failure placeholder for a MarkdownSegment.Table
 * that the caller has determined to be unrenderable
 * (MarkdownSegment.Table.isRenderable is `false`). The chat bubble
 * substitutes this for any `Table` whose body is empty / blank: the
 * raw pipe syntax of the original Markdown block is not surfaced
 * here, since it's visually noisy and uninformative.
 */
@Composable
fun ToolCallBlock(
  block: RenderBlock.ToolCall,
  onOpenInEditor: (path: String, startLine: Int, endLine: Int) -> Unit,
  onViewDiff: (path: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> },
  onSubChatClick: OpenSubChatAction? = null
) {
  if (block.pending && !(ToolCallRendererRegistry.find(aliasName = block.alias)?.rendersWhilePending() ?: false))
    return

  val clipboardScope: CoroutineScope = rememberCoroutineScope()
  val renderer = ToolCallRendererRegistry.find(aliasName = block.alias)
  if (renderer == null) {
    val fallbackToolDetails: String = if (!block.success) {
      formatToolDetails(
        alias = block.alias,
        result = block.result,
        errorDetail = block.errorDetail,
        errorMessage = block.errorMessage,
        arguments = block.arguments
      )
    } else ""
    ToolCallCapsule(
      label = humanizeToolName(block.alias),
      iconKey = AllIconsKeys.Nodes.Plugin,
      success = block.success,
      errorInfo = ToolCallErrorInfo(
        detail = block.errorDetail,
        message = block.errorMessage,
        toolDetails = fallbackToolDetails
      )
    )
    return
  }
  val delegateArgs: Map<String, Any> =
    if (block.alias == "Delegate") {
      block.arguments + mapOf(
        "pending" to block.pending,
        "timeoutSeconds" to block.timeoutSeconds
      )
    } else block.arguments
  val content: ToolCallContent =
    renderer.parseContent(
      arguments = delegateArgs,
      result = parseJsonResult(serializedResult = block.result)
    )

  val displayContent: ToolCallContent =
    if (renderer.alias() == ToolCallRendererRegistry.DEFAULT_ALIAS) content.copy(aliasName = block.alias)
    else content
  val toolDetails: String? =
    if (!block.success) {
      formatToolDetails(
        alias = block.alias,
        result = block.result,
        errorDetail = block.errorDetail,
        errorMessage = block.errorMessage,
        arguments = block.arguments
      )
    } else null
  val ctx =
    ToolCallRenderContext(
      project = null,
      isError = !block.success,
      toolDetails = toolDetails,
      errorDetail = block.errorDetail,
      onCopy = { payload: String ->
        copyToClipboard(
          onReset = {},
          onCopied = {},
          text = payload,
          scope = clipboardScope
        )
      },
      onOpenInEditor = onOpenInEditor,
      onViewDiff = { filePath: String, originalContent: String?, modifiedContent: String? ->
        onViewDiff(
          filePath,
          originalContent.orEmpty(),
          modifiedContent.orEmpty()
        )
      },
      onSubChatClick = onSubChatClick,
    )
  Box(modifier = Modifier.horizontalScroll(state = rememberScrollState())) {
    renderer.render(displayContent, ctx)
  }
}

@Composable
private fun TokenStatusRow(
  isLoading: Boolean,
  sendingPhase: String,
  totalTokenCount: Int = 0,
  contextTokenCount: Int = 0
) {
  val tokenText: String =
    when {
      contextTokenCount > 0 ->
        "$sendingPhase & ${message("gradum.tokens.used", formatTokenCount(contextTokenCount))}"

      totalTokenCount > 0 ->
        "$sendingPhase & ${message("gradum.tokens.used", formatTokenCount(totalTokenCount))}"

      else -> sendingPhase.ifEmpty { "..." }
    }

  val fadeAlpha = remember { Animatable(initialValue = 1f) }
  var displayText: String by remember { mutableStateOf(value = sendingPhase) }
  var previousText: String by remember { mutableStateOf(value = sendingPhase) }

  LaunchedEffect(key1 = sendingPhase, key2 = tokenText) {
    val newText: String = tokenText.ifEmpty { sendingPhase }
    if (newText != previousText) {
      displayText = newText
      previousText = newText
      fadeAlpha.snapTo(targetValue = 0f)
      fadeAlpha.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = PHASE_FADE_IN_MS)
      )
    }
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
  ) {
    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))

    val tokenTextContent: @Composable () -> Unit = {
      SweepLightText(
        text = displayText,
        enabled = isLoading,
        modifier = Modifier.graphicsLayer {
          this.alpha = fadeAlpha.value
        }
      )
    }
    if (contextTokenCount > 0 && totalTokenCount > 0) {
      Tooltip(
        tooltip = {
          Text(
            text = message(
              "gradum.tokens.total.tooltip",
              formatTokenCount(totalTokenCount)
            )
          )
        }
      ) { tokenTextContent() }
    } else {
      tokenTextContent()
    }
  }
}

@Composable
private fun MessageActionsRow(
  onRetry: () -> Unit,
  isLoading: Boolean,
  hasContent: Boolean,
  message: ChatMessage,
  actionsEnabled: Boolean,
  selectedPermission: String = PermissionMode.READONLY
) {
  val isDebug: Boolean = selectedPermission == PermissionMode.DEBUG
  var isCopied: Boolean by remember { mutableStateOf(value = false) }
  var isSelectedLike: Boolean by remember { mutableStateOf(value = false) }
  var isSelectedDislike: Boolean by remember { mutableStateOf(value = false) }

  Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
    if (LocalShowCopyAction.current) {
      MessageCopyButton(
        message = message,
        isCopied = isCopied,
        onCopy = { isCopied = true },
        onReset = { isCopied = false }
      )
    }
    if (LocalShowRetryAction.current) {
      Tooltip(tooltip = { Text(text = message("gradum.reset.tooltip")) }) {
        IconButton(
          onClick = onRetry,
          enabled = actionsEnabled && !isLoading && hasContent
        ) {
          Icon(
            contentDescription = message("gradum.reset"),
            key = AllIconsKeys.Actions.Refresh
          )
        }
      }
    }
    if (!isDebug && LocalShowLikeDislikeAction.current) {
      IconButton(
        enabled = hasContent,
        onClick = {
          isSelectedLike = !isSelectedLike
          if (isSelectedLike) isSelectedDislike = false
        }
      ) {
        Icon(
          contentDescription = message("gradum.like"),
          key =
            if (isSelectedLike) GradumIcons.LikeSelected
            else GradumIcons.Like
        )
      }
      IconButton(
        enabled = hasContent,
        onClick = {
          isSelectedDislike = !isSelectedDislike
          if (isSelectedDislike) isSelectedLike = false
        }
      ) {
        Icon(
          contentDescription = message("gradum.dislike"),
          key =
            if (isSelectedDislike) GradumIcons.DislikeSelected
            else GradumIcons.Dislike
        )
      }
    }
  }
}

@Composable
private fun ErrorBlock(block: RenderBlock.Error) {
  val isInterrupted: Boolean = block.code == ChatErrorCode.INTERRUPTED
  val textErrorColor: Color = JewelTheme.globalColors.text.error

  if (isInterrupted) return

  val hasDetail: Boolean = block.detail.isNotBlank()
  var isExpanded: Boolean by remember { mutableStateOf(value = false) }

  val titleRow: @Composable () -> Unit = {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
      modifier = Modifier
        .fillMaxWidth()
        .then(if (hasDetail) Modifier.clickable { isExpanded = !isExpanded } else Modifier)
    ) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.Status.FailedInProgress
      )
      Text(
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = textErrorColor,
        text =
          if (block.code.isBlank()) block.message.ifBlank { friendlyErrorMessage(block.code) }
          else friendlyErrorMessage(block.code),
        style = JewelTheme.typography.editorTextStyle.copy(
          color = textErrorColor
        ),
        modifier = Modifier.weight(weight = 1f, fill = false)
      )
      if (hasDetail) {
        Icon(
          contentDescription = null,
          key =
            if (isExpanded) AllIconsKeys.General.ChevronDown
            else AllIconsKeys.General.ChevronRight
        )
      }
    }
  }

  Column(
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.md),
    modifier = Modifier.fillMaxWidth()
  ) {
    if (hasDetail) {
      Tooltip(tooltip = { Text(text = message("gradum.error.show.raw")) }) { titleRow() }
    } else {
      titleRow()
    }

    AnimatedVisibility(visible = isExpanded && hasDetail) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = ERROR_DETAIL_MAX_HEIGHT)
          .verticalEdgeFade()
      ) {
        val scrollState = rememberScrollState()
        SelectionContainer {
          Text(
            text = block.detail,
            color = textErrorColor,
            style = JewelTheme.typography.editorTextStyle.copy(
              color = textErrorColor
            ),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = GradumSpacing.md)
              .verticalScroll(state = scrollState)
          )
        }
      }
    }
  }
}

// Max height of the expanded error-detail block before it scrolls.
private val ERROR_DETAIL_MAX_HEIGHT = 200.dp
