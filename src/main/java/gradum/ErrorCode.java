/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ErrorCode.java  2026-07-14 21:27:12 Changed by gwy
 */

package gradum;

public enum ErrorCode {
    INVALID_PARAMETER("INVALID_PARAMETER"),
    IO_ERROR("IO_ERROR"),
    FILE_NOT_FOUND("FILE_NOT_FOUND"),
    CODE_NOT_FOUND("CODE_NOT_FOUND"),
    MULTIPLE_MATCHES("MULTIPLE_MATCHES"),
    EMPTY_RESULT("EMPTY_RESULT"),
    COMMAND_BLOCKED("COMMAND_BLOCKED"),
    TIMEOUT("TIMEOUT"),
    ALREADY_INITIALIZED("ALREADY_INITIALIZED"),
    NOT_INITIALIZED("NOT_INITIALIZED"),
    ALL_COMPLETED("ALL_COMPLETED"),
    FILE_TOO_LARGE("FILE_TOO_LARGE"),
    CLIENT_ERROR("CLIENT_ERROR"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    /**
     * Returned by the agent's mode gate when a tool call's functionName is
     * not in the target skill's [Skill.allowedToolModes] for the current
     * [ToolMode]. Distinct from COMMAND_BLOCKED (which is a finer-grained
     * filter on the command text itself) so the plugin can surface a
     * different message: "this tool is not available in read-only mode"
     * vs. "this shell command would mutate the filesystem".
     */
    TOOL_NOT_PERMITTED("TOOL_NOT_PERMITTED"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION"),
    /**
     * Returned when the debug tool-call playback mode receives scenario
     * XML that cannot be parsed into a valid {@code <tls>} document.
     * Distinct from the skill-level INVALID_PARAMETER so the plugin can
     * surface "the scenario file is malformed" instead of blaming a tool.
     */
    INVALID_SCENARIO_XML("INVALID_SCENARIO_XML");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
