package gradum.skill

import gradum.AgentConfiguration
import gradum.Provider
import gradum.SchemaVariant
import gradum.ToolMode
import gradum.agent.Agent
import gradum.agent.MessageRecord

/**
 * Per-session context handed to every [Skill.execute] call.
 *
 * Centralizes session state a skill needs that is not part of the LLM's
 * tool-call arguments: the active [toolMode], validated [projectRoot], and
 * provider/model identity used by provider-aware skills to pick schema variants.
 *
 * [agentConfiguration] and [emitEvent] are added for skills that spawn sub-agents
 * (e.g. [DelegateSkill]). [registerChildSession]/[unregisterChildSession] enable
 * skills that spawn sub-agents to register them in the server's session hierarchy,
 * so `POST /stop` on the parent cascades to all child sessions.
 */
data class SkillContext(
  val toolMode: ToolMode,
  val projectRoot: String,
  val modelName: String = "",
  val provider: Provider = Provider.OLLAMA,

  /** Full AgentConfiguration for current session; used by sub-agent spawning skills. */
  val agentConfiguration: AgentConfiguration? = null,

  /**
   * Main conversation history (lazy lambda, not a snapshot).
   * Reading during tool execution returns current message list.
   */
  val conversationHistory: () -> List<MessageRecord> = { emptyList() },

  /** Event emitter for current session; skills forward events into same NDJSON stream. */
  val emitEvent: ((eventType: String, eventData: Map<String, Any>) -> Unit)? = null,

  /** Register sub-agent in server session hierarchy (provided by Routes). */
  val registerChildSession: ((childSessionId: String, agent: Agent) -> Unit)? = null,

  /** Unregister sub-agent after completion/error (paired with registerChildSession). */
  val unregisterChildSession: ((childSessionId: String) -> Unit)? = null,

  /**
   * Ask capability for this session; non-null allows scope.askInteraction { ... }.
   * Null in tests/sub-agents that must not block; skill must fall back.
   */
  val scope: AskScope? = null,

  /** Base URL of Gradum server for this session (for skill HTTP calls). */
  val serverUrl: String? = null,

  /** Bearer token for authenticating against this server's HTTP APIs. */
  val serverApiKey: String? = null,

  /** Per-skill plugin config sections (plugins.<skillName>), from settings.json. */
  val plugins: Map<String, Map<String, Any?>> = emptyMap(),
) {
  /**
   * Session-scoped cache of externally-authorized read paths (out-of-project).
   * In-memory only, never persisted; session restart asks again.
   */
  val authorizedReadPaths: MutableSet<String> = mutableSetOf()

  /**
   * Session-scoped cache of externally-authorized write paths (out-of-project).
   * In-memory only, never persisted; session restart asks again.
   */
  val authorizedWritePaths: MutableSet<String> = mutableSetOf()

  /**
   * Session-scoped cache of dangerous command categories approved "always".
   * In-memory only, never persisted; session restart asks again.
   */
  val authorizedCommandCategories: MutableSet<String> = mutableSetOf()

  /**
   * Session-scoped cache of MCP tools approved "always".
   * In-memory only, never persisted; session restart asks again.
   */
  val authorizedMcpTools: MutableSet<String> = mutableSetOf()

  /**
   * Session-scoped registry of MCP tools materialized via mcp_tools directory skill.
   * Retained for last MaterializedMcpTools.DEFAULT_MAX_ROUNDS rounds; older trimmed.
   * In-memory only; fresh session starts with only directory skill exposed.
   */
  val materializedMcpTools: MaterializedMcpTools = MaterializedMcpTools()

  /** True when active model wants SIMPLE schema variant (small/local models). */
  val isSimpleModel: Boolean get() = SchemaVariant.resolve(modelName) == SchemaVariant.SIMPLE

  /**
   * True in ToolMode.YOLO (user opted out of every permission gate).
   * Single source of truth for that bypass; skills must not re-derive from toolMode.
   */
  val isYolo: Boolean get() = toolMode == ToolMode.YOLO
}