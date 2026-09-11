/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DelegateSkill.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.*
import gradum.agent.Agent
import org.slf4j.LoggerFactory
import java.util.*

private val delegateLog = LoggerFactory.getLogger("DelegateSkill")

/**
 * Delegates a focused sub-task to a sub-agent.
 *
 * The sub-agent runs independently with its own conversation history,
 * using a restricted tool set (read, search, explore, run command).
 * It does NOT have access to write, edit, or delegate tools, and
 * the system prompt is augmented with the task description.
 *
 * Events from the sub-agent are forwarded with a `sub_agent:` prefix
 * so the client can stream them in real time. The final result is
 * returned only after the sub-agent session ends.
 */
class DelegateSkill : Skill() {
  override val skillName: String = "delegate_task"
  override val alias: String = "Delegate"
  override val description: String =
    "Delegate a focused sub-task to a sub-agent. " +
      "The sub-agent runs independently to investigate, research, or gather information, " +
      "then returns a structured result. Use this for large tasks that would benefit from " +
      "focused, isolated execution. The task description must be at least 120 characters " +
      "to ensure the sub-agent has enough context to work with."

  override val allowedToolModes: Set<ToolMode> = setOf(ToolMode.AGENT, ToolMode.EDIT, ToolMode.READ_ONLY)

  override val manageOwnEventStream: Boolean = true

