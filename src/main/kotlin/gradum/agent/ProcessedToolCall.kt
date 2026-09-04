/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProcessedToolCall.kt  2026-08-31 19:21:55 Changed by gwy
 */

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
