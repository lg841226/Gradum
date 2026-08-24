/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WelcomeScreen.kt  2026-08-24 18:59:20 Changed by gwy
 */

package gradum.idea.chat.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import gradum.idea.chat.state.ChatSessionState
import gradum.idea.chat.ui.input.ChatInputSection
import gradum.idea.settings.WelcomeLayout
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/** Callbacks for the merge-mode session management board. */
data class MergeModeCallbacks(
  val onStartMerge: () -> Unit,
  val onCancelMerge: () -> Unit,
  val onMergeSelected: () -> Unit,
  val onDeleteSelected: () -> Unit,
  val onClearMergeSelection: () -> Unit,
  val onToggleMergeSelection: (String) -> Unit,
  val onRenameSession: (String, String) -> Unit,
  val onDeleteSession: (String) -> Unit
)

/**
 * Linear gradient for the welcome heading text.
 */
private val WelcomeGradient: Brush = Brush.linearGradient(
  end = Offset(0f, Float.POSITIVE_INFINITY),
  start = Offset(Float.POSITIVE_INFINITY, 0f),
  colors = listOf(Color(0xFF3070FD), Color(0xFF5C71F6))
)

/**
 * Landing screen shown before the user has sent any message. Renders a
 * centered brand header, the chat input, quick-start suggestions, and (when
 * available) the recent-sessions list.
 */
@Composable
fun WelcomeScreen(
  state: ChatSessionState,
  modifier: Modifier = Modifier,
  welcomeLayout: WelcomeLayout = WelcomeLayout.QS4_RC2,
) {
  val titleFont = remember { Font("/font/GoogleSans.ttf") }
  val titleFontFamily = remember { FontFamily(titleFont) }

  // Derive MergeModeCallbacks from ChatSessionState
  val mergeCallbacks = remember(state) {
    MergeModeCallbacks(
      onStartMerge = state.onStartMerge,
      onCancelMerge = state.onCancelMerge,
      onMergeSelected = state.onMergeSelected,
      onDeleteSelected = state.onDeleteSelected,
      onClearMergeSelection = state.onClearMergeSelection,
      onToggleMergeSelection = state.onToggleMergeSelection,
      onRenameSession = state.onRenameSession,
      onDeleteSession = state.onDeleteSession
    )
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    if (state.isMergeModeActive) {
      ManageSessionsBoard(
        sessions = state.sessions.toList(),
        selectedIds = state.mergeSelectedIds,
        onMerge = mergeCallbacks.onMergeSelected,
        onClearSelection = mergeCallbacks.onClearMergeSelection,
        onDeleteSelected = mergeCallbacks.onDeleteSelected,
        onBack = mergeCallbacks.onCancelMerge,
        onToggleSelection = mergeCallbacks.onToggleMergeSelection,
        onRenameSession = mergeCallbacks.onRenameSession,
        onDeleteSession = mergeCallbacks.onDeleteSession
      )
    } else {
      val isInputFocused: Boolean = state.inputState.isFocused
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(GradumSpacing.ml)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
          ) {
            Icon(
              contentDescription = null,
              key = GradumIcons.ColorLogo,
              modifier = Modifier.size(28.dp)
            )
            Text(
              fontWeight = FontWeight.Medium,
              fontFamily = titleFontFamily,
              text = message("gradum.welcome.text"),
              style = JewelTheme.typography.h2TextStyle.copy(brush = WelcomeGradient),
              letterSpacing = GradumSpacing.welcomeTitleTracking
            )
          }
          Spacer(modifier = Modifier.height(GradumSpacing.md))
        }
        ChatInputSection(
          state = state.inputState,
          textState = state.textState,
          actions = state.inputActions,
          selectedPermission = state.selectedPermission,
          modifier = Modifier.widthIn(max = 600.dp)
        )
        AnimatedVisibility(
          visible = !isInputFocused || state.sessions.isEmpty(),
          exit = shrinkVertically(animationSpec = tween(200))
        ) {
          if (welcomeLayout.quickStartCount > 0) {
            QuickStartSection(
              textState = state.textState,
              suggestionVariants = state.suggestionVariants,
              onRefreshSuggestions = state.onRefreshSuggestions,
              maxItems = welcomeLayout.quickStartCount
            )
          }
        }
        if (state.sessions.isNotEmpty()) {
          RecentChatsSection(
            sessions = state.sessions.toList(),
            expanded = isInputFocused,
            maxDisplay = welcomeLayout.recentCount,
            onStartMerge = mergeCallbacks.onStartMerge,
            onOpenSession = state.onOpenSession,
            onDeleteSession = mergeCallbacks.onDeleteSession
          )
        }
      }
    }
    if (!state.isMergeModeActive) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = GradumSpacing.lg)
          .align(Alignment.BottomCenter),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Spacer(modifier = Modifier.width(GradumSpacing.md))
        Text(
          text = message("gradum.disclaimer"),
          style = JewelTheme.typography.small,
          color = JewelTheme.globalColors.text.info,
          fontFamily = JewelTheme.typography.editorTextStyle.fontFamily
        )
      }
    }
  }
}