  private val subAgentTimeoutSeconds: Int = GradumConfig.DELEGATE_TIMEOUT_SECONDS

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "task",
      description = "The task description for the sub-agent. Must be at least 120 characters. " +
        "Be specific and include what information to gather, what questions to answer, " +
        "and how to structure the result. IMPORTANT: Do not pass the user's message verbatim — " +
        "rewrite the task in your own words with additional context.",
      required = true,
    )
    string(
      name = "title",
      description = "A short, concise title describing what the sub-agent is doing. " +
        "This is displayed to the user so they know the purpose of the sub-agent. " +
        "Keep it under 20 characters. ",
    )
  }

  companion object {
    private val MIN_TASK_LENGTH: Int = GradumConfig.MIN_TASK_LENGTH
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val task: String = validateTask(arguments, context.conversationHistory())
      ?: return makeFailure(
        ErrorCode.INVALID_PARAMETER,
        buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Invalid task parameter. The task must be at least $MIN_TASK_LENGTH characters " +
            "and must not be identical to the user's original message. Rewrite the task in your own words, " +
            "adding specific context about what to investigate, what tools to use, and how to structure the result.",
          fixHint = "Provide a detailed, rewritten task description (at least $MIN_TASK_LENGTH characters) " +
            "that adds context and structure. Do not pass the user's message verbatim."
        )
      )
    val title: String = arguments["title"] as? String ?: ""

    delegateLog.info(
      "Validation passed: taskLength={}, title='{}', taskFirst80='{}'",
      task.length, title, task.take(n = 80).replace('\n', ' ')
    )

    val config: AgentConfiguration = context.agentConfiguration
      ?: return makeFailure(
        code = ErrorCode.CLIENT_ERROR,
        message = buildXmlError(
          code = "CLIENT_ERROR",
          message = "AgentConfiguration not available in skill context.",
          fixHint = "This is a server-side issue. The skill context must carry the agent configuration."
        )
      )
    val emitEvent: (String, Map<String, Any>) -> Unit = context.emitEvent
      ?: return makeFailure(
        code = ErrorCode.CLIENT_ERROR,
        message = buildXmlError(
          code = "CLIENT_ERROR",
          message = "emitEvent not available in skill context.",
          fixHint = "This is a server-side issue. The skill context must carry the event emitter."
        )
      )

    val subConfig: AgentConfiguration = buildSubAgentConfig(config, task)

    val registerChild: ((String, Agent) -> Unit)? = context.registerChildSession
    val unregisterChild: ((String) -> Unit)? = context.unregisterChildSession

    emitStartEvent(emitEvent, config.modelName, title, task)
    return try {
      val result: String = runSubAgent(subConfig, task, emitEvent, registerChild, unregisterChild)
      makeSuccess { string("result", result) }
    } catch (exception: Exception) {
      delegateLog.error("Sub-agent execution failed", exception)
      makeFailure(ErrorCode.CLIENT_ERROR, "Sub-agent failed: ${exception.message}")
    }
  }

  /** Extracts the task string from arguments, or null if missing/too short/duplicate of user input. */
  private fun validateTask(
    arguments: Map<String, Any>, conversationHistory: List<Map<String, Any>>
  ): String? {
    val task: String = arguments["task"] as? String ?: return null
    if (task.length < MIN_TASK_LENGTH) {
      delegateLog.info("Rejected: task too short (length={}, min={})", task.length, MIN_TASK_LENGTH)
      return null
    }

    val lastUserInput: String? = conversationHistory.lastOrNull { userMessage ->
      userMessage["role"] == "user"
    }?.get("content") as? String

    lastUserInput?.let { input ->
      val taskTrim = task.trim()
      val inputTrim = input.trim()
      if (taskTrim == inputTrim) {
        delegateLog.info("Rejected: task identical to last user message")
        return null
      }
    }
    return task
  }

  /** Creates the sub-agent configuration based on the parent configuration. */
  private fun buildSubAgentConfig(config: AgentConfiguration, task: String): AgentConfiguration {
    return config.copy(
      sessionId = null,
      taskDescription = task,
      maxRepeatedToolCalls = 256,
      maxRepeatedResponses = 256,
      timeoutSeconds = subAgentTimeoutSeconds
    )
  }

  /** Emits the start event to notify the client that a sub-agent is running. */
  private fun emitStartEvent(
    emitEvent: (String, Map<String, Any>) -> Unit, modelName: String, title: String,
    task: String
  ) {
    emitEvent(
      "sub_agent:start", mapOf(
        "timeoutSeconds" to subAgentTimeoutSeconds,
        "task" to task,
        "title" to title,
        "modelName" to modelName
      )
    )
  }

  /** Creates and runs the sub-agent, then emits the end event and returns the result. */
  private fun runSubAgent(
    config: AgentConfiguration, task: String, emitEvent: (String, Map<String, Any>) -> Unit,
    registerChild: ((String, Agent) -> Unit)?, unregisterChild: ((String) -> Unit)?
  ): String {
    val subAgentId = "sub_${UUID.randomUUID()}"
    val subAgent = Agent(
      configuration = config,
      emitEvent = { type: String, data: Map<String, Any> ->
        emitEvent("sub_agent:$type", data)
      },
      registerChildSession = registerChild,
      unregisterChildSession = unregisterChild,
    )

    registerChild?.invoke(subAgentId, subAgent)
    try {
      subAgent.executeTask(userInput = task)
    } catch (exception: Exception) {
      delegateLog.error("Sub-agent execution failed", exception)
      emitEvent(
        "sub_agent:session_end", mapOf(
          "result" to "Sub-agent execution failed: ${exception.message}",
          "conversation" to emptyList<Map<String, Any>>()
        )
      )
      throw exception
    } catch (exception: Error) {
      delegateLog.error("Sub-agent critical error", exception)
      emitEvent(
        "sub_agent:session_end", mapOf(
          "result" to "Sub-agent critical error: ${exception.message}",
          "conversation" to emptyList<Map<String, Any>>()
        )
      )
      throw exception
    } finally {
      unregisterChild?.invoke(subAgentId)
    }

    val result: String =
      if (subAgent.isSessionAborted()) {
        val reason: String = subAgent.getSessionEndReason() ?: "unknown reason"
        "Sub-agent session aborted, $reason"
      } else {
        subAgent.getResult() ?: "no output"
      }

    val conversationHistory: List<Map<String, Any>> = subAgent.getConversationHistory()

    emitEvent(
      "sub_agent:session_end", mapOf(
        "result" to result,
        "conversation" to conversationHistory
      )
    )

    return result
  }
}
