/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatEventHandlers.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.state

import com.intellij.openapi.diagnostic.Logger
import gradum.idea.PluginConfig
import gradum.idea.chat.history.ChatTranscript
import gradum.idea.chat.history.SessionMeta
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.TokenUsage
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.chat.ui.chat.errorDetailText
import gradum.idea.chat.ui.chat.friendlyErrorMessage
import gradum.idea.utils.GradumBundle
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.milliseconds

internal fun GradumChatSession.handleResponseEvent(responseData: JsonObject?) {
  if (responseData == null) return

  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex < 0 || messages[assistantIndex].isUserMessage) return

  var updatedMessage = messages[assistantIndex]

  val totalTokens: Int = responseData["totalTokens"]?.jsonPrimitive?.intOrNull ?: 0
  val responseContent: String = responseData["content"]?.jsonPrimitive?.content ?: ""

  if (responseContent.isNotEmpty()) {
    updatedMessage = updatedMessage.appendEvent(ChatEvent.Response(responseContent))
  }

  if (totalTokens > 0) {
    updatedMessage = updatedMessage.copy(
      tokenUsage = TokenUsage(
        totalTokens = totalTokens,
        promptTokens = responseData["promptTokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = responseData["completionTokens"]?.jsonPrimitive?.intOrNull ?: 0
      )
    )
  }

  messages[assistantIndex] = updatedMessage
}

internal fun GradumChatSession.handleThinkingEvent(data: JsonObject?) {
  val content: String = data?.get("content")?.jsonPrimitive?.content ?: return
  val assistantIndex: Int = messages.lastIndex

  if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage)
    messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.Thinking(content))
}

/**
 * Handles the pre-execution `tool_call_start` event. Appends a
 * *pending* tool call row (spinner) with the already-known arguments
 * so the UI can show "running this tool" before the blocking skill
 * finishes. The matching `tool_call` event replaces this placeholder
 * in place (same `toolCallId`).
 */
internal fun GradumChatSession.handleToolCallStartEvent(data: JsonObject?) {
  val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: "unknown"
  val toolCallId = data?.get("toolCallId")?.jsonPrimitive?.content ?: ""
  val toolAlias = data?.get("alias")?.jsonPrimitive?.content ?: toolName

  val callArguments: Map<String, Any> = try {
    parseArguments(data?.get("arguments")?.jsonObject)
  } catch (parseException: Exception) {
    log.warn("Failed to parse tool_call_start arguments (using empty)", parseException)
    emptyMap()
  }

  val toolCall = ToolCallInfo(
    result = "",
    success = true,
    pending = true,
    alias = toolAlias,
    toolName = toolName,
    toolCallId = toolCallId,
    arguments = callArguments
  )

  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
    try {
      messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(info = toolCall))
      sendingPhase = toolPendingPhase(toolName, toolAlias)
    } catch (appendException: Exception) {
      log.warn("Failed to append tool_call_start event", appendException)
    }
  }
}

internal fun toolPendingPhase(toolName: String, toolAlias: String): String {
  return GradumBundle.messageOrNull(key = "gradum.phase.tool.$toolName")
    ?: message("gradum.phase.tool.running", toolAlias)
}

internal fun GradumChatSession.handleToolCallEvent(data: JsonObject?) {

  val toolResultString: String = data?.get("result")?.toString() ?: ""
  val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: "unknown"
  val toolAlias: String = data?.get("alias")?.jsonPrimitive?.content ?: toolName
  val toolCallId: String = data?.get("toolCallId")?.jsonPrimitive?.content ?: ""
  val callSuccess: Boolean = data?.get("success")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

  val callArguments: Map<String, Any> = try {
    parseArguments(data?.get("arguments")?.jsonObject)
  } catch (parseException: Exception) {
    log.warn("Failed to parse tool_call arguments (using empty)", parseException)
    emptyMap()
  }

  val toolCall = ToolCallInfo(
    alias = toolAlias,
    toolName = toolName,
    success = callSuccess,
    toolCallId = toolCallId,
    result = toolResultString,
    arguments = callArguments
  )

  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
    try {
      messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(info = toolCall))
      scope?.launch {
        delay(duration = WEAVING_FADE_BUFFER_MS.milliseconds)
        sendingPhase = message("gradum.phase.weaving")
      }
    } catch (appendException: Exception) {
      log.warn("Failed to append tool_call event", appendException)
    }
  }
}

