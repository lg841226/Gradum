package gradum.agent

/**
 * Wire identifiers for the NDJSON event stream emitted to connected clients.
 *
 * Reference [wireName] instead of raw string literals so a renamed event
 * fails to compile at every call site at once, instead of silently
 * desyncing the server and whatever consumes the stream.
 *
 * SUB_AGENT_EVENT_PREFIX is the prefix for dynamically named sub-agent
 * events, e.g. `sub_agent:response`.
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

const val SUB_AGENT_EVENT_PREFIX: String = "sub_agent:"

/** Payload key carrying an [ErrorScope.wireName] on an `ERROR` event. */
const val ERROR_SCOPE_KEY: String = "scope"

/**
 * Where an `ERROR` event originated, so consumers can tell a recoverable
 * per-tool failure apart from a turn-fatal one.
 *
 * [TOOL]: a single skill invocation failed. The agent loop continues and the
 * model may retry, so consumers must not escalate it to a failed turn.
 *
 * [TURN]: the turn itself could not proceed (e.g. the LLM client errored); no
 * final answer will be produced.
 */
enum class ErrorScope(val wireName: String) {
  TOOL("tool"),
  TURN("turn"),
}
