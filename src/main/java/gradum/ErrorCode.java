/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ErrorCode.java  2026-06-22 20:11:15 Changed by gwy
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
    CLIENT_ERROR("CLIENT_ERROR");

    private final String code;

    ErrorCode(String code) { this.code = code; }

    public String getCode() { return code; }
}