internal fun GradumChatSession.handleToolExpectMismatch(data: JsonObject?) {
  val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: "unknown"
  val expectSuccess: String = data?.get("expectSuccess")?.jsonPrimitive?.content ?: "?"
  val actualSuccess: String = data?.get("actualSuccess")?.jsonPrimitive?.content ?: "?"
  val errorMessage: String =
    "Playback assertion mismatch on tool '$toolName': expected success=$expectSuccess, " +
      "actual=$actualSuccess"

  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
    messages[assistantIndex] = messages[assistantIndex].appendEvent(
      ChatEvent.Error(errorMessage, code = "TOOL_EXPECT_MISMATCH")
    )
  }
}

internal fun GradumChatSession.handleErrorEvent(data: JsonObject?) {
  val assistantIndex: Int = messages.lastIndex
  val errorCode: String = data?.get("code")?.jsonPrimitive?.content ?: ""
  val errorToolName: String = data?.get("tool")?.jsonPrimitive?.content ?: ""
  val rawMessage: String = data?.get("message")?.jsonPrimitive?.content ?: "Unknown error"

  if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
    val updatedMessage: ChatMessage = messages[assistantIndex].updateLastError(
      friendlyMessage = friendlyErrorMessage(errorCode),
      errorDetailText(errorCode, rawMessage, tool = errorToolName)
    )
    if (updatedMessage !== messages[assistantIndex])
      messages[assistantIndex] = updatedMessage
    else
      messages[assistantIndex] = messages[assistantIndex].appendEvent(
        ChatEvent.Error(rawMessage, errorCode, tool = errorToolName)
      )
  }
}

internal fun GradumChatSession.handleSubAgentStart(data: JsonObject?) {
  subAgentState.resetAllState()
  subAgentState.isActive = true
  subAgentState.startTimestamp = System.currentTimeMillis()
  subAgentState.title = data?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
  subAgentState.modelName = data?.get("modelName")?.jsonPrimitive?.contentOrNull ?: ""

  val assistantIndex: Int = messages.lastIndex

  subAgentState.userQuery = data?.get("task")?.jsonPrimitive?.contentOrNull
    ?: messages.getOrNull(assistantIndex - 1)?.content.orEmpty()

  val timeoutSeconds: Int =
    data?.get("timeoutSeconds")
      ?.jsonPrimitive?.intOrNull
      ?: PluginConfig.SUB_AGENT_TIMEOUT_FALLBACK

  if (assistantIndex < 0 || messages[assistantIndex].isUserMessage) return

  val delegateArgs: Map<String, Any> = mapOf(
    "pending" to true,
    "toolMode" to toolMode,
    "title" to subAgentState.title,
    "timeoutSeconds" to timeoutSeconds,
    "startTimestamp" to System.currentTimeMillis()
  )

  val toolCall = ToolCallInfo(
    success = true,
    pending = true,
    alias = "Delegate",
    arguments = delegateArgs,
    toolName = "delegate_task",
    toolCallId = "delegate_task",
    result = "Sub-agent running",
    timeoutSeconds = timeoutSeconds
  )

  try {
    messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(info = toolCall))
  } catch (appendException: Exception) {
    log.warn("Failed to update delegate block on start", appendException)
  }

  subAgentTimeoutJob?.cancel(CancellationException("Gradum: cancel sub-agent timeout"))
  subAgentTimeoutJob = scope?.launch {
    delay(duration = ((timeoutSeconds * 1000L) + 30_000L).milliseconds)
    if (subAgentState.isActive) {
      log.warn("Sub-agent timed out after ${timeoutSeconds}s")
      handleSubAgentError(
        data = JsonObject(
          content = mapOf(
            "message" to JsonPrimitive(value = "Sub-agent timed out after ${timeoutSeconds}s"),
            "code" to JsonPrimitive(value = "TIMEOUT")
          )
        )
      )
    }
  }
}

internal fun GradumChatSession.handleSubAgentResponse(data: JsonObject?) {
  if (!subAgentState.isActive) return
  val content: String = data?.get("content")?.jsonPrimitive?.content ?: return
  subAgentState.streamingResponse += content
}

