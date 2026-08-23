/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DelegateSkill.kt  2026-08-23 13:44:58 Changed by gwy
 */

package gradum.skill

import gradum.*
import gradum.agent.Agent

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
      "focused, isolated execution."

  override val allowedToolModes: Set<ToolMode> = setOf(ToolMode.AGENT)

  override val manageOwnEventStream: Boolean = true

  /** Sub-agent timeout in seconds, configured with a 30s grace period on the client. */
  private val subAgentTimeoutSeconds: Int = 600

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    return buildFunctionSchema(
      description = description,
      properties = mapOf(
        "task" to mapOf(
          "type" to "string",
          "description" to "The task description for the sub-agent. Be specific and include " +
            "what information to gather, what questions to answer, and how to structure the result.",
        ),
        "title" to mapOf(
          "type" to "string",
          "description" to "A short, concise title describing what the sub-agent is doing. " +
            "This is displayed to the user so they know the purpose of the sub-agent. " +
            "Keep it under 60 characters. Examples: 'Explore project structure', " +
            "'Research codebase architecture', 'Analyze dependencies'.",
        ),
      ),
      required = listOf("task"),
    )
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val task: String = validateTask(arguments) ?: return makeFailure(
      ErrorCode.INVALID_PARAMETER,
      buildXmlError(
        code = "INVALID_PARAMETER",
        message = "Missing required parameter: task.",
        fixHint = "Provide a task description in the 'task' parameter."
      )
    )
    val title: String = arguments["title"] as? String ?: ""

    val config: AgentConfiguration = context.agentConfiguration
      ?: return makeFailure(
        ErrorCode.CLIENT_ERROR,
        buildXmlError(
          code = "CLIENT_ERROR",
          message = "AgentConfiguration not available in skill context.",
          fixHint = "This is a server-side issue. The skill context must carry the agent configuration."
        )
      )
    val emitEvent: (String, Map<String, Any>) -> Unit = context.emitEvent
      ?: return makeFailure(
        ErrorCode.CLIENT_ERROR,
        buildXmlError(
          code = "CLIENT_ERROR",
          message = "emitEvent not available in skill context.",
          fixHint = "This is a server-side issue. The skill context must carry the event emitter."
        )
      )

    val enhancedTask: String = buildEnhancedTask(context, task)
    val subConfig: AgentConfiguration = buildSubAgentConfig(config, enhancedTask)

    emitStartEvent(emitEvent, config.modelName, title)
    val result: String = runSubAgent(subConfig, task, emitEvent)
    return makeSuccess(mapOf("result" to result))
  }

  /** Extracts the task string from arguments, or null if missing. */
  private fun validateTask(arguments: Map<String, Any>): String? {
    return arguments["task"] as? String
  }

  /** Builds the enhanced task by prepending the main conversation context. */
  private fun buildMainContext(context: SkillContext): String {
    return context.conversationHistory
      .filter { it["role"] as? String != "system" }
      .joinToString("\n\n") { msg ->
        val role: String = msg["role"] as? String ?: "unknown"
        val content: String = msg["content"] as? String ?: ""
        "[$role]: $content"
      }
      .take(3000)
  }

  /** Builds the full task prompt with optional main conversation context. */
  private fun buildEnhancedTask(context: SkillContext, task: String): String {
    val mainContext: String = buildMainContext(context)
    return buildString {
      if (mainContext.isNotBlank()) {
        appendLine("## Main Conversation Context")
        appendLine(mainContext)
        appendLine()
      }
      appendLine("## Task")
      append(task)
    }
  }

  /** Creates the sub-agent configuration based on the parent configuration. */
  private fun buildSubAgentConfig(config: AgentConfiguration, enhancedTask: String): AgentConfiguration {
    return config.copy(
      sessionId = null,
      maxRepeatedToolCalls = 100,
      maxRepeatedResponses = 100,
      taskDescription = enhancedTask,
      timeoutSeconds = subAgentTimeoutSeconds
    )
  }

  /** Emits the start event to notify the client that a sub-agent is running. */
  private fun emitStartEvent(
    emitEvent: (String, Map<String, Any>) -> Unit, modelName: String, title: String
  ) {
    emitEvent(
      "sub_agent:start", mapOf(
        "timeoutSeconds" to subAgentTimeoutSeconds,
        "modelName" to modelName,
        "title" to title
      )
    )
  }

  /** Creates and runs the sub-agent, then emits the end event and returns the result. */
  private fun runSubAgent(
    config: AgentConfiguration, task: String,
    emitEvent: (String, Map<String, Any>) -> Unit
  ): String {
    val subAgent = Agent(config) { type: String, data: Map<String, Any> ->
      emitEvent("sub_agent:$type", data)
    }

    subAgent.executeTask(task)

    val result: String = subAgent.getResult() ?: "(no output)"
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
