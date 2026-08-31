/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Skill.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import gradum.utils.JsonUtil.decodeMap
import gradum.utils.JsonUtil.encodeMap

abstract class Skill {
  abstract val skillName: String
  abstract val description: String
  abstract val alias: String

  /**
   * The [ToolMode] values under which this skill may execute.
   *
   * The runtime gate lives here, not in [gradum.skill.SkillRegistry],
   * because LLM-hallucinated tool calls bypass the schema filter — the
   * agent must enforce the invariant regardless of what the schema
   * whitelist let through. Skills that mutate the project must exclude
   * [ToolMode.READ_ONLY].
   */
  open val allowedToolModes: Set<ToolMode> = setOf(
    ToolMode.AGENT, ToolMode.EDIT, ToolMode.READ_ONLY
  )

  /**
   * Whether this skill may execute under [toolMode].
   *
   * The single authority for the tool-mode gate — both the schema filter
   * in [gradum.skill.SkillRegistry] and the runtime gate in
   * [gradum.agent.Agent] call this so the two checks can never disagree.
   */
  fun allows(toolMode: ToolMode): Boolean = toolMode in allowedToolModes

  /**
   * Execute the skill with the LLM's tool-call arguments and the
   * per-session [SkillContext] (tool mode + project root).
   */
  abstract fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult

  /**
   * Returns the OpenAI-compatible function schema for this skill.
   *
   * A final template method: it decides whether the active model is simple by
   * reading [SkillContext.isSimpleModel], then renders [schemaProperties]
   * through the [SchemaBuilder] DSL. A skill declares its parameters once and
   * never branches on simple vs cloud here.
   */
  fun getSchema(context: SkillContext? = null): Map<String, Any> {
    val useSimple: Boolean = context?.isSimpleModel == true
    return buildFunctionSchema(
      useSimple = useSimple,
      description =
        if (useSimple) (simpleDescription ?: description) else description,
    ) {
      schemaProperties()
    }
  }

  /**
   * Single source of truth for this skill's parameters, declared with the
   * [SchemaBuilder] DSL (`string`, `integer`, `boolean`, `stringArray`,
   * `objectArray`, and the `cloudOnly` / `simpleOnly` scopes). One declaration
   * is rendered into both the simple and cloud schemas.
   */
  protected abstract val schemaProperties: SchemaBuilder.() -> Unit

  /**
   * Optional shorter description used for simple (local) model schemas. When
   * `null`, [description] is reused for both model tiers.
   */
  protected open val simpleDescription: String? = null

  /**
   * Builds the standard OpenAI function-schema envelope
   * (`{"type":"function","function":{...}}`) around this skill's
   * [skillName]. Single source of truth for the wrapper shape — every
   * skill schema is composed through this helper instead of hand-rolling
   * the `mapOf` envelope.
   */
  protected fun buildFunctionSchema(
    description: String,
    properties: Map<String, Any>,
    required: List<String>,
  ): Map<String, Any> = mapOf(
    "type" to "function",
    "function" to mapOf(
      "name" to skillName,
      "description" to description,
      "parameters" to mapOf(
        "type" to "object",
        "required" to required,
        "properties" to properties
      ),
    ),
  )

  /**
   * Builds the schema envelope from a [SchemaBuilder] DSL block instead of
   * raw `mapOf` property maps. The [properties] argument describes the
   * parameter object's fields with `string` / `integer` / ... helpers.
   *
   * When [useSimple] is true, parameters marked [ParameterLevel.CLOUD_ONLY]
   * are dropped and required is derived from the remaining parameters.
   */
  protected fun buildFunctionSchema(
    useSimple: Boolean, description: String, properties: SchemaBuilder.() -> Unit
  ): Map<String, Any> {
    val builder = SchemaBuilder()
    builder.properties()

    val visible: List<SkillParameter> =
      if (useSimple) {
        builder.schemaParameters.filter { parameter ->
          parameter.level != ParameterLevel.CLOUD_ONLY
        }
      } else builder.schemaParameters

    val required: List<String> = visible.filter { parameter ->
      parameter.required
    }.map { parameter -> parameter.name }

    val parameterMap: Map<String, Any> = visible.associate { parameter ->
      parameter.name to parameter.schema
    }
    return buildFunctionSchema(description, properties = parameterMap, required)
  }

