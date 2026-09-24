/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * AssistantChatBubble.kt  2026-09-25 01:18:08 Changed by gwy
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("UnstableApiUsage")

package gradum.idea.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.chat.model.*
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
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.settings.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.LocalGlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

private const val PHASE_FADE_IN_MS: Int = 300
private const val askInputWidthDp: Int = 260
private const val askArrowSlotDp: Int = 16
private const val askIconSlotDp: Int = 16
private const val askOptionNumberWidthDp: Int = 24

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
  ) -> Unit = { _: String, _: String, _: String?, _: String?, _: Boolean -> }
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
            is RenderBlock.AskInteraction -> AskCard(block, onRespondToAsk)
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
      label = block.alias,
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
    renderer.render(content, ctx)
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

/**
 * Renders the agent-initiated question card. Shows title/details, then the
 * interaction body (discrete choice buttons, or a free-text field + send
 * button). A response or cancel POSTs the answer back to the server and locks
 * the card into an "answered" state so it cannot be submitted twice.
 */
@Composable
private fun AskCard(
  block: RenderBlock.AskInteraction,
  onRespondToAsk: suspend (
    sessionId: String, requestId: String, choice: String?, text: String?, cancelled: Boolean
  ) -> Unit
) {
  val scope: CoroutineScope = rememberCoroutineScope()
  val paragraphStyle: TextStyle = rememberGradumParagraphTextStyle()
  val optionStyle: TextStyle = paragraphStyle
  val globalColors: GlobalColors = LocalGlobalColors.current
  val editorTextStyle: TextStyle = JewelTheme.editorTextStyle
  val detailStyle: TextStyle = JewelTheme.typography.editorTextStyle

  var responded: Boolean by remember { mutableStateOf(value = false) }
  val titleStyle: TextStyle = paragraphStyle.copy(fontWeight = FontWeight.SemiBold)
  val optionNumberStyle: TextStyle = paragraphStyle.copy(
    color = globalColors.text.info,
    fontFamily = editorTextStyle.fontFamily
  )

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(globalColors.borders.normal)
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Icon(
        contentDescription = null,
        key = AllIconsKeys.General.Warning,
        modifier = Modifier.width(askIconSlotDp.dp)
      )
      if (block.title.isNotBlank()) Text(text = block.title, style = titleStyle)
    }
    if (block.details.isNotBlank()) {
      Text(
        text = block.details,
        style = detailStyle,
        modifier = Modifier.padding(
          start = askIconSlotDp.dp + GradumSpacing.sml
        )
      )
    }

    Spacer(modifier = Modifier.height(GradumSpacing.md))

    when (val prompt: AskPrompt = block.prompt) {
      is AskPrompt.Choices -> {
        val defaultIndex: Int = prompt.choices.indexOfFirst { it.id == block.default }
          .coerceAtLeast(0)
        var selectedIndex: Int by remember { mutableStateOf(defaultIndex) }
        val submit: (AskChoice) -> Unit = { option: AskChoice ->
          if (!responded) {
            responded = true
            scope.launch {
              onRespondToAsk(block.sessionId, block.requestId, option.id, null, false)
            }
          }
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { keyEvent: KeyEvent ->
              if (responded) return@onPreviewKeyEvent false
              if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              when (keyEvent.key) {
                Key.DirectionUp -> {
                  selectedIndex =
                    (selectedIndex - 1 + prompt.choices.size) % prompt.choices.size
                  true
                }

                Key.DirectionDown -> {
                  selectedIndex = (selectedIndex + 1) % prompt.choices.size
                  true
                }

                Key.Enter -> {
                  submit(prompt.choices[selectedIndex])
                  true
                }

                else -> false
              }
            },
          verticalArrangement = Arrangement.spacedBy(GradumSpacing.lg)
        ) {
          Text(
            style = titleStyle,
            text = message("gradum.ask.static.options")
          )
          prompt.choices.forEachIndexed { index: Int, option: AskChoice ->
            val selected: Boolean = index == selectedIndex
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !responded) { selectedIndex = index },
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
            ) {
              if (selected) {
                Icon(
                  contentDescription = null,
                  key = AllIconsKeys.Vcs.Arrow_right,
                  modifier = Modifier.width(askArrowSlotDp.dp)
                )
              } else {
                Spacer(modifier = Modifier.width(askArrowSlotDp.dp))
              }
              Box(
                modifier = Modifier.width(askOptionNumberWidthDp.dp),
                contentAlignment = Alignment.CenterEnd
              ) {
                Text(
                  maxLines = 1,
                  text = "${index + 1}.",
                  style =
                    if (selected) optionNumberStyle
                    else optionNumberStyle.copy(color = globalColors.text.disabled)
                )
              }
              Text(
                style = optionStyle,
                text = askChoiceLabel(option.semantics)
              )
            }
          }
        }
      }

      is AskPrompt.Input -> {
        val inputState: TextFieldState = remember(key1 = block.default) {
          TextFieldState(initialText = block.default)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)) {
          TextField(
            state = inputState,
            modifier = Modifier.width(askInputWidthDp.dp),
            enabled = !responded,
            placeholder =
              if (prompt.placeholder.isNotBlank()) {
                { Text(text = prompt.placeholder) }
              } else null
          )
          OutlinedButton(
            enabled = !responded && inputState.text.isNotBlank(),
            onClick = {
              responded = true
              val submitted: String = inputState.text.toString()
              scope.launch {
                onRespondToAsk(block.sessionId, block.requestId, null, submitted, false)
              }
            }
          ) {
            Text(text = message("gradum.ask.static.send"))
          }
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(globalColors.borders.normal)
    )
  }
}

/** Maps a choice's stable semantic code to its localized button label. */
private fun askChoiceLabel(semantics: String): String = when (semantics) {
  AskChoiceMeaning.ALLOW_ONCE -> message("gradum.ask.choice.allow_once")
  AskChoiceMeaning.ALLOW_ALWAYS -> message("gradum.ask.choice.allow_always")
  AskChoiceMeaning.REJECT -> message("gradum.ask.choice.reject")
  else -> semantics
}

private fun formatTokenCount(count: Int): String {
  return when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
  }
}
