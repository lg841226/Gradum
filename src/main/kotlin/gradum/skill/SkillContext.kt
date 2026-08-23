/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillContext.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.skill

import gradum.AgentConfiguration
import gradum.Provider
import gradum.SchemaVariant
import gradum.ToolMode

/**
 * Per-session context handed to every [Skill.execute] call.
 *
 * Centralizes the session state a skill needs that is not part of the
 * LLM's tool-call arguments: the active [toolMode], the validated
 * [projectRoot], and the provider/model identity used by provider-aware
 * skills to pick schema variants.
 *
 * [agentConfiguration] and [emitEvent] are added for skills that need
 * to create sub-agents (e.g. [gradum.skill.skills.DelegateSkill]).
 */
data class SkillContext(
  val toolMode: ToolMode,
  val projectRoot: String,
  val provider: Provider = Provider.OLLAMA,
  val modelName: String = "",

  /**
   * The full [AgentConfiguration] for the current session. Skills
   * that spawn sub-agents (e.g. [gradum.skill.skills.DelegateSkill])
   * use this to derive the sub-agent's configuration.
   */
  val agentConfiguration: AgentConfiguration? = null,

  /**
   * The main conversation history for the current session. Skills
   * that spawn sub-agents use this to provide the sub-agent with
   * the main conversation context.
   */
  val conversationHistory: List<Map<String, Any>> = emptyList(),

  /**
   * Event emitter for the current session. Skills that need to
   * forward events (e.g. sub-agent tool calls) use this to emit
   * prefixed events into the same NDJSON stream.
   */
  val emitEvent: ((eventType: String, eventData: Map<String, Any>) -> Unit)? = null,
) {
  /**
   * True when the active model wants the SIMPLE schema variant (small /
   * local models). Single source of truth for the per-skill
   * `SchemaVariant.resolve(modelName) == SIMPLE` checks.
   */
  val isSimpleModel: Boolean
    get() = SchemaVariant.resolve(modelName) == SchemaVariant.SIMPLE
}