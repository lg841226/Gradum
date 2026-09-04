/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WhatsNewDialog.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.idea.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ide.BrowserUtil
import gradum.idea.BuildConfig
import gradum.idea.chat.ui.markdown.GradumMarkdown
import gradum.idea.chat.ui.markdown.LocalMarkdownBodyTextStyle
import gradum.idea.chat.ui.markdown.rememberGradumMarkdownStyling
import gradum.idea.chat.ui.markdown.rememberGradumParagraphTextStyle
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import kotlinx.coroutines.delay
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.typography
import javax.swing.JDialog
import javax.swing.WindowConstants
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.skia.Image as SkiaImage

internal data class FeatureItem(
  val titleKey: String,
  val imagePath: String?,
  val badgeIsNew: Boolean,
  val descriptionKey: String
)

private const val DIALOG_WIDTH = 900
private const val DIALOG_HEIGHT = 600
private const val ANIMATION_DURATION_MS = 300

internal fun showWhatsNewDialog() {
  val dialog: JDialog = JDialog().apply {
    isModal = true
    title = message("gradum.whatsnew.title", BuildConfig.version)
    defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    isResizable = false
    contentPane = JewelComposePanel {
      WhatsNewContent(onDismiss = { dispose() })
    }
    setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
    setLocationRelativeTo(null)
  }
  dialog.isVisible = true
}

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalFoundationApi::class, ExperimentalJewelApi::class)
@Composable
private fun WhatsNewContent(onDismiss: () -> Unit) {
  var currentPage: Int by remember { mutableStateOf(value = 0) }
  var isPlaying: Boolean by remember { mutableStateOf(value = false) }
  var isImageExpanded: Boolean by remember { mutableStateOf(value = false) }
  val features: List<FeatureItem> = remember { featureItems() }
  val pageCount: Int = features.size
  val markdownStyling: MarkdownStyling = rememberGradumMarkdownStyling()
  val baseParagraphStyle: TextStyle = rememberGradumParagraphTextStyle()
  val enlargedParagraphStyle: TextStyle = remember(key1 = baseParagraphStyle) {
    baseParagraphStyle.copy(fontSize = baseParagraphStyle.fontSize + 1.sp)
  }

  val currentIsPlaying: Boolean by rememberUpdatedState(newValue = isPlaying)

  LaunchedEffect(key1 = isPlaying) {
    if (!currentIsPlaying) return@LaunchedEffect
    while (true) {
      delay(duration = 4000L.milliseconds)
      currentPage = (currentPage + 1) % pageCount
    }
  }

  val imageWeight: Float by animateFloatAsState(
    targetValue = if (isImageExpanded) 1f else 0.6f,
    animationSpec = snap(),
    label = "imageWeight"
  )

  val handleKeyEvent: (KeyEvent) -> Boolean =
    rememberKeyEventHandler(
      isPlaying = isPlaying,
      pageCount = pageCount,
      onDismiss = onDismiss,
      currentPage = currentPage,
      isImageExpanded = isImageExpanded,
      onPageChange = { currentPage = it },
      onImageExpandChange = { isImageExpanded = it }
    )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(all = GradumSpacing.xxl)
      .onPreviewKeyEvent(handleKeyEvent),
  ) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
          val direction: Int = if (targetState > initialState) 1 else -1
          if (direction == 1) {
            (slideInHorizontally(
              animationSpec = tween(durationMillis = ANIMATION_DURATION_MS, easing = LinearOutSlowInEasing),
              initialOffsetX = { fullWidth: Int -> fullWidth }
            ) + fadeIn(animationSpec = tween(durationMillis = ANIMATION_DURATION_MS)))
              .togetherWith(
                exit = fadeOut(animationSpec = tween(durationMillis = ANIMATION_DURATION_MS))
              )
          } else {
            (slideInHorizontally(
              animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
              initialOffsetX = { fullWidth: Int -> direction * fullWidth }
            ) + fadeIn(animationSpec = tween(durationMillis = ANIMATION_DURATION_MS)))
              .togetherWith(
                exit = slideOutHorizontally(
                  animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
                  targetOffsetX = { fullWidth: Int -> -direction * fullWidth }
                ) + fadeOut(animationSpec = tween(durationMillis = ANIMATION_DURATION_MS))
              )
          }
        },
        label = "featurePage",
      ) { page: Int ->
        val feature: FeatureItem = features[page]
        Row(
          modifier = Modifier.fillMaxSize(),
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.xxl)
        ) {
          val imageBitmap = featureImageCache(feature.imagePath)

          Box(
            modifier = Modifier
              .fillMaxHeight()
              .weight(imageWeight)
              .pointerHoverIcon(PointerIcon.Hand)
              .clickable {
                if (isImageExpanded) {
                  isImageExpanded = false
                } else {
                  isImageExpanded = true
                  isPlaying = false
                }
              }
              .animateEnterExit(
                enter = fadeIn(
                  animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = ANIMATION_DURATION_MS
                  )
                ) + slideInVertically(
                  animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = ANIMATION_DURATION_MS
                  ),
                  initialOffsetY = { it / 18 }
                )
              ),
            contentAlignment = Alignment.Center
          ) {
            if (imageBitmap != null) {
              Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
              )
            } else {
              Text(
                text = message("gradum.whatsnew.no.image"),
                style = JewelTheme.typography.labelTextStyle.copy(
                  color = JewelTheme.globalColors.text.info
                )
              )
            }
          }
          AnimatedVisibility(
            visible = !isImageExpanded,
            modifier = Modifier
              .weight(0.4f)
              .fillMaxHeight()
              .animateEnterExit(
                enter = fadeIn(
                  animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = ANIMATION_DURATION_MS
                  )
                ) + slideInVertically(
                  animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = ANIMATION_DURATION_MS
                  ),
                  initialOffsetY = { it / 20 }
                )
              ),
            enter = fadeIn(animationSpec = snap()),
            exit = fadeOut(animationSpec = snap())
          ) {
            Column(
              modifier = Modifier.fillMaxHeight(),
              horizontalAlignment = Alignment.Start,
              verticalArrangement = Arrangement.Center
            ) {
              Badge(
                style =
                  if (feature.badgeIsNew) JewelTheme.badgeStyle.greenSecondary
                  else JewelTheme.badgeStyle.blue,
              ) {
                Text(
                  text =
                    if (feature.badgeIsNew) message("gradum.whatsnew.badge.new")
                    else message("gradum.whatsnew.badge.beta")
                )
              }
              CompositionLocalProvider(
                value = LocalMarkdownBodyTextStyle provides enlargedParagraphStyle
              ) {
                ProvideMarkdownStyling(markdownStyling = markdownStyling) {
                  RenderMarkdownText("## ${message(feature.titleKey)}\n\n${message(feature.descriptionKey)}")
                }
              }
            }
          }
        }
      }
    }

    Spacer(Modifier.height(GradumSpacing.xl))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Tooltip(tooltip = { Text(message("gradum.whatsnew.previous")) }) {
        IconButton(
          enabled = isPlaying || currentPage > 0,
          onClick = {
            currentPage =
              if (isPlaying && currentPage == 0) pageCount - 1
              else currentPage - 1
          },
        ) {
          Icon(
            key = AllIconsKeys.Actions.Play_back,
            contentDescription = message("gradum.whatsnew.previous")
          )
        }
      }

      Spacer(Modifier.width(GradumSpacing.lg))

      PaginationDots(
        features = features,
        pageCount = pageCount,
        isPlaying = isPlaying,
        currentPage = currentPage,
        onDotClick = { page: Int -> currentPage = page },
      )

      Spacer(Modifier.width(GradumSpacing.lg))

      Tooltip(tooltip = { Text(message("gradum.whatsnew.next")) }) {
        IconButton(
          enabled = isPlaying || currentPage < pageCount - 1,
          onClick = {
            currentPage = if (isPlaying && currentPage == pageCount - 1) 0 else currentPage + 1
          },
        ) {
          Icon(
            key = AllIconsKeys.Actions.Play_forward,
            contentDescription = message("gradum.whatsnew.next")
          )
        }
      }

      Spacer(Modifier.width(GradumSpacing.md))

      Tooltip(tooltip = {
        Text(
          text =
            if (isPlaying) message("gradum.whatsnew.auto.pause")
            else message("gradum.whatsnew.auto.play")
        )
      }) {
        ToggleableIconButton(
          value = isPlaying,
          onValueChange = { isPlaying = it }
        ) {
          Icon(
            modifier = Modifier.size(16.dp),
            key =
              if (isPlaying) AllIconsKeys.Actions.Pause
              else AllIconsKeys.Toolwindows.ToolWindowRun,
            contentDescription = if (isPlaying) message("gradum.whatsnew.auto.pause")
            else message("gradum.whatsnew.auto.play")
          )
        }
      }

      if (!isImageExpanded) {
        val uri = "https://github.com/lg841226/Gradum/releases"

        Spacer(Modifier.weight(1f))
        Tooltip(tooltip = { Text(uri) }) {
          OutlinedButton(
            content = {
              Row {
                Text(text = message("gradum.whatsnew.changelog.before"))
                Spacer(Modifier.width(GradumSpacing.sm))
                Icon(
                  key = GradumIcons.Github,
                  contentDescription = null
                )
                val afterText: String = message("gradum.whatsnew.changelog.after")
                if (afterText.isNotEmpty()) {
                  Spacer(Modifier.width(GradumSpacing.sm))
                  Text(text = afterText)
                }
              }
            },
            onClick = { BrowserUtil.browse(uri) }
          )
        }

        Spacer(Modifier.width(GradumSpacing.lg))

        DefaultButton(onClick = onDismiss) {
          Text(message("gradum.whatsnew.done"))
        }
      }
    }
  }
}