  /**
   * Whether this skill manages its own event stream (e.g. emits
   * progress events during execution) and should NOT receive the
   * standard `tool_call_start` / `tool_call` events from the agent
   * loop. Default `false`; override to `true` for skills like
   * [DelegateSkill] that use their own event namespace.
   */
  open val manageOwnEventStream: Boolean = false

  /**
   * How many recent calls of this skill keep their result fields in
   * full. Older calls have their [historyVolatileKeys] stripped by
   * [compactHistory]. Defaults to [Int.MAX_VALUE] (no stripping).
   *
   * **Applies to OLDER history only** — the current call's result is
   * never silently stripped.
   */
  open val historyKeepCount: Int = Int.MAX_VALUE

  /**
   * Field names removed from older tool messages when [compactHistory]
   * runs. These should be redundant or oversized fields (diff payloads,
   * verbose metadata) — never the primary payload the LLM just asked for.
   */
  open val historyVolatileKeys: List<String> = emptyList()

  private var prepareHistoryCallCount: Int = 0

  /** Session call count for this skill, bumped by [recordAndCompactHistory]. */
  val callCount: Int get() = prepareHistoryCallCount

  /** Resets the session call count; called at the start of each session. */
  fun resetHistoryCount() {
    prepareHistoryCallCount = 0
  }

  /**
   * Increment the history counter, strip [historyVolatileKeys] from the
   * OLDER entries in [conversationHistory] in place, and return the
   * processed version of [currentResult] to add to history and return
   * to the LLM this turn.
   *
   * This is the only sanctioned way to do per-call history work —
   * direct calls to [compactHistory] / [prepareHistoryResult] bypass
   * the counter increment and can leave the two paths inconsistent.
   */
  fun recordAndCompactHistory(
    ownMessageIndices: List<Int>,
    currentResult: Map<String, Any>,
    conversationHistory: MutableList<Map<String, Any>>
  ): Map<String, Any> {
    prepareHistoryCallCount++
    compactHistory(prepareHistoryCallCount, ownMessageIndices, conversationHistory)
    return prepareHistoryResult(currentResult)
  }

  /**
   * Return the version of [result] to add to history and show to the
   * LLM this turn. Default: as-is — the LLM must see what the tool
   * just produced.
   *
   * Override ONLY when the current result carries data too large for
   * the LLM's view of THIS turn — e.g. [EditFileSkill] strips diff
   * payloads that would blow the response context.
   *
   * **Do not** override to strip based on [historyKeepCount] /
   * [historyVolatileKeys] — that is [compactHistory]'s job and applies
   * to OLDER messages only.
   */
  open fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> = result

  /**
   * Compact older conversation history entries to save context: strips
   * [historyVolatileKeys] from the OLDEST entries indexed by
   * [ownMessageIndices], keeping the most recent [historyKeepCount]
   * calls intact so the LLM can still refer back to them.
   */
  open fun compactHistory(
    callCount: Int,
    ownMessageIndices: List<Int>,
    conversationHistory: MutableList<Map<String, Any>>
  ) {
    if (historyKeepCount == Int.MAX_VALUE || historyVolatileKeys.isEmpty()) return
    if (ownMessageIndices.isEmpty()) return

    val dropCount: Int = (ownMessageIndices.size + 1 - historyKeepCount).coerceAtLeast(0)
    val dropEndIndex: Int = dropCount.coerceAtMost(ownMessageIndices.size)
    if (dropEndIndex == 0) return
    for (dropIndex in 0 until dropEndIndex) {
      val historyIndex: Int = ownMessageIndices[dropIndex]
      if (historyIndex in conversationHistory.indices) {
        conversationHistory[historyIndex] =
          stripVolatileKeys(conversationHistory[historyIndex])
      }
    }
  }

  private fun stripVolatileKeys(message: Map<String, Any>): Map<String, Any> {
    val encodedContent = message["content"] as? String ?: return message

    val hasVolatileKey = historyVolatileKeys.any { key ->
      key in encodedContent
    }
    if (!hasVolatileKey) return message

    val decodedMap = try {
      decodeMap(input = encodedContent)
    } catch (_: Exception) {
      return message
    }

    val filteredMap = decodedMap.filterKeys { key ->
      key !in historyVolatileKeys
    }

    val filteredContent = encodeMap(input = filteredMap)
    return message + ("content" to filteredContent)
  }
}
