/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-09-26 00:12:53 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ErrorCode
import gradum.idea.chat.model.RenderBlock
import gradum.idea.chat.ui.chat.skill.humanizeToolName
import gradum.idea.chat.ui.chat.skill.internal.ToolCallCapsule
import gradum.idea.chat.ui.chat.skill.internal.ToolCallErrorInfo
import gradum.idea.chat.ui.chat.skill.internal.formatToolDetails
import gradum.idea.chat.ui.chat.skill.spi.ToolCallContent
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRenderContext
import gradum.idea.chat.ui.chat.skill.spi.ToolCallRendererRegistry
import gradum.idea.chat.ui.chat.skill.spi.parseJsonResult
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.chat.ui.input.formatModelName
import gradum.idea.chat.ui.markdown.GradumMarkdown
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

/**
 * Left-aligned assistant message bubble.
 *
 * Renders events in order to preserve the conversation flow:
 * thinking, tool calls, responses, and errors appear in the sequence they occurred.
 */
@Composable
fun AssistantChatBubble(
  message: ChatMessage,
  sendingPhase: String = "",
  onRetry: () -> Unit = {},
  isLoading: Boolean = false,
  showActions: Boolean = true,
  modifier: Modifier = Modifier,
  actionsEnabled: Boolean = true,
  onUrlClick: (String) -> Unit = {},
  selectedPermission: String = PermissionMode.READONLY,
  onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { _: String, _: Int, _: Int -> },
  onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) -> Unit = { _: String, _: String, _: String -> },
  onSubChatClick: ((conversationJson: String, toolCallsJson: String, title: String) -> Unit)? = null,
  onRespondToAsk: suspend (
    sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean
  ) -> Unit = { _: String, _: String, _: String?, _: String?, _: Boolean -> },
  dismissedAskRequestIds: Set<String> = emptySet(),
  onDismissAsk: (String) -> Unit = {}
) {
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

      renderBlocks.forEachIndexed { index: Int, block: RenderBlock ->
        if (block is RenderBlock.ToolCall && block.pending &&
          !(ToolCallRendererRegistry.find(aliasName = block.alias)?.rendersWhilePending() ?: false)
        ) return@forEachIndexed
        if (hasContentBefore) {
          Spacer(modifier = Modifier.height(GradumSpacing.lg))
        }

        hasContentBefore = true

        key(block.key(index)) {
          when (block) {
            is RenderBlock.Thinking -> {
              val hasNonThinkingAfter: Boolean = renderBlocks
                .drop(n = index + 1)
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

      val tokenCount: Int = message.tokenUsage?.totalTokens ?: 0
      val showTokenStatus: Boolean =
        if (isDebugMode) isLoading
        else isLoading || tokenCount > 0

      if (showTokenStatus) {
        if (hasContentBefore) Spacer(modifier = Modifier.height(GradumSpacing.lg))
        hasContentBefore = true
        TokenStatusRow(
          isLoading = isLoading,
          tokenCount = tokenCount,
          sendingPhase =
            if (isLoading) sendingPhase
            else sendingPhase.takeIf { it.isNotBlank() } ?: message("gradum.done")
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

private fun RenderBlock.key(index: Int): String = when (this) {
  is RenderBlock.Thinking -> "thinking_$index"
  is RenderBlock.ToolCall -> "tool_${index}_$alias"
  is RenderBlock.Response -> "response_$index"
  is RenderBlock.Error -> "error_$index"
  is RenderBlock.AskInteraction -> "ask_${index}_${requestId}"
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
    startCollapsed = LocalCollapseThinkingByDefault.current,
    hasResponseAfter = hasResponseAfter,
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
 * substitutes this for any `Table` whose body is empty / blank — the
 * raw pipe syntax of the original Markdown block is not surfaced
 * here, since it's visually noisy and uninformative.
 */
@Composable
fun ToolCallBlock(
  block: RenderBlock.ToolCall,
  onOpenInEditor: (path: String, startLine: Int, endLine: Int) -> Unit,
  onViewDiff: (path: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> },
  onSubChatClick: ((conversationJson: String, toolCallsJson: String, title: String) -> Unit)? = null
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
  // The catch-all renderer can't see `block.alias` (parseContent only gets
  // arguments + result), so surface the real tool name through the content.
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
  isLoading: Boolean, sendingPhase: String, tokenCount: Int = 0
) {
  val tokenText: String =
    if (tokenCount > 0)
      "$sendingPhase & ${message("gradum.tokens.used", formatTokenCount(tokenCount))}"
    else
      sendingPhase.ifEmpty { "..." }

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

    SweepLightText(
      text = displayText,
      enabled = isLoading,
      modifier = Modifier.graphicsLayer {
        this.alpha = fadeAlpha.value
      }
    )
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
  val isInterrupted: Boolean = block.code == ErrorCode.INTERRUPTED.code
  val textErrorColor: Color = JewelTheme.globalColors.text.error

  if (isInterrupted) return

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(state = rememberScrollState())
  ) {
    Icon(
      contentDescription = null,
      key = AllIconsKeys.Status.FailedInProgress
    )
    Text(
      maxLines = 1,
      color = textErrorColor,
      text = block.message.ifBlank { friendlyErrorMessage(block.code) },
      style = JewelTheme.typography.editorTextStyle.copy(
        color = textErrorColor
      )
    )
  }
}


private fun formatTokenCount(count: Int): String {
  return when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
  }
}