internal fun GradumChatSession.handleSubAgentToolCall(data: JsonObject?) {
  if (!subAgentState.isActive) return
  val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: return
  val toolResultString: String = data["result"]?.toString() ?: ""
  val alias: String = data["alias"]?.jsonPrimitive?.contentOrNull ?: toolName
  val success: Boolean = data["success"]?.jsonPrimitive?.booleanOrNull ?: true
  val toolCallId: String = data["toolCallId"]?.jsonPrimitive?.contentOrNull ?: toolName

  val callArguments: Map<String, Any> = try {
    parseArguments(data["arguments"]?.jsonObject)
  } catch (parseException: Exception) {
    log.warn("Failed to parse sub-agent tool call arguments (using empty)", parseException)
    emptyMap()
  }

  val toolCall = ToolCallInfo(
    alias = alias,
    pending = false,
    success = success,
    toolName = toolName,
    toolCallId = toolCallId,
    result = toolResultString,
    arguments = callArguments
  )

  // Deduplicate by toolCallId: replace existing entry if present, otherwise append
  val existingIndex: Int = subAgentState.toolCalls.indexOfFirst { it.toolCallId == toolCallId }
  subAgentState.toolCalls = if (existingIndex >= 0) {
    subAgentState.toolCalls.toMutableList().also {
      it[existingIndex] = toolCall
    }
  } else {
    subAgentState.toolCalls + toolCall
  }
}

/**
 * Clean up sub-agent state: cancel the timeout job, resetAllState state, and update
 * the delegate capsule to show "stopped" if the assistant message still exists.
 *
 * Called when the session is externally terminated (stop, resetAllState, cancellation)
 * while a sub-agent is still running.
 */
internal fun GradumChatSession.cleanupSubAgent() {
  val savedTitle: String = subAgentState.title
  val wasActive: Boolean = subAgentState.isActive
  val savedStartTimestamp: Long = subAgentState.startTimestamp

  subAgentTimeoutJob?.cancel(cause = CancellationException("Gradum: cancel sub-agent timeout on cleanup"))
  subAgentTimeoutJob = null
  subAgentState.resetAllState()
  subAgentState.wasInterrupted = true

  if (wasActive) {
    log.warn("Sub-agent cleaned up due to external termination")
    val assistantIndex: Int = messages.lastIndex
    if (assistantIndex >= 0 && assistantIndex < messages.size
      && !messages[assistantIndex].isUserMessage
    ) {
      val updateToolCall = ToolCallInfo(
        success = false,
        pending = false,
        alias = "Delegate",
        result = "Interrupted",
        toolName = "delegate_task",
        toolCallId = "delegate_task",
        arguments = mapOf(
          "pending" to false,
          "result" to "Interrupted",
          "title" to savedTitle,
          "startTimestamp" to savedStartTimestamp,
          "endTimestamp" to System.currentTimeMillis(),
        ),
      )
      try {
        messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(info = updateToolCall))
      } catch (appendException: Exception) {
        log.warn("Failed to update delegate capsule on sub-agent cleanup", appendException)
      }
    }
  }
}

internal fun GradumChatSession.handleSubAgentError(data: JsonObject?) {
  val errorMessage: String = data?.get("message")?.jsonPrimitive?.contentOrNull
    ?: data?.get("error")?.jsonPrimitive?.contentOrNull
    ?: "Sub-agent encountered an error"
  val errorCode: String = data?.get("code")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"

  subAgentState.isActive = false
  subAgentState.errorMessage = errorMessage
  subAgentTimeoutJob?.cancel(cause = CancellationException("Gradum: cancel sub-agent timeout on error"))
  log.warn("Sub-agent error: [$errorCode] $errorMessage")

  // Update the delegate capsule in the main chat to show the error
  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex >= 0 && assistantIndex < messages.size
    && !messages[assistantIndex].isUserMessage
  ) {
    val toolCall = ToolCallInfo(
      success = false,
      pending = false,
      alias = "Delegate",
      result = errorMessage,
      toolName = "delegate_task",
      toolCallId = "delegate_task",
      arguments = mapOf(
        "pending" to false,
        "result" to errorMessage,
        "title" to subAgentState.title,
        "startTimestamp" to subAgentState.startTimestamp,
        "endTimestamp" to System.currentTimeMillis(),
        "toolMode" to toolMode
      ),
    )
    try {
      messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(info = toolCall))
    } catch (appendException: Exception) {
      log.warn("Failed to update delegate block on error", appendException)
    }
  }
  sendingPhase = ""
}

