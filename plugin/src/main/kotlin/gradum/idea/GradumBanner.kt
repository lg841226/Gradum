/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumBanner.kt  2026-08-07 23:44:17 Changed by gwy
 */

package gradum.idea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.BannerColors
import org.jetbrains.jewel.ui.component.styling.BannerMetrics
import org.jetbrains.jewel.ui.component.styling.DefaultBannerStyle
import org.jetbrains.jewel.ui.theme.defaultBannerStyle
import org.jetbrains.jewel.ui.typography

/**
 * Banner severity level, controls the border and background tint.
 */
enum class BannerSeverity {
  Success, Warning, Error
}

/**
 * Customizable banner component based on Jewel's DefaultBannerImpl.
 *
 * @param text The banner message text.
 * @param severity Severity level, controls the color scheme.
 * @param showClose Whether to render the close icon button area.
 */
@Composable
internal fun GradumBanner(
  text: String,
  showClose: Boolean = true,
  modifier: Modifier = Modifier,
  icon: (@Composable () -> Unit)? = null,
  linkContent: @Composable (() -> Unit)? = null,
  iconContent: (@Composable (() -> Unit))? = null,
  severity: BannerSeverity = BannerSeverity.Success,
  textStyle: TextStyle = JewelTheme.typography.regular,
) {
  val originalStyle = when (severity) {
    BannerSeverity.Success -> JewelTheme.defaultBannerStyle.success
    BannerSeverity.Warning -> JewelTheme.defaultBannerStyle.warning
    BannerSeverity.Error -> JewelTheme.defaultBannerStyle.error
  }
  val compactStyle = DefaultBannerStyle(
    colors = BannerColors(
      border = originalStyle.colors.border,
      background = originalStyle.colors.background
    ),
    metrics = BannerMetrics(
      cornerSize = originalStyle.metrics.cornerSize,
      borderWidth = originalStyle.metrics.borderWidth,
      padding = PaddingValues(
        vertical = GradumSpacing.sml,
        horizontal = GradumSpacing.xl
      )
    )
  )

  val bannerShape = RoundedCornerShape(6.dp)

  Column(modifier = modifier.width(IntrinsicSize.Max)) {
    Row(
      modifier = Modifier
        .clip(bannerShape)
        .border(1.dp, compactStyle.colors.border, bannerShape)
        .background(compactStyle.colors.background)
        .padding(compactStyle.metrics.padding),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (icon != null) {
        Box(
          Modifier.size(GradumSpacing.xl),
          contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(modifier = Modifier.width(GradumSpacing.md))
      }

      Box(Modifier.weight(1f)) {
        Text(
          text = text,
          maxLines = 1,
          style = textStyle,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (showClose && (linkContent != null || iconContent != null)) {
        Spacer(Modifier.width(GradumSpacing.md))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md),
        ) {
          linkContent?.invoke()
          iconContent?.invoke()
        }
      }
    }
  }
}