@Composable
private fun rememberKeyEventHandler(
  pageCount: Int, currentPage: Int, isPlaying: Boolean, isImageExpanded: Boolean,
  onDismiss: () -> Unit, onPageChange: (Int) -> Unit, onImageExpandChange: (Boolean) -> Unit
): (KeyEvent) -> Boolean = remember(currentPage, isPlaying, pageCount, isImageExpanded) {
  { event: KeyEvent ->
    event.type == KeyEventType.KeyUp && when (event.key) {
      Key.DirectionLeft -> {
        if (isPlaying || currentPage > 0) {
          onPageChange(
            if (isPlaying && currentPage == 0)
              pageCount - 1 else currentPage - 1
          )
        }
        true
      }

      Key.DirectionRight -> {
        if (isPlaying || currentPage < pageCount - 1) {
          onPageChange(
            if (isPlaying && currentPage == pageCount - 1) 0
            else currentPage + 1
          )
        }
        true
      }

      Key.Escape -> {
        if (isImageExpanded) onImageExpandChange(false)
        else onDismiss()
        true
      }

      else -> false
    }
  }
}

@Composable
internal fun RenderMarkdownText(markdown: String) {
  GradumMarkdown(text = markdown) {
    animationEnabled = false
    paragraphStyle = LocalMarkdownBodyTextStyle.current
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaginationDots(
  pageCount: Int,
  currentPage: Int,
  isPlaying: Boolean,
  onDotClick: (Int) -> Unit,
  features: List<FeatureItem>
) {
  val primaryColor: Color =
    when {
      JewelTheme.badgeStyle.blue.colors.background is SolidColor ->
        (JewelTheme.badgeStyle.blue.colors.background as SolidColor).value

      else -> JewelTheme.badgeStyle.blue.colors.content
    }
  val trackColor: Color = JewelTheme.globalColors.borders.disabled
  val hoverColor: Color = JewelTheme.globalColors.text.info

  val animProgress = remember { Animatable(initialValue = 0f) }
  var animPhase: Int by remember { mutableIntStateOf(value = 0) } // 0=idle, 1=auto-play, 2=pausing
  val currentIsPlaying: Boolean by rememberUpdatedState(newValue = isPlaying)

  LaunchedEffect(key1 = isPlaying, key2 = currentPage) {
    if (isPlaying) {
      animPhase = 1
      animProgress.snapTo(targetValue = 0f)
      val startNanos: Long = withFrameNanos { it }
      while (currentIsPlaying) {
        val elapsed: Float = (withFrameNanos { it } - startNanos) / 4_000_000_000f
        val progress: Float = elapsed.coerceIn(0f, 1f)
        animProgress.snapTo(targetValue = progress)
        if (progress >= 1f) break
      }
    }
  }

  LaunchedEffect(key1 = isPlaying) {
    if (!isPlaying && animPhase == 1) {
      animPhase = 2
      animProgress.animateTo(
        targetValue = 1f,
        animationSpec = tween(
          durationMillis = 250,
          easing = EaseOutCubic
        )
      )
      animPhase = 0
    }
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    repeat(times = pageCount) { index: Int ->
      val isActive: Boolean = index == currentPage
      val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
      val isHovered: Boolean by interactionSource.collectIsHoveredAsState()
      val targetColor: Color = when {
        isActive -> primaryColor
        isHovered -> hoverColor
        else -> trackColor
      }
      val dotColor: Color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "dotColor"
      )
      val dotWidth: Dp by animateDpAsState(
        targetValue = if (isActive) 60.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "dotWidth"
      )
      val cornerRadius: Dp by animateDpAsState(
        targetValue = if (isActive) 4.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "dotCornerRadius"
      )
      Tooltip(
        tooltip = {
          Text(
            text = message(features[index].titleKey),
            style = JewelTheme.typography.labelTextStyle
          )
        },
        enabled = !isActive
      ) {
        if (isActive && isPlaying) {
          Box(
            modifier = Modifier
              .size(width = dotWidth, height = 6.dp)
              .clip(shape = RoundedCornerShape(size = cornerRadius))
              .background(trackColor)
          ) {
            Box(
              modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = animProgress.value)
                .clip(shape = RoundedCornerShape(size = cornerRadius))
                .background(primaryColor)
            )
          }
        } else if (isActive && animPhase == 2) {
          Box(
            modifier = Modifier
              .size(width = dotWidth, height = 6.dp)
              .clip(shape = RoundedCornerShape(size = cornerRadius))
              .background(trackColor)
          ) {
            Box(
              modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = animProgress.value)
                .clip(shape = RoundedCornerShape(size = cornerRadius))
                .background(primaryColor)
            )
          }
        } else {
          Box(
            modifier = Modifier
              .size(width = dotWidth, height = 6.dp)
              .clip(shape = RoundedCornerShape(size = cornerRadius))
              .background(dotColor)
              .clickable(
                indication = null,
                interactionSource = interactionSource
              ) { onDotClick(index) }
          )
        }
      }
    }
  }
}