internal fun GradumChatSession.handleSubAgentEnd(data: JsonObject?) {
  subAgentState.isActive = false
  subAgentTimeoutJob?.cancel(cause = CancellationException("Gradum: cancel sub-agent timeout on end"))

  val resultText: String = data?.get("result")?.jsonPrimitive?.content ?: "(no output)"

  val subMessages: List<ChatMessage> = buildList {
    if (subAgentState.userQuery.isNotBlank()) {
      add(ChatMessage(role = "user", content = subAgentState.userQuery))
    }
    val assistantEvents: MutableList<ChatEvent> = mutableListOf()
    subAgentState.toolCalls.forEach { toolCall: ToolCallInfo ->
      assistantEvents.add(ChatEvent.ToolCall(info = toolCall))
    }
    if (subAgentState.streamingResponse.isNotBlank()) {
      assistantEvents.add(ChatEvent.Response(content = subAgentState.streamingResponse))
    }
    add(
      ChatMessage(
        role = "assistant",
        events = assistantEvents,
        modelName = subAgentState.modelName,
        content = subAgentState.streamingResponse
      )
    )
  }

  val transcriptMarkdown: String = ChatTranscript.generateTranscript(
    messages = subMessages,
    sessionMeta = SessionMeta(
      title = subAgentState.title,
      modelName = subAgentState.modelName,
      sessionId = "sub_${System.nanoTime()}",
      createdAt = subAgentState.startTimestamp,
      updatedAt = System.currentTimeMillis()
    )
  )

  log.warn("handleSubAgentEnd transcriptMarkdown length=${transcriptMarkdown.length}")

  // Guard against stale assistantIndex: only update if the last message is still the assistant
  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex < 0 || assistantIndex >= messages.size || messages[assistantIndex].isUserMessage) {
    log.warn("Sub-agent end: assistant message no longer at last index, skipping capsule update")
    sendingPhase = message("gradum.phase.delegate.done")
    return
  }

  val delegateArgs: Map<String, Any> = mapOf(
    "pending" to false,
    "result" to resultText,
    "toolMode" to toolMode,
    "title" to subAgentState.title,
    "transcriptMarkdown" to transcriptMarkdown,
    "endTimestamp" to System.currentTimeMillis(),
    "startTimestamp" to subAgentState.startTimestamp
  )

  val toolCall = ToolCallInfo(
    success = true,
    pending = false,
    alias = "Delegate",
    result = resultText,
    arguments = delegateArgs,
    toolName = "delegate_task",
    toolCallId = "delegate_task"
  )

  try {
    messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(info = toolCall))
  } catch (appendException: Exception) {
    log.warn("Failed to update delegate block on end", appendException)
  }
  sendingPhase = message("gradum.phase.delegate.done")
}

internal fun parseArguments(jsonObject: JsonObject?): Map<String, Any> {
  if (jsonObject == null) return emptyMap()

  return try {
    jsonObject.mapValues { (_, jsonElement: JsonElement) -> convertJsonElement(jsonElement) ?: "" }
  } catch (exception: Exception) {
    Logger.getInstance("ChatEventHandlers").warn("Failed to parse tool arguments", exception)
    emptyMap()
  }
}

private fun convertJsonElement(jsonElement: JsonElement): Any? {
  return when (jsonElement) {
    is JsonArray -> jsonElement.map { convertJsonElement(it) }
    is JsonObject -> jsonElement.mapValues { (_, value: JsonElement) ->
      convertJsonElement(value)
    }

    is JsonPrimitive -> {
      when {
        jsonElement.isString -> jsonElement.content
        jsonElement.intOrNull != null -> jsonElement.int
        jsonElement.longOrNull != null -> jsonElement.long
        jsonElement.doubleOrNull != null -> jsonElement.double
        jsonElement.booleanOrNull != null -> jsonElement.boolean
        else -> jsonElement.content
      }
    }

    is JsonNull -> null
  }
}
