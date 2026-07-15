/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Skill.kt  2026-07-15 20:19:37 Changed by gwy
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
   * How many recent calls of this skill keep their result
   * fields in full. Older calls (those older than the keep
   * count in conversation history) have their
   * [historyVolatileKeys] stripped by [compactHistory] at the
   * start of the next call. Defaults to [Int.MAX_VALUE] (no
   * stripping ever).
   *
   * **Applies to OLDER history only.** The current call's
   * result is always returned to the LLM in full (or
   * filtered through [prepareHistoryResult] if the subclass
   * overrides it) — never silently stripped based on
   * `historyKeepCount`. The previous design incorrectly
   * stripped the current call from the third invocation
   * onward, which left the agent blind to its own
   * just-returned result. See the doc on [compactHistory]
   * for the corrected path.
   */
  open val historyKeepCount: Int = Int.MAX_VALUE

  /**
   * Field names removed from older tool messages when
   * [compactHistory] runs. Defaults to empty.
   *
   * These should be REDUNDANT or oversized fields — diff
   * payloads, command stdout that the LLM can re-derive by
   * re-running, verbose metadata. They must NEVER be the
   * primary payload the LLM just asked for; the LLM still
   * needs to see those on the current call.
   */
  open val historyVolatileKeys: List<String> = emptyList()

  /**
   * How many times [recordAndCompactHistory] has been invoked
   * for this skill instance in the current session. Reset to
   * zero by [resetHistoryCount] at session start (see
   * `Agent.onSessionStart`). Bumped at the start of
   * [recordAndCompactHistory].
   */
  private var prepareHistoryCallCount: Int = 0

  /** Current call count for the session. Read-only from the outside. */
  val callCount: Int get() = prepareHistoryCallCount

  /**
   * Resets the internal history call counter.
   * Should be called at the start of each session to ensure
   * [historyKeepCount] behaves correctly across sessions.
   */
  fun resetHistoryCount() {
    prepareHistoryCallCount = 0
  }

  /**
   * Increment [prepareHistoryCallCount], strip
   * [historyVolatileKeys] from the OLDER entries in
   * [conversationHistory] in place, and return the processed
   * version of [currentResult] to add to conversation history
   * (and to return to the LLM this turn).
   *
   * This is the only sanctioned way to do per-call history
   * work — direct calls to [compactHistory] or
   * [prepareHistoryResult] from the Agent are discouraged
   * because they bypass the counter increment and can leave
   * the two paths inconsistent.
   *
   * @param conversationHistory the agent's full conversation
   *   history. Mutated in place: older "own" messages are
   *   replaced with versions whose `content` JSON is missing
   *   the volatile keys.
   * @param ownMessageIndices indices into
   *   [conversationHistory] of this skill's prior tool
   *   messages, in chronological order (oldest first, most
   *   recent last). The current call's result is NOT yet in
   *   the list — the agent appends it after this call
   *   returns. The Agent computes these by filtering
   *   `conversationHistory` to `role=tool` entries whose
   *   `alias` matches [alias].
   *
   * The two operations are coupled by design: the
   * post-increment counter is the same value passed to
   * [compactHistory] and is the value a custom
   * [prepareHistoryResult] can read via [callCount] if it
   * needs to know "this is the Nth call".
   */
  fun recordAndCompactHistory(
    currentResult: Map<String, Any>,
    conversationHistory: MutableList<Map<String, Any>>,
    ownMessageIndices: List<Int>
  ): Map<String, Any> {
    prepareHistoryCallCount++
    compactHistory(conversationHistory, ownMessageIndices, prepareHistoryCallCount)
    return prepareHistoryResult(currentResult)
  }

  /**
   * Return the version of [result] that should be (a) added to
   * conversation history and (b) shown to the LLM this turn.
   * Default: return as-is. The LLM must see what the tool just
   * produced.
   *
   * Override ONLY when the current result carries data too
   * large for the LLM's view of THIS turn — `EditFileSkill`
   * strips `originalContent` / `modifiedContent` diff payloads
   * because the LLM does not need the full pre- / post-strings to
   * understand "an edit happened at path X with N added M
   * removed" and those blobs blow the response context.
   *
   * **Do not** override to strip based on [historyKeepCount] /
   * [historyVolatileKeys] — that is [compactHistory]'s job and
   * applies to OLDER messages only. Stripping the current
   * call's primary data blinds the agent to the result it
   * just got.
   */
  open fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> = result

  /**
   * Compact older conversation history entries to save
   * context. The default implementation strips
   * [historyVolatileKeys] from the OLDEST
   * `callCount - historyKeepCount` entries in
   * [conversationHistory] (indexed by [ownMessageIndices]).
   * Recent entries (the LLM's working set of
   * [historyKeepCount] most recent calls) are kept intact so
   * the LLM can still refer back to them within the same
   * session without re-invoking the tool.
   *
   * @param conversationHistory the agent's full conversation
   *   history. Mutated in place: older "own" messages are
   *   replaced with versions whose `content` JSON is missing
   *   the volatile keys. Entries whose `content` is not a JSON
   *   object (plain string tool results, errors, etc.) are
   *   left untouched.
   * @param ownMessageIndices indices into
   *   [conversationHistory] of this skill's prior tool
   *   messages, in chronological order (oldest first, most
   *   recent last). The current call's result is NOT in this
   *   list.
   * @param callCount the index of the current call (1-based,
   *   so the first call has `callCount = 1`, second has
   *   `callCount = 2`, etc.). Total number of calls of this
   *   skill in the session, including the current one.
   */
  open fun compactHistory(
    conversationHistory: MutableList<Map<String, Any>>,
    ownMessageIndices: List<Int>,
    callCount: Int
  ) {
    if (historyKeepCount == Int.MAX_VALUE || historyVolatileKeys.isEmpty()) return
    if (ownMessageIndices.isEmpty()) return
    val dropCount: Int = (callCount - historyKeepCount).coerceAtLeast(0)
    val dropEndIndex: Int = dropCount.coerceAtMost(ownMessageIndices.size)
    if (dropEndIndex == 0) return
    for (i in 0 until dropEndIndex) {
      val historyIndex: Int = ownMessageIndices[i]
      if (historyIndex in conversationHistory.indices) {
        conversationHistory[historyIndex] =
          stripVolatileKeys(conversationHistory[historyIndex])
      }
    }
  }

  private fun stripVolatileKeys(message: Map<String, Any>): Map<String, Any> {
    val content: String = message["content"] as? String ?: return message
    val parsed: Map<String, Any?>? = try {
      gradum.utils.JsonUtil.decodeMap(content)
    } catch (e: Exception) {
      // Not a JSON object (plain string, error marker, etc.) —
      // leave the message as-is.
      return message
    }
    val stripped: Map<String, Any?> = parsed?.filterKeys { it !in historyVolatileKeys } ?: emptyMap()
    val reencoded: String = gradum.utils.JsonUtil.encodeMap(stripped)
    return message + ("content" to reencoded)
  }
}
