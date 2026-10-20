package gradum.idea.chat.ui.chat

import gradum.idea.chat.model.ChatErrorCode
import gradum.idea.utils.GradumBundle.message

/**
 * Maps server-side error codes to user-friendly display messages.
 *
 * The goal is to explain what went wrong without exposing technical
 * internals. Messages are intentionally non-technical so that end
 * users can understand the cause of failure at a glance.
 *
 * @param code The error code string sent by the server (e.g. "CLIENT_ERROR").
 * @return A human-readable description suitable for the error popup.
 */
fun friendlyErrorMessage(code: String): String = when (code) {
  ChatErrorCode.CLIENT_ERROR -> message("gradum.error.client")
  ChatErrorCode.LLM_UPSTREAM_ERROR -> message("gradum.error.llm.upstream")
  ChatErrorCode.LLM_AUTH_ERROR -> message("gradum.error.llm.auth")
  "FILE_NOT_FOUND" -> message("gradum.error.file.not.found")
  "CODE_NOT_FOUND" -> message("gradum.error.code.not.found")
  "MULTIPLE_MATCHES" -> message("gradum.error.multiple.matches")
  "INVALID_PARAMETER" -> message("gradum.error.invalid.parameter")
  "IO_ERROR" -> message("gradum.error.io")
  "TIMEOUT" -> message("gradum.error.timeout")
  "COMMAND_BLOCKED" -> message("gradum.error.command.blocked")
  "PERMISSION_DENIED" -> message("gradum.error.permission.denied")
  "FILE_TOO_LARGE" -> message("gradum.error.file.too.large")
  "EMPTY_RESULT" -> message("gradum.error.empty.result")
  "NOT_INITIALIZED" -> message("gradum.error.not.initialized")
  "ALREADY_INITIALIZED" -> message("gradum.error.already.initialized")
  "ALL_COMPLETED" -> message("gradum.error.all.completed")
  ChatErrorCode.INTERRUPTED -> message("gradum.error.interrupted")
  "TOOL_NOT_PERMITTED" -> message("gradum.error.tool.not.permitted")
  else -> message("gradum.error.unexpected")
}

/**
 * Builds the full technical detail string for clipboard copying.
 *
 * Includes the error code, raw message, and tool name (if any)
 * so that developers or support staff can diagnose the issue.
 */
fun errorDetailText(code: String, message: String, tool: String): String = buildString {
  if (code.isNotBlank()) append("Code: $code")
  if (tool.isNotBlank())
    if (isNotEmpty()) {
      append("\n")
      append("Tool: $tool")
    }

  if (isNotEmpty()) append("\n")
  append(message)
}
