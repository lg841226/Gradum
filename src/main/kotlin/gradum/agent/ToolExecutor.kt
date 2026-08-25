/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolExecutor.kt  2026-08-25 22:47:18 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration
import gradum.ErrorCode
import gradum.SkillResult
import gradum.ToolMode
import gradum.client.ToolCallEntry
import gradum.skill.Skill
import gradum.skill.SkillContext
import gradum.skill.SkillRegistry
import gradum.skill.getTodoManagerInstance
import gradum.utils.CommandVerdict
import gradum.utils.JsonUtil
import gradum.utils.classifyCommand
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ToolExecutor")

/**
 * Executes tool calls on behalf of an [Agent] session.
 *
 * Responsibilities:
 * - Assigning stable call identifiers to raw LLM tool call entries.
 * - Running the full execution pipeline (permission checks, skill
 *   dispatch, result recording).
 * - Emitting `tool_call_start` and `tool_call` events.
 * - Detecting tool-runaway patterns (identical repeated calls).
 */
class ToolExecutor(
  private val skillContext: SkillContext,
  private val sessionManager: SessionManager,
  private val configuration: AgentConfiguration,
  private val conversationHistory: ConversationHistory,
  private val emitEvent: (eventType: String, eventData: Map<String, Any>) -> Unit
) {

  private var toolCallCounter: Int = 0
  private var lastToolCallKey: String? = null
  private var repeatedToolCallCount: Int = 0

  /** Resets counters for a new execution. */
  fun reset() {
    toolCallCounter = 0
    lastToolCallKey = null
    repeatedToolCallCount = 0
  }

  /** Assigns stable call identifiers to raw tool call entries. */
  fun prepareToolCalls(rawCalls: List<ToolCallEntry>): List<ProcessedToolCall> {
    return rawCalls.map { call: ToolCallEntry ->
      val existingId: String = call.callIdentifier
      if (existingId.isNotBlank()) {
        ProcessedToolCall(callIdentifier = existingId, callData = call)
      } else {
        toolCallCounter++
        ProcessedToolCall(callIdentifier = "call_$toolCallCounter", callData = call)
      }
    }
  }

  /**
   * Executes a single tool call through the full pipeline:
   * 1. Argument conversion and project-root injection
   * 2. Session abort check
   * 3. Tool-runaway detection
   * 4. Read-only mode command blocking
   * 5. Skill dispatch
   * 6. Result event emission
   */
  fun executeSingleTool(
    processedCall: ProcessedToolCall, isLastToolCall: Boolean = true
  ): Map<String, Any> {
    val functionName: String = processedCall.callData.functionName
    val rawArguments: Map<String, JsonElement> = processedCall.callData.functionArguments

    val convertedArguments: MutableMap<String, Any> = mutableMapOf()
    for ((key: String, value: JsonElement) in rawArguments) {
      val converted: Any? = JsonUtil.fromJsonElement(value)
      if (converted != null) {
        convertedArguments[key] = converted
      }
    }

    convertedArguments.remove(key = "projectRoot")
    convertedArguments.remove(key = "project_root")
    convertedArguments["projectRoot"] = configuration.projectRoot

    logger.info("Skill: $functionName")
    if (logger.isDebugEnabled) logger.debug("Args: ${truncateToolArguments(convertedArguments)}")

    val skillInstance: Skill? = SkillRegistry.getSkill(skillName = functionName)
    val toolAlias: String = skillInstance?.alias ?: functionName

    emitToolCallStart(
      toolAlias, toolCallId = processedCall.callIdentifier, functionName, skillInstance, convertedArguments
    )

    val executionResult: Map<String, Any>

    if (sessionManager.isAborted) {
      executionResult = mapOf(
        "success" to false,
        "error" to mapOf(
          "code" to ErrorCode.CLIENT_ERROR.name,
          "message" to "Aborted by user before execution started"
        ),
      )
      emitToolResult(functionName, skillInstance, isLastToolCall, processedCall, executionResult, convertedArguments)
      logToolResult(executionResult)
      return executionResult
    }

    if (checkToolRunaway(toolName = functionName, toolArguments = convertedArguments)) {
      logger.error("Result: TOOL_RUNAWAY repeated call ($repeatedToolCallCount times), aborting")
      sessionManager.emitRevoked(
        reason = "tool_runaway",
        details = mapOf(
          "tool" to functionName,
          "arguments" to convertedArguments,
          "repeatedCount" to repeatedToolCallCount
        )
      )
      executionResult = mapOf(
        "success" to false,
        "error" to mapOf(
          "code" to ErrorCode.CLIENT_ERROR.name,
          "message" to "Tool runaway: repeated call $repeatedToolCallCount times, aborting"
        ),
      )
      emitToolResult(functionName, skillInstance, isLastToolCall, processedCall, executionResult, convertedArguments)
      logToolResult(executionResult)
      sessionManager.abort(reason = "tool_runaway")
      return executionResult
    }

    if (configuration.toolMode == ToolMode.READ_ONLY && functionName == "run_cmd") {
      val commandText: String = convertedArguments["command"] as? String ?: ""
      val verdict: CommandVerdict = classifyCommand(commandText, configuration.toolMode)
      if (verdict is CommandVerdict.Blocked) {
        logger.error("Result: COMMAND_BLOCKED — ${verdict.description} (rule: ${verdict.ruleName})")
        executionResult = mapOf(
          "success" to false,
          "error" to mapOf(
            "rule" to verdict.ruleName,
            "message" to verdict.description,
            "code" to ErrorCode.COMMAND_BLOCKED.name
          ),
        )
        emitToolResult(
          functionName, skillInstance, isLastToolCall, processedCall, executionResult, convertedArguments
        )
        logToolResult(executionResult)
        return executionResult
      }
    }

    executionResult = executeSkill(skillInstance, functionName, convertedArguments)

    emitToolResult(
      functionName, skillInstance, isLastToolCall, processedCall, executionResult, convertedArguments
    )
    logToolResult(executionResult)

    return executionResult
  }

  /**
   * Dispatches a tool call to [SkillRegistry] and returns the result.
   * Handles SKILL_NOT_FOUND and TOOL_NOT_PERMITTED errors.
   */
  fun executeSkill(
    skillInstance: Skill?, functionName: String, convertedArguments: Map<String, Any>
  ): Map<String, Any> {
    if (skillInstance == null) {
      logger.error("Result: SKILL_NOT_FOUND '$functionName' not registered")
      return mapOf(
        "success" to false,
        "error" to mapOf("code" to "SKILL_NOT_FOUND", "message" to "Skill '$functionName' not found"),
      )
    }
    if (!skillInstance.allows(configuration.toolMode)) {
      val allowedNames: List<String> = skillInstance.allowedToolModes.map { it.name }
      logger.error(
        "Result: TOOL_NOT_PERMITTED — '${functionName}' not allowed in ${configuration.toolMode} " +
          "(allowed: ${allowedNames.joinToString(separator = ", ")})"
      )
      return mapOf(
        "success" to false,
        "error" to mapOf(
          "code" to ErrorCode.TOOL_NOT_PERMITTED.name,
          "message" to "Tool '${functionName}' is not permitted in ${configuration.toolMode} mode " +
            "(allowed: ${allowedNames.joinToString(separator = ", ")})",
          "toolMode" to configuration.toolMode.name,
          "allowedModes" to allowedNames,
        ),
      )
    }
    return when (val result: SkillResult = skillInstance.execute(convertedArguments, skillContext)) {
      is SkillResult.Success -> mapOf(
        "success" to true
      ).plus(map = result.data)

      is SkillResult.Failure -> mapOf(
        "success" to false,
        "error" to mapOf("code" to result.code, "message" to result.message)
      )
    }
  }

  /** Checks whether the current tool call is a runaway (identical signature). */
  fun checkToolRunaway(toolName: String, toolArguments: Map<String, Any>): Boolean {
    val callSignature =
      "$toolName|${
        toolArguments.entries.sortedBy { it.key }.joinToString(separator = ",") {
          "${it.key}=${it.value}"
        }
      }"
    repeatedToolCallCount =
      if (callSignature == lastToolCallKey) repeatedToolCallCount + 1
      else 1
    lastToolCallKey = callSignature
    return repeatedToolCallCount >= configuration.maxRepeatedToolCalls
  }

  /**
   * Emits a `tool_call_start` event. Skills that manage their own event
   * stream (e.g. DelegateSkill) are skipped.
   */
  private fun emitToolCallStart(
    toolAlias: String,
    toolCallId: String,
    functionName: String,
    skillInstance: Skill? = null,
    convertedArguments: Map<String, Any>
  ) {
    if (skillInstance?.manageOwnEventStream == true) return
    emitEvent(
      "tool_call_start",
      mapOf(
        "alias" to toolAlias,
        "tool" to functionName,
        "toolCallId" to toolCallId,
        "arguments" to convertedArguments
      )
    )
  }

  /**
   * Emits the post-execution `tool_call` event, appends the result to
   * conversation history, and handles history compaction.
   */
  private fun emitToolResult(
    functionName: String,
    skillInstance: Skill?,
    isLastToolCall: Boolean = true,
    processedCall: ProcessedToolCall,
    executionResult: Map<String, Any>,
    convertedArguments: Map<String, Any>
  ) {
    val toolAlias: String = skillInstance?.alias ?: functionName
    val ownMessageIndices: List<Int> = conversationHistory.toolMessageIndices(toolAlias)
    val historyResult: Map<String, Any> = conversationHistory.recordAndCompact(
      skillInstance, currentResult = executionResult, ownMessageIndices
    )
    val callSuccess: Boolean = executionResult["success"] as? Boolean ?: false

    val skipEvent: Boolean = skillInstance?.manageOwnEventStream == true

    if (!skipEvent) {
      emitEvent(
        "tool_call",
        mapOf(
          "tool" to functionName,
          "alias" to toolAlias,
          "arguments" to convertedArguments,
          "toolCallId" to processedCall.callIdentifier,
          "success" to callSuccess,
          "result" to executionResult
        )
      )

      if (!callSuccess) {
        @Suppress("UNCHECKED_CAST")
        val errorInfo: Map<String, Any> = executionResult["error"] as? Map<String, Any> ?: emptyMap()
        emitEvent(
          "error",
          mapOf(
            "tool" to functionName,
            "toolCallId" to processedCall.callIdentifier,
            "code" to (errorInfo["code"] ?: "EXECUTION_ERROR"),
            "message" to (errorInfo["message"] ?: "Unknown error")
          )
        )
      }
    }

    val resultString: String = JsonUtil.encodeMap(input = historyResult)
    val todoReminder: String? =
      if (isLastToolCall) getTodoManagerInstance().getTaskReminder() else null
    val finalResult: String = todoReminder?.let { "$resultString\n\n$it" } ?: resultString

    conversationHistory.addToolMessage(
      toolAlias, content = finalResult, toolCallId = processedCall.callIdentifier, configuration.provider
    )
  }

  private fun logToolResult(executionResult: Map<String, Any>) {
    val callSuccess = executionResult["success"] as? Boolean ?: false
    if (callSuccess) {
      logger.info("Result: success")
    } else {
      @Suppress("UNCHECKED_CAST")
      val errorInfo = executionResult["error"] as? Map<String, Any> ?: emptyMap()
      val errorCode = errorInfo["code"] ?: "UNKNOWN"
      val errorMessage = errorInfo["message"] ?: "No message"
      logger.error("  Result: failed [$errorCode] $errorMessage")
    }
  }

  private fun truncateToolArguments(toolArguments: Map<String, Any>): String {
    val maxValueLength = 512
    val serialized: String = JsonUtil.encodeMap(input = toolArguments, prettyPrint = true)
    return if (serialized.length <= maxValueLength) serialized
    else serialized.take(n = maxValueLength) + "(truncated, ${serialized.length} chars total)"
  }
}
