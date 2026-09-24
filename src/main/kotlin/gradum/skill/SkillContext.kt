/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillContext.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.AgentConfiguration
import gradum.Provider
import gradum.SchemaVariant
import gradum.ToolMode
import gradum.agent.Agent

/**
 * Per-session context handed to every [Skill.execute] call.
 *
 * Centralizes the session state a skill needs that is not part of the
 * LLM's tool-call arguments: the active [toolMode], the validated
 * [projectRoot], and the provider/model identity used by provider-aware
 * skills to pick schema variants.
 *
 * [agentConfiguration] and [emitEvent] are added for skills that need
 * to create sub-agents (e.g. [DelegateSkill]).
 *
 * [registerChildSession] and [unregisterChildSession] enable skills
 * that spawn sub-agents to register them in the server's session
 * hierarchy ([gradum.server.Routes.SessionEntry]), so that stopping
 * the parent session via `POST /stop` cascades to all child sessions
 * and terminates them on the server side — not just in the plugin UI.
 */
data class SkillContext(
  val toolMode: ToolMode,
  val projectRoot: String,
  val modelName: String = "",
  val provider: Provider = Provider.OLLAMA,

  /**
   * The full [AgentConfiguration] for the current session. Skills
   * that spawn sub-agents (e.g. [DelegateSkill])
   * use this to derive the sub-agent's configuration.
   */
  val agentConfiguration: AgentConfiguration? = null,

  /**
   * The main conversation history for the current session. Skills that
   * spawn sub-agents use this to provide the sub-agent with
   * the main conversation context.
   *
   * This is a lazy-access lambda (not a snapshot) so that reading it
   * during a tool-call execution returns the current message list,
   * which may have been populated after the [SkillContext] was created.
   */
  val conversationHistory: () -> List<Map<String, Any>> = { emptyList() },

  /**
   * Event emitter for the current session. Skills that need to
   * forward events (e.g. sub-agent tool calls) use this to emit
   * prefixed events into the same NDJSON stream.
   */
  val emitEvent: ((eventType: String, eventData: Map<String, Any>) -> Unit)? = null,

  /**
   * Callback to register a sub-agent in the server's session
   * hierarchy. When non-null, skills that spawn sub-agents
   * (e.g. [DelegateSkill]) call this with a unique child session
   * ID and the [Agent] instance so that [POST /stop] on the parent
   * session cascades to terminate the sub-agent.
   *
   * Implementation is provided by Routes and
   * scoped to the parent session's SessionEntry.
   */
  val registerChildSession: ((childSessionId: String, agent: Agent) -> Unit)? = null,

  /**
   * Callback to unregister a sub-agent from the server's session
   * hierarchy after it completes or errors. Paired with
   * [registerChildSession]; the [DelegateSkill] calls this in
   * a `finally` block to guarantee cleanup.
   */
  val unregisterChildSession: ((childSessionId: String) -> Unit)? = null,

  /**
   * Ask capability for this session. When non-null, skills may call
   * `scope.ask_interaction { ... }` to pause for a user decision and
   * resume with their answer. Null when the agent was not wired with a
   * [PendingQuestions] (e.g. tests or sub-agents that must not block) —
   * a skill must fall back when this is absent instead of assuming it exists.
   */
  val scope: AskScope? = null
) {
  /**
   * Session-scoped cache of externally-authorized read paths (e.g. a
   * `read_file` target outside the project root that the user approved
   * "always"). In-memory only, never persisted; a session restart asks
   * again. Not part of the constructor/equality — it lives for the
   * lifetime of the per-session [SkillContext] instance.
   */
  val authorizedReadPaths: MutableSet<String> = mutableSetOf()

  /**
   * True when the active model wants the SIMPLE schema variant (small /
   * local models). Single source of truth for the per-skill
   * `SchemaVariant.resolve(modelName) == SIMPLE` checks.
   */
  val isSimpleModel: Boolean get() = SchemaVariant.resolve(modelName) == SchemaVariant.SIMPLE
}
