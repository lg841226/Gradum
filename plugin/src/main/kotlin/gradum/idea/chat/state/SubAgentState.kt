/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SubAgentState.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.chat.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gradum.idea.chat.model.ToolCallInfo

/**
 * Independent state for the currently running (or completed) sub-agent
 * session. Kept separate from the main conversation's ToolCall blocks
 * so the sub-agent data flows through its own dedicated channel instead
 * of being stuffed into a Map<String, Any> in ToolCall arguments.
 *
 * The Delegate skill's event handlers in [GradumChatSession] write to
 * this state directly. The UI reads it for the sub-chat view without
 * needing to dig through render blocks.
 */
class SubAgentState {
  /** Whether a sub-agent is currently running. */
  var isActive: Boolean by mutableStateOf(value = false)

  /** Accumulated streaming response text from the sub-agent. */
  var streamingResponse: String by mutableStateOf(value = "")

  /** Model name used by the sub-agent. */
  var modelName: String by mutableStateOf(value = "")

  /** Tool calls made by the sub-agent during its execution. */
  var toolCalls: List<ToolCallInfo> by mutableStateOf(value = emptyList())

  /** Error message from the sub-agent, non-empty when an error occurred. */
  var errorMessage: String by mutableStateOf(value = "")

  /** Short title describing what the sub-agent is doing. */
  var title: String by mutableStateOf(value = "")

  /** The user's original query that triggered this sub-agent. */
  var userQuery: String by mutableStateOf(value = "")

  /** Timestamp (epoch millis) when the sub-agent started. */
  var startTimestamp: Long by mutableStateOf(value = 0L)

  /** Whether the sub-agent was externally interrupted (stop/resetAllState/cancel). */
  var wasInterrupted: Boolean by mutableStateOf(value = false)

  /** Resets all state to initial values. */
  fun resetAllState() {
    title = ""
    modelName = ""
    userQuery = ""
    errorMessage = ""
    streamingResponse = ""
    isActive = false
    startTimestamp = 0L
    wasInterrupted = false
    toolCalls = emptyList()
  }
}
