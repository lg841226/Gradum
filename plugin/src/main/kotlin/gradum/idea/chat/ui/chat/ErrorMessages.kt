/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ErrorMessages.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.idea.chat.ui.chat

import gradum.idea.chat.model.ErrorCode
import gradum.idea.bundle.GradumBundle.message

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
    ErrorCode.CLIENT_ERROR.code -> message("gradum.error.client")
    ErrorCode.FILE_NOT_FOUND.code -> message("gradum.error.file.not.found")
    ErrorCode.CODE_NOT_FOUND.code -> message("gradum.error.code.not.found")
    ErrorCode.MULTIPLE_MATCHES.code -> message("gradum.error.multiple.matches")
    ErrorCode.INVALID_PARAMETER.code -> message("gradum.error.invalid.parameter")
    ErrorCode.IO_ERROR.code -> message("gradum.error.io")
    ErrorCode.TIMEOUT.code -> message("gradum.error.timeout")
    ErrorCode.COMMAND_BLOCKED.code -> message("gradum.error.command.blocked")
    ErrorCode.PERMISSION_DENIED.code -> message("gradum.error.permission.denied")
    ErrorCode.FILE_TOO_LARGE.code -> message("gradum.error.file.too.large")
    ErrorCode.EMPTY_RESULT.code -> message("gradum.error.empty.result")
    ErrorCode.NOT_INITIALIZED.code -> message("gradum.error.not.initialized")
    ErrorCode.ALREADY_INITIALIZED.code -> message("gradum.error.already.initialized")
    ErrorCode.ALL_COMPLETED.code -> message("gradum.error.all.completed")
    ErrorCode.INTERRUPTED.code -> message("gradum.error.interrupted")
    ErrorCode.TOOL_NOT_PERMITTED.code -> message("gradum.error.tool.not.permitted")
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
        if (isNotEmpty()) append("\n"); append("Tool: $tool")
    if (isNotEmpty()) append("\n")

    append(message)
}
