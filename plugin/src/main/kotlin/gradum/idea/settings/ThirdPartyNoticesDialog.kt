/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThirdPartyNoticesDialog.kt  2026-08-23 21:12:19 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ide.BrowserUtil
import gradum.idea.chat.ui.markdown.*
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text
import java.awt.Dimension
import javax.swing.JDialog
import javax.swing.WindowConstants

enum class FontSizeOption(val labelKey: String) {
  SMALL("gradum.settings.oss.font.small"),
  MEDIUM("gradum.settings.oss.font.medium"),
  LARGE("gradum.settings.oss.font.large")
}

val FontSizeOption.label: String get() = message(labelKey)

private val FontSizeOption.fontSizeSp: Float
  get() = when (this) {
    FontSizeOption.SMALL -> 11f
    FontSizeOption.MEDIUM -> 13f
    FontSizeOption.LARGE -> 18f
  }

// Dialog size constants for the third-party notices dialog
private const val THIRD_PARTY_DIALOG_WIDTH = 540
private const val THIRD_PARTY_DIALOG_HEIGHT = 750
private const val THIRD_PARTY_DIALOG_MIN_WIDTH = 400
private const val THIRD_PARTY_DIALOG_MIN_HEIGHT = 520
private const val THIRD_PARTY_DIALOG_MAX_WIDTH = 800
private const val THIRD_PARTY_DIALOG_MAX_HEIGHT = 1000

// Inner content max width — keeps text readable when dialog is wide
private const val THIRD_PARTY_CONTENT_MAX_WIDTH = 540

internal fun showThirdPartyNoticesDialog() {
  val dialog = JDialog().apply {
    isModal = true
    title = message("gradum.settings.oss.dialog.title")
    defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    isResizable = true
    minimumSize = Dimension(THIRD_PARTY_DIALOG_MIN_WIDTH, THIRD_PARTY_DIALOG_MIN_HEIGHT)
    maximumSize = Dimension(THIRD_PARTY_DIALOG_MAX_WIDTH, THIRD_PARTY_DIALOG_MAX_HEIGHT)
    contentPane = JewelComposePanel {
      ThirdPartyNoticesContent()
    }
    setSize(THIRD_PARTY_DIALOG_WIDTH, THIRD_PARTY_DIALOG_HEIGHT)
    setLocationRelativeTo(null)
  }
  dialog.isVisible = true
}

@Composable
private fun ThirdPartyNoticesContent() {
  ProvideAppearance {
    val baseFontSize: TextUnit = LocalParagraphFontSize.current
    CompositionLocalProvider(
      LocalParagraphFontSize provides baseFontSize.value.sp
    ) {
      ThirdPartyNoticesContentInner()
    }
  }
}

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ThirdPartyNoticesContentInner() {
  val markdownText = remember {
    runCatching {
      object {}::class.java
        .getResourceAsStream("/legal/third_party_notices.md")
        ?.use { it.reader().readText() }
        ?: ""
    }.getOrDefault("")
  }
  val scrollState = rememberScrollState()
  val markdownStyling = rememberGradumMarkdownStyling()
  val focusRequester = remember { FocusRequester() }
  val stickyRegistry = remember { StickySectionRegistry() }
  var fontSizeOption by remember { mutableStateOf(FontSizeOption.MEDIUM) }
  val fontSizeSp = fontSizeOption.fontSizeSp

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(all = GradumSpacing.xxl)
      .focusRequester(focusRequester)
      .focusTarget()
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = THIRD_PARTY_CONTENT_MAX_WIDTH.dp)
        .align(Alignment.Center)
    ) {
      val fontSizeButtons = FontSizeOption.entries.map { option ->
        SegmentedControlButtonData(
          selected = option == fontSizeOption,
          onSelect = { fontSizeOption = option },
          content = { Text(text = option.label) }
        )
      }
      SegmentedControl(
        enabled = true,
        buttons = fontSizeButtons
      )

      Spacer(modifier = Modifier.height(GradumSpacing.lg))

      CompositionLocalProvider(
        LocalParagraphFontSize provides fontSizeSp.sp,
        LocalStickySectionRegistry provides stickyRegistry
      ) {
        val editorParagraphStyle = rememberGradumParagraphTextStyle()

        Box(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .verticalScroll(scrollState)
              .onGloballyPositioned { coordinates ->
                stickyRegistry.columnOriginInWindow = coordinates.localToWindow(Offset.Zero)
              }
          ) {
            CompositionLocalProvider(
              LocalMarkdownBodyTextStyle provides editorParagraphStyle
            ) {
              ProvideMarkdownStyling(markdownStyling = markdownStyling) {
                RenderThirdPartyNotices(
                  markdown = markdownText,
                  onUrlClick = { BrowserUtil.browse(it) }
                )
              }
            }
          }

          val enableStickySections: Boolean = LocalEnableStickySections.current
          val activeSection = if (enableStickySections) {
            stickyRegistry.entries.firstOrNull { entry ->
              scrollState.value >= entry.topInColumn && scrollState.value < entry.bottomInColumn
            }
          } else null
          if (enableStickySections) {
            Box(modifier = Modifier.fillMaxWidth()) {
              stickyRegistry.entries.forEach { section ->
                val isActive = section == activeSection
                val remaining = section.bottomInColumn - scrollState.value
                val toolbarHeight = section.toolbarHeight
                val alpha =
                  if (isActive && toolbarHeight > 0f) {
                    val linear = ((remaining - toolbarHeight) / (toolbarHeight * 0.5f)).coerceIn(0f, 1f)
                    FastOutSlowInEasing.transform(linear)
                  } else if (isActive) 1f else 0f
                if (alpha > 0f) {
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .graphicsLayer { this.alpha = alpha }
                      .background(JewelTheme.globalColors.panelBackground)
                      .onGloballyPositioned {
                        section.toolbarHeight = it.size.height.toFloat()
                      }
                  ) {
                    section.toolbar()
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RenderThirdPartyNotices(
  markdown: String, onUrlClick: (String) -> Unit
) {
  GradumMarkdown(text = markdown) {
    this.onUrlClick = onUrlClick
    animationEnabled = false
    paragraphStyle = LocalMarkdownBodyTextStyle.current
  }
}
