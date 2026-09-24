/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumEventType.kt  2026-09-17 Changed by gwy
 */

package gradum.agent

/**
 * Wire identifiers for the NDJSON event stream emitted to connected clients.
 *
 * Reference [wireName] instead of raw string literals so a renamed event
 * fails to compile at every call site at once, instead of silently
 * desyncing the server and whatever consumes the stream.
 */
enum class GradumEventType(val wireName: String) {
  SESSION_START("session_start"),
  SESSION_END("session_end"),
  RESPONSE("response"),
  THINKING("thinking"),
  ERROR("error"),
  TOOL_CALL("tool_call"),
  TOOL_CALL_START("tool_call_start"),
  MISSION_REVOKED("mission_revoked"),
  GUARDRAIL("guardrail"),
  PLAYBACK_START("playback_start"),
  PLAYBACK_END("playback_end"),
  SUB_AGENT_START("sub_agent:start"),
  SUB_AGENT_SESSION_END("sub_agent:session_end"),
  ASK_INTERACTION("ask_interaction"),
}

/**
 * Prefix for dynamically named sub-agent events, e.g. `sub_agent:response`.
 */
const val SUB_AGENT_EVENT_PREFIX: String = "sub_agent:"