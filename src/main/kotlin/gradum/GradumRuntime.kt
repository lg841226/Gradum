package gradum

import gradum.mcp.McpConnectionManager
import gradum.mode.ModeDirectoryScanner
import gradum.mode.ModeDirectoryWatcher
import gradum.server.setup.ServerSettings
import gradum.skill.external.ExternalSkillDirectoryScanner
import gradum.skill.external.ExternalSkillDirectoryWatcher

data class GradumRuntime(
  val resolvedApiKey: String?,
  val settings: ServerSettings,
  val mcpManager: McpConnectionManager,
  val skillScanner: ExternalSkillDirectoryScanner,
  val skillWatcher: ExternalSkillDirectoryWatcher,
  val modeScanner: ModeDirectoryScanner,
  val modeWatcher: ModeDirectoryWatcher
)
