/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WelcomeScreen.kt  2026-07-13 20:29:50 Changed by gwy
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Linear gradient for the welcome heading text. Anchored at
 * [GradumIcons.ColorLogo]'s `starGrad` `linearGradient`
 * (x1="100%" y1="0%" x2="0%" y2="100%"), which is translated to
 * Compose's [Offset] space: start at the top-right of the text
 * bounds, end at the bottom-left. That mirrors the logo's diagonal
 * sweep, so the heading reads as the same brand mark as the icon
 * next to it.
 *
 * The end color is nudged away from the logo's pure light blue
 * (`#7CB3FF`) toward a slightly deeper lavender-blue
 * (`#7B86E0`): keeps the gradient from washing out at the
 * light end and gives the heading a touch of purple so it doesn't
 * read as a flat sky-blue band against the white background.
 */
private val WelcomeGradient: Brush = Brush.linearGradient(
  end = Offset(0f, Float.POSITIVE_INFINITY),
  start = Offset(Float.POSITIVE_INFINITY, 0f),
  colors = listOf(Color(0xFF3070FD), Color(0xFF5C71F6))
)

/**
 * Landing screen shown before the user has sent any message. Renders a
 * centered brand header and a single chat input section.
 */
@Composable
fun WelcomeScreen(
  inputState: ChatInputState,
  textState: TextFieldState,
  suggestionVariants: List<Int>,
  modifier: Modifier = Modifier,
  inputActions: ChatInputActions,
  onRefreshSuggestions: () -> Unit,
) {
  val titleFont = remember { Font("/font/GoogleSans.ttf") }
  val titleFontFamily = remember { FontFamily(titleFont) }
  // (Typewriter text and the MiSans family that powered it are kept
  // in [TypewriterText] below as a `@Suppress("unused")` private
  // composable — re-enable when we want the welcome line back.)

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier.widthIn(max = 600.dp),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.ml)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            contentDescription = null,
            key = GradumIcons.ColorLogo,
            modifier = Modifier.size(28.dp)
          )
          Spacer(modifier = Modifier.width(GradumSpacing.md))
          Text(
            fontWeight = FontWeight.Medium,
            fontFamily = titleFontFamily,
            text = message("gradum.welcome.text"),
            style = JewelTheme.typography.h2TextStyle.copy(brush = WelcomeGradient),
            letterSpacing = GradumSpacing.welcomeTitleTracking
          )
        }
        Spacer(modifier = Modifier.height(GradumSpacing.xl))
      }
      ChatInputSection(
        state = inputState,
        textState = textState,
        actions = inputActions,
        modifier = Modifier.widthIn(max = 600.dp)
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
        .padding(vertical = GradumSpacing.lg)
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
@Suppress("unused")
private fun TypewriterText(
  text: String,
  play: Boolean = true,
  charDelayMs: Long = 20,
  modifier: Modifier = Modifier,
  initialCursorBlinkCount: Int = 3,
  cursorBlinkDurationMs: Long = 300,
  cursorColor: Color = Color.Unspecified,
  style: androidx.compose.ui.text.TextStyle
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
        if (isCursorVisible)
          withStyle(SpanStyle(color = cursorColor)) { append("_") }
        else
          append("\u00A0")
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
  Text(
    style = style,
    text = displayText,
    modifier = modifier
  )
}
