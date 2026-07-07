/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WelcomeScreen.kt  2026-07-07 16:13:30 Changed by gwy
 */

@file:OptIn(ExperimentalJewelApi::class)

package gradum.idea.chat.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.input.ChatInputActions
import gradum.idea.chat.input.ChatInputState
import gradum.idea.chat.ui.GradumSpacing
import gradum.idea.chat.ui.input.ChatInputSection
import gradum.idea.icons.GradumIcons
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Linear gradient for the welcome heading text. Colours come from
 * [GradumIcons.ColorLogo]'s `starGrad` `linearGradient`:
 *
 *   <stop offset="0%"   stop-color="#165DFF"/>
 *   <stop offset="100%" stop-color="#7CB3FF"/>
 *
 * with the SVG's x1="100%" y1="0%" x2="0%" y2="100%" axis
 * translated to Compose's [Offset] space: start at the top-right
 * of the text bounds, end at the bottom-left. That mirrors the
 * logo's diagonal sweep, so the heading reads as the same brand
 * mark as the icon next to it.
 */
private val WelcomeGradient: Brush = Brush.linearGradient(
  colors = listOf(
    Color(0xFF165DFF),
    Color(0xFF7CB3FF),
  ),
  start = Offset(Float.POSITIVE_INFINITY, 0f),
  end = Offset(0f, Float.POSITIVE_INFINITY),
)

/**
 * Landing screen shown before the user has sent any message. Renders a
 * centered brand header and a single chat input section.
 */
@Composable
fun WelcomeScreen(
  inputState: ChatInputState,
  inputActions: ChatInputActions,
  textState: TextFieldState,
  suggestionVariants: List<Int>,
  onRefreshSuggestions: () -> Unit,
  modifier: Modifier = Modifier
) {
  val welcomeIndex = remember { Random.nextInt(16) }
  val welcomeText = remember(welcomeIndex) { message("gradum.welcome.$welcomeIndex") }
  val editorFontFamily = JewelTheme.typography.editorTextStyle.fontFamily
  val normalStyle = JewelTheme.typography.regular
  val welcomeStyle = remember(welcomeIndex) {
    normalStyle.copy(fontFamily = editorFontFamily)
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier.widthIn(max = 600.dp),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.ml),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            key = GradumIcons.ColorLogo,
            contentDescription = null,
            modifier = Modifier.size(36.dp)
          )
          Spacer(modifier = Modifier.width(GradumSpacing.lg))
          Text(
            fontWeight = FontWeight.Medium,
            style = JewelTheme.typography.h2TextStyle.copy(brush = WelcomeGradient),
            text = message("gradum.welcome.text")
          )
        }
        Spacer(modifier = Modifier.height(GradumSpacing.xl))
        TypewriterText(
          text = welcomeText,
          style = welcomeStyle,
          play = inputState.modelsLoaded,
          cursorColor = JewelTheme.globalColors.outlines.focused
        )
      }
      ChatInputSection(
        modifier = Modifier.widthIn(max = 600.dp),
        state = inputState,
        actions = inputActions,
        textState = textState
      )
      QuickStartSection(
        textState = textState,
        suggestionVariants = suggestionVariants,
        onRefreshSuggestions = onRefreshSuggestions
      )
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 14.dp)
        .align(Alignment.BottomCenter),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Spacer(modifier = Modifier.width(GradumSpacing.md))
      Text(
        text = message("gradum.disclaimer"),
        style = JewelTheme.typography.small,
        fontFamily = JewelTheme.typography.editorTextStyle.fontFamily,
        color = JewelTheme.globalColors.text.info
      )
    }
  }
}

@Composable
private fun TypewriterText(
  text: String,
  style: androidx.compose.ui.text.TextStyle,
  modifier: Modifier = Modifier,
  play: Boolean = true,
  charDelayMs: Long = 20,
  initialCursorBlinkCount: Int = 3,
  cursorBlinkDurationMs: Long = 300,
  cursorColor: Color = Color.Unspecified
) {
  var visibleCharacterCount by remember { mutableStateOf(0) }
  var isCursorVisible by remember { mutableStateOf(true) }

  LaunchedEffect(text, play) {
    if (play) {
      isCursorVisible = true
      visibleCharacterCount = 0

      repeat(initialCursorBlinkCount) {
        isCursorVisible = !isCursorVisible
        delay(cursorBlinkDurationMs.milliseconds)
      }
      isCursorVisible = true

      for (index in text.indices) {
        delay(charDelayMs.milliseconds)
        visibleCharacterCount = index + 1
      }

      isCursorVisible = false
    } else {
      visibleCharacterCount = text.length
      isCursorVisible = false
    }
  }

  val displayText: AnnotatedString = when {
    visibleCharacterCount == 0 && play -> {
      buildAnnotatedString {
        if (isCursorVisible) {
          withStyle(SpanStyle(color = cursorColor)) { append("_") }
        } else {
          append("\u00A0")
        }
      }
    }

    play && visibleCharacterCount < text.length -> {
      buildAnnotatedString {
        if (visibleCharacterCount > 0)
          append(text.take(visibleCharacterCount))
        if (isCursorVisible)
          withStyle(SpanStyle(color = cursorColor)) { append("_") }
      }
    }

    else -> AnnotatedString(text.take(visibleCharacterCount))
  }
  Text(text = displayText, style = style, modifier = modifier)
}