@Composable
private fun featureImageCache(baseImageName: String?): ImageBitmap? {
  val isDark: Boolean = JewelTheme.isDark
  return remember(key1 = baseImageName, key2 = isDark) {
    if (baseImageName == null) null
    else runCatching {
      val imagePath = "$baseImageName${if (isDark) "_Dark" else "_Light"}.png"
      val bytes: ByteArray = GradumConfigurable::class.java.getResourceAsStream("/new/$imagePath")
        ?.use { it.readAllBytes() }
        ?: return@remember null
      SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
  }
}

private fun featureItems(): List<FeatureItem> = listOf(
  FeatureItem(
    badgeIsNew = true,
    imagePath = "Cloud",
    titleKey = "gradum.whatsnew.feature1.title",
    descriptionKey = "gradum.whatsnew.feature1.description"
  ),
  FeatureItem(
    badgeIsNew = true,
    imagePath = "GitAudit",
    titleKey = "gradum.whatsnew.feature2.title",
    descriptionKey = "gradum.whatsnew.feature2.description"
  ),
  FeatureItem(
    badgeIsNew = false,
    imagePath = "Premissions",
    titleKey = "gradum.whatsnew.feature3.title",
    descriptionKey = "gradum.whatsnew.feature3.description"
  ),
  FeatureItem(
    badgeIsNew = true,
    imagePath = "History",
    titleKey = "gradum.whatsnew.feature4.title",
    descriptionKey = "gradum.whatsnew.feature4.description"
  )
)
