package gradum.idea.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.intellij.ide.BrowserUtil
import gradum.idea.BannerSeverity
import gradum.idea.GradumBanner
import gradum.idea.utils.GradumBundle.message
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// A runtime dependency one command family needs, and where to get it.
// stdio servers run on the user's machine, so a missing runtime means the
// entry can never start until the user installs it.
internal data class RuntimeRequirement(
  val executable: String,
  val displayName: String,
  val downloadUrl: String
)

// Maps a command's first token to the runtime family it belongs to. Only
// known families warn: an arbitrary or half-typed word must not flash a
// banner while the user is still writing the command.
internal fun runtimeRequirementFor(commandText: String): RuntimeRequirement? {
  val token: String = commandText.trim().split(Regex("\\s+")).firstOrNull() ?: return null
  return when (token) {
    "npx", "node" ->
      RuntimeRequirement(
        executable = "node",
        displayName = "Node.js",
        downloadUrl = "https://nodejs.org/en/download"
      )

    "uvx", "uv" ->
      RuntimeRequirement(
        executable = "uv",
        displayName = "uv",
        downloadUrl = "https://docs.astral.sh/uv/getting-started/installation/"
      )

    "python", "python3", "pip", "pip3" ->
      RuntimeRequirement(
        executable = "python",
        displayName = "Python",
        downloadUrl = "https://www.python.org/downloads/"
      )

    "docker" ->
      RuntimeRequirement(
        executable = "docker",
        displayName = "Docker",
        downloadUrl = "https://www.docker.com/products/docker-desktop/"
      )

    else -> null
  }
}

// Warning banner shown above the form while the selected server's command
// needs a runtime the machine does not have. The link opens the vendor's
// download page.
@Composable
internal fun RuntimeMissingBanner(requirement: RuntimeRequirement, modifier: Modifier = Modifier) {
  GradumBanner(
    text = message("gradum.settings.mcp.runtime.missing", requirement.displayName),
    severity = BannerSeverity.Warning,
    modifier = modifier.fillMaxWidth(),
    icon = { Icon(key = AllIconsKeys.General.Warning, contentDescription = null) },
    linkContent = {
      Link(
        text = message("gradum.settings.mcp.runtime.install", requirement.displayName),
        onClick = { BrowserUtil.browse(requirement.downloadUrl) }
      )
    }
  )
}
