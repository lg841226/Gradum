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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.intellij.ide.BrowserUtil
import gradum.idea.mcp.McpRegistryResult
import gradum.idea.utils.GradumBundle.message
import gradum.idea.utils.GradumIcons
import gradum.idea.utils.GradumSpacing
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.badgeStyle
import org.jetbrains.jewel.ui.typography

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
      verticalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradumSpacing.sml)
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
          Text(text = version)
        }

        result.registryType?.let { type ->
          Badge(style = JewelTheme.badgeStyle.graySecondary) {
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
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(weight = 1f, fill = false),
              horizontalArrangement = Arrangement.spacedBy(GradumSpacing.md)
            ) {
              result.packageLine?.let { line ->
                val targetUrl = result.repositoryUrl

                ExternalLink(
                  text = line,
                  onClick = {
                    if (targetUrl != null) BrowserUtil.browse(targetUrl)
                    else BrowserUtil.browse(line)
                  },
                  modifier = Modifier.weight(weight = 1f, fill = false)
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
