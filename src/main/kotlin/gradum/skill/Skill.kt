/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Skill.kt  2026-07-05 22:56:12 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode

abstract class Skill {
  abstract val skillName: String
  abstract val description: String
  abstract val alias: String

  /**
   * The set of [ToolMode] values under which this skill is allowed to
   * execute. The [gradum.agent.Agent] checks this once per call, BEFORE
   * invoking [execute], and rejects any call whose mode is not in the
   * set with [gradum.ErrorCode.TOOL_NOT_PERMITTED]. This is the SINGLE
   * source of truth for mode gating — the agent does NOT maintain a
   * per-skill allowlist of its own.
   *
   * Skills that mutate the project (write files, run destructive
   * commands) must exclude [ToolMode.READ_ONLY]. Skills that do
   * multistep task planning must exclude [ToolMode.EDIT].
   * Skills that are pure inspection (read_file, explore_project,
   * run_cmd) leave the default — every mode is allowed.
   *
   * Why this is in the interface, not in [gradum.skill.SkillRegistry]:
   * SkillRegistry only knows the schema whitelist used to build the
   * LLM's tool list. The runtime gate has to live on the Skill itself
   * because LLM-hallucinated tool calls bypass the schema filter — the
   * model can still call `edit_file` even when its tool list shows
   * only three read-only tools, so the agent has to enforce the
   * invariant regardless of what the schema filter let through.
   */
  open val allowedToolModes: Set<ToolMode> = setOf(
    ToolMode.AGENT, ToolMode.EDIT, ToolMode.READ_ONLY
  )

  /**
   * Execute the skill with the LLM's tool-call arguments and the
   * per-session [SkillContext] (tool mode + project root).
   *
   * The [context] is the single source of truth for session-level
   * state. Skills that need to know the project root or the active
   * permission tier should read them from here — never from a
   * process-global like `ProjectPaths`. The agent constructs one
   * [SkillContext] per session from `AgentConfiguration` and passes
   * the same instance to every call, so all Skills see the same
   * project root and the same [ToolMode] within a session.
   *
   * @param arguments LLM-supplied tool-call arguments, with the
   *   `projectRoot` key already injected by the agent (LLM-supplied
   *   `projectRoot` / `project_root` are stripped before injection).
   * @param context per-session state owned by the agent.
   */
  abstract fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult

  /**
   * Returns the OpenAI-compatible function schema for this skill.
   *
   * @param context optional session context. When provided, skills
   *   that offer different parameter structures for local vs cloud
   *   models (e.g. [EditFileSkill]) can return a schema tailored
   *   to the active [gradum.Provider]. Skills that don't need
   *   provider-aware schemas may ignore this parameter.
   */
  abstract fun getSchema(context: SkillContext? = null): Map<String, Any>

  /**
   * How many recent results keep their [historyVolatileKeys] in conversation history.
   * Older results beyond this count will have those keys stripped to save context.
   * Default [Int.MAX_VALUE] keeps all results intact (no stripping).
   */
  open val historyKeepCount: Int = Int.MAX_VALUE

  /**
   * Keys to strip from history result when exceeding [historyKeepCount].
   * Only relevant when [historyKeepCount] is not [Int.MAX_VALUE].
   */
  open val historyVolatileKeys: List<String> = emptyList()

  /** Tracks how many times [prepareHistoryResult] has been called in this session. */
  protected var prepareHistoryCallCount: Int = 0

  /**
   * Resets the internal history call counter.
   * Should be called at the start of each session to ensure
   * [historyKeepCount] behaves correctly across sessions.
   */
  fun resetHistoryCount() {
    prepareHistoryCallCount = 0
  }

  /**
   * Strips volatile keys from older history entries to save context window space.
   * Newer results (within [historyKeepCount]) are returned untouched; older ones
   * have their [historyVolatileKeys] removed.
   */
  open fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
    prepareHistoryCallCount++

    // No stripping if: unlimited retention OR no volatile keys defined
    if (historyKeepCount == Int.MAX_VALUE || historyVolatileKeys.isEmpty())
      return result

    // Keep recent results intact; strip volatile keys from older ones
    return if (prepareHistoryCallCount <= historyKeepCount)
      result
    else
      result.filterKeys { it !in historyVolatileKeys }
  }
}
