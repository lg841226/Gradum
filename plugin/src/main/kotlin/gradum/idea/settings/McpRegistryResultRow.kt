@file:OptIn(ExperimentalFoundationApi::class)

package gradum.idea.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.intellij.ide.BrowserUtil
import gradum.idea.mcp.McpRegistryResult
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Badge
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.typography

// One registry hit: icon and name and version chip and ecosystem badge and status
// mark (the verified badge when the server is importable, the provider
// row's failed-connection icon when it is not) and a two-line description.
// Below them a meta line shows the installable package in the editor font
// with the repository's blue ExternalLink right after it, then the
// official publish month. The add icon stays in the trailing group;
// remote-only servers get neither badge nor icon.
@Composable
internal fun McpRegistryResultRow(
  result: McpRegistryResult, onAdd: (McpRegistryResult) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
  ) {
    Column(
      modifier = Modifier.weight(weight = 1f),
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sm)
      ) {
        Icon(
          key = GradumIcons.Mcp,
          contentDescription = null
        )
        Text(
          text = result.shortName,
          fontWeight = FontWeight.SemiBold
        )
        result.version?.let { version ->
          Badge(style = JewelTheme.badgeStyle.graySecondary) {
            Text(text = version)
          }
        }

        result.registryType?.let { type ->
          Badge(style = JewelTheme.badgeStyle.blueSecondary) {
            Text(text = registryLabel(registryType = type))
          }
        }

        if (result.command != null) {
          Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.badge.verified")) }) {
            Icon(
              key = GradumIcons.BadgeVerified,
              contentDescription = message("gradum.settings.mcp.badge.verified")
            )
          }
        } else {
          Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.registry.unsupported")) }) {
            Icon(
              key = AllIconsKeys.Vcs.Ignore_file,
              contentDescription = message("gradum.settings.mcp.registry.unsupported")
            )
          }
        }
      }
      Text(
        maxLines = 2,
        text = result.description,
        overflow = TextOverflow.Ellipsis,
        style = JewelTheme.typography.labelTextStyle
      )
      val hasMeta: Boolean =
        result.packageLine != null || result.repositoryUrl != null || result.publishedAt != null
      if (hasMeta) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
        ) {
          if (result.packageLine != null || result.repositoryUrl != null) {
            Row(
              modifier = Modifier.weight(weight = 1f, fill = false),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
            ) {
              result.packageLine?.let { line ->
                Text(
                  modifier = Modifier.weight(weight = 1f, fill = false),
                  text = line,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  color = JewelTheme.globalColors.text.info,
                  style = JewelTheme.typography.labelTextStyle,
                  fontFamily = JewelTheme.editorTextStyle.fontFamily ?: FontFamily.Default
                )
              }
              result.repositoryUrl?.let { url ->
                ExternalLink(
                  text = url,
                  onClick = { BrowserUtil.browse(url) }
                )
              }
            }
          }
          result.publishedAt?.let { published ->
            Text(
              text = message(key = "gradum.settings.mcp.registry.published", published),
              color = JewelTheme.globalColors.text.info,
              style = JewelTheme.typography.labelTextStyle
            )
          }
        }
      }
    }
    if (result.command != null) {
      Tooltip(tooltip = { Text(text = message("gradum.settings.mcp.add")) }) {
        IconButton(onClick = { onAdd(result) }) {
          Icon(
            key = AllIconsKeys.General.Add,
            contentDescription = message("gradum.settings.mcp.add")
          )
        }
      }
    }
  }
}

private fun registryLabel(registryType: String): String =
  when (registryType.lowercase()) {
    "pypi" -> "uv"
    "oci" -> "docker"
    else -> registryType.lowercase()
  }
