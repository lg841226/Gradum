package gradum

import gradum.mcp.McpConnectionManager
import gradum.server.ServerSettings
import gradum.skill.external.ExternalSkillDirectoryScanner
import gradum.skill.external.ExternalSkillDirectoryWatcher

/**
 * Immutable runtime state shared by both HTTP and ACP entry points.
 *
 * Everything here is safe to access from either transport: settings are
 * read-only after startup, mcpManager/skillScanner/skillWatcher have their
 * own internal locking, and resolvedApiKey is a plain string reference.
 *
 * Acquiring an [gradum.agent.Agent] for a session is still per-request work: the
 * runtime only holds the long-lived components.
 */
data class GradumRuntime(
  val resolvedApiKey: String?,
  val settings: ServerSettings,
  val mcpManager: McpConnectionManager,
  val skillScanner: ExternalSkillDirectoryScanner,
  val skillWatcher: ExternalSkillDirectoryWatcher
)
