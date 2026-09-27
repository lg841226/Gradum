package gradum.agent

import gradum.client.ToolCallEntry

/**
 * A tool call that has been prepared for execution with a stable
 * call identifier assigned by [ToolExecutor.prepareToolCalls].
 */
data class ProcessedToolCall(
  val callIdentifier: String,
  val callData: ToolCallEntry
)
