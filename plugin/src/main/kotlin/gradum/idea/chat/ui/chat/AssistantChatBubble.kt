/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-08-23 21:12:19 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ErrorCode
import gradum.idea.chat.model.RenderBlock
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
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private val RISE_DISTANCE_DP: Dp = 24.dp
private const val FADE_IN_MS: Int = 600
private const val PHASE_FADE_MS: Int = 100
private const val PHASE_FADE_IN_MS: Int = 300
private const val RISE_DURATION_MS: Int = 300

/**
 * Left-aligned assistant message bubble.
 *
 * Renders events in order to preserve the conversation flow:
 * thinking, tool calls, responses, and errors appear in the sequence they occurred.
 */
@Composable
fun AssistantChatBubble(
  message: ChatMessage,
  modifier: Modifier = Modifier,
  sendingPhase: String = "",
  onRetry: () -> Unit = {},
  onUrlClick: (String) -> Unit = {},
  isLoading: Boolean = false,
  showActions: Boolean = true,
  actionsEnabled: Boolean = true,
  selectedPermission: String = PermissionMode.READONLY,
  onOpenInEditor: (filePath: String, startLine: Int, endLine: Int) -> Unit = { _, _, _ -> },
  onViewDiff: (filePath: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> },
  onSubChatClick: ((conversationJson: String, toolCallsJson: String, title: String) -> Unit)? = null
) {
  val renderBlocks = message.renderBlocks
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
            text = if (isDebugMode) {
              message.modelName
            } else {
              formatModelName(message.modelName)
            }
          )
        }
        hasContentBefore = true
      }

      renderBlocks.forEachIndexed { index, block ->
        if (block is RenderBlock.ToolCall && block.pending &&
          !(ToolCallRendererRegistry.find(block.alias)?.rendersWhilePending() ?: false)
        ) return@forEachIndexed
        if (hasContentBefore) Spacer(modifier = Modifier.height(GradumSpacing.lg))
        hasContentBefore = true
        key(block.key(index)) {
          when (block) {
            is RenderBlock.Thinking -> {
              val hasNonThinkingAfter = renderBlocks.drop(index + 1).any { it !is RenderBlock.Thinking }
              ThinkingBlock(block, isLoading, onUrlClick, hasNonThinkingAfter)
            }

            is RenderBlock.ToolCall -> ToolCallBlock(
              block, onOpenInEditor, onViewDiff, onSubChatClick
            )

            is RenderBlock.Response -> ResponseBlock(block, onUrlClick)
            is RenderBlock.Error -> ErrorBlock(block)
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
          sendingPhase = if (isLoading) sendingPhase else message("gradum.done")
        )
      }

      if (showActions && !isLoading) {
        if (hasContentBefore) Spacer(modifier = Modifier.height(GradumSpacing.lg))
        MessageActionsRow(
          onRetry = onRetry,
          message = message,
          isLoading = isLoading,
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
}

@Composable
private fun ThinkingBlock(
  block: RenderBlock.Thinking, isLoading: Boolean,
  onUrlClick: (String) -> Unit, hasResponseAfter: Boolean = false
) {
  ThinkingIndicator(
    thinking = block.content,
    isTaskComplete = !isLoading,
    startCollapsed = !isLoading && LocalCollapseThinkingByDefault.current,
    hasResponseAfter = hasResponseAfter,
    onUrlClick = onUrlClick
  )
}

@Composable
private fun ResponseBlock(
  block: RenderBlock.Response,
  onUrlClick: (String) -> Unit
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
  onViewDiff:
    (path: String, originalContent: String, modifiedContent: String) -> Unit = { _, _, _ -> },
  onSubChatClick: ((conversationJson: String, toolCallsJson: String, title: String) -> Unit)? = null
) {
  // Delegate blocks are rendered even when pending (live countdown).
  if (block.pending && !(ToolCallRendererRegistry.find(block.alias)?.rendersWhilePending() ?: false)) return

  val fadeAlpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    fadeAlpha.animateTo(
      targetValue = 1f,
      animationSpec = tween(durationMillis = FADE_IN_MS)
    )
  }
  val animModifier = Modifier.graphicsLayer { this.alpha = fadeAlpha.value }
  val clipboardScope = rememberCoroutineScope()
  val renderer = ToolCallRendererRegistry.find(block.alias)
  if (renderer == null) {
    val fallbackToolDetails: String = if (!block.success) {
      formatToolDetails(
        alias = block.alias,
        result = block.result,
        arguments = block.arguments,
        errorDetail = block.errorDetail,
        errorMessage = block.errorMessage
      )
    } else ""
    ToolCallCapsule(
      label = block.alias,
      modifier = animModifier,
      success = block.success,
      errorInfo = ToolCallErrorInfo(
        detail = block.errorDetail,
        toolDetails = fallbackToolDetails,
        message = block.errorMessage
      ),
      iconKey = AllIconsKeys.Nodes.Plugin
    )
    return
  }
  val delegateArgs: Map<String, Any> = if (block.alias == "Delegate") {
    block.arguments + mapOf(
      "pending" to block.pending,
      "timeoutSeconds" to block.timeoutSeconds
    )
  } else block.arguments
  val content: ToolCallContent =
    renderer.parseContent(
      arguments = delegateArgs,
      result = parseJsonResult(block.result)
    )
  val toolDetails: String? = if (!block.success) {
    formatToolDetails(
      alias = block.alias,
      result = block.result,
      arguments = block.arguments,
      errorDetail = block.errorDetail,
      errorMessage = block.errorMessage
    )
  } else null
  val ctx =
    ToolCallRenderContext(
      project = null,
      isError = !block.success,
      toolDetails = toolDetails,
      errorDetail = block.errorDetail,
      onCopy = { payload ->
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
  Box(modifier = animModifier.horizontalScroll(rememberScrollState())) {
    renderer.render(content, ctx)
  }
}

@Composable
private fun TokenStatusRow(
  isLoading: Boolean, sendingPhase: String, tokenCount: Int = 0
) {
  val tokenText = if (tokenCount > 0)
    "$sendingPhase & ${message("gradum.tokens.used", formatTokenCount(tokenCount))}"
  else
    sendingPhase.ifEmpty { "..." }

  val density = LocalDensity.current
  val fadeAlpha = remember { Animatable(1f) }
  val verticalOffset = remember { Animatable(0f) }
  var displayText by remember { mutableStateOf(sendingPhase) }
  var previousText by remember { mutableStateOf(sendingPhase) }
  val riseDistancePx: Float = with(density) { -RISE_DISTANCE_DP.toPx() }

  LaunchedEffect(sendingPhase, tokenText) {
    val newText = tokenText.ifEmpty { sendingPhase }
    if (newText != previousText) {
      launch {
        verticalOffset.animateTo(
          targetValue = riseDistancePx,
          animationSpec = tween(
            easing = FastOutSlowInEasing,
            durationMillis = RISE_DURATION_MS
          ),
        )
      }
      fadeAlpha.animateTo(0f, tween(durationMillis = PHASE_FADE_MS))
      displayText = newText
      previousText = newText
      launch {
        verticalOffset.animateTo(
          targetValue = 0f,
          animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
          ),
        )
      }
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
    if (isLoading) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp))
    }
    SweepLightText(
      text = displayText,
      enabled = isLoading,
      modifier = Modifier.graphicsLayer {
        this.alpha = fadeAlpha.value
        translationY = verticalOffset.value
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
  val isDebug = selectedPermission == PermissionMode.DEBUG
  var isCopied by remember { mutableStateOf(false) }
  var isSelectedLike by remember { mutableStateOf(false) }
  var isSelectedDislike by remember { mutableStateOf(false) }

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
          key = if (isSelectedLike) GradumIcons.LikeSelected else GradumIcons.Like
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
  val isInterrupted = block.code == ErrorCode.INTERRUPTED.code
  if (isInterrupted) return

  val textErrorColor = JewelTheme.globalColors.text.error

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm),
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
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
