# Changelog

All notable changes to Gradum are tracked here, roughly following
[Keep a Changelog](https://keepachangelog.com/); see the README for
how to download the packaged distributions.

## [Unreleased]

## [1.0.5] - 2026-10-31

Sessions become server-authoritative, the timeline grows a profiler, and the
MCP integration is decoupled behind a provider interface.

**Server-authoritative session state**

- The server now owns the session projection: the chat view renders from the
  session-state sidecar, multi-turn assistant messages fold into a single
  bubble, and live extras stream alongside sequence-numbered events with
  dropped-event reporting.
- Rewind, delete, and merge all happen on the server before the plugin view
  catches up; sessions cold-load from the sidecar only.
- Message history is consolidated into a strongly typed `MessageRecord` model
  with a single representation held per chat message.

**Timeline & session analysis**

- New per-turn timeline visualization: tool execution status indicators, a
  thinking clock, duration text, adjustable zoom (five precision levels),
  and red-hatched fills for failed tool calls.

**Thinking mode rework**

- Model capabilities are probed (Ollama `/api/show`, LM Studio responses
  availability) and thinking levels are gated on what the model actually
  supports; probe failures are no longer cached permanently.
- LM Studio is a first-class provider wired to the `/v1/responses` API with
  reasoning tiers; Ollama cloud models and parameter sizes are identified
  from official metadata.
- JSON-RPC 2.0 string-type ids are adapted end to end.

**MCP & architecture**

- MCP tool access is decoupled from the agent core behind the new
  `ExternalToolProvider` interface plus registry: the core no longer imports
  the MCP module, and JSON-RPC transport primitives moved to a standalone
  `gradum.jsonrpc` package.
- MCP connection management reworked with typed startup failure reporting.

**Agent & tools**

- New background task management with stoppable workers and job outcomes.
- File errors can be attached to a chat message in the `gradum` namespace
  attachment format.
- Code vision features for Java/Kotlin functions: explain and update docs.
- New YOLO mode skips permission checks for trusted environments.

**Platform & docs**

- Traditional Chinese (zh_TW) i18n support.
- Search skill configuration and an improved startup banner.
- Event stream debug dump with a visual monitoring tool.

## [1.0.2] - 2026-09-25

Command authorization and security-model rework.

**Command authorization (run_cmd)**

- `readOnlyAllowedExecutables` removed; the READ_ONLY tier no longer uses a
  whitelist. Destructive commands on paths outside the project root now pause
  and ask the user for explicit authorization (once / always / reject) instead
  of being auto-blocked.
- `CommandVerdict` gains `NeedsApproval`, classifying `rm`, recursive `chmod`,
  and `dd` against paths outside the project root. Protected system paths and
  blocked executables stay hard-blocked and cannot be authorized away.
- Session-scoped `authorizedCommandCategories` remembers per-category approvals
  so the same class of command is not re-asked within a session.

**Skills & prompts**

- `write_file` merged from `edit_file` and `save_file`; file-creation now renders
  added-line counts.
- System prompts updated so models call the tool and await user authorization
  rather than self-refusing out-of-project destructive commands.

## [1.0.1-experimental] - 2026-09-11

Packaging, distribution, and server ergonomics got their first real polish this round.

**Packaged distribution**

- The macOS app now ships with an official Gradum icon, alongside vendor and
  copyright attribution; the bundle is ad-hoc signed so Finder shows it like a
  normal app instead of a quarantine badge.
- Double-clicking the app opens a live Terminal window instead of quietly
  sitting in the background, which makes first-run behavior and logs obvious.
- The bundled JVM runtime is slimmed down with `jlink`, keeping only the JDK
  modules the server actually uses. `jdeps` figures that out automatically on
  every build. The distributed app dropped from roughly 183 MB to about 69 MB,
  or roughly 49 MB once zipped, with no behavior lost.

**Server stability**

- When the configured port is already taken, startup now prints a friendly
  hint pointing you at the fix, instead of a raw `BindException` stack trace.

**Configuration & logging**

- Settings validation errors render as a compact tree, one line per issue,
  with the offending value called out so they are easy to scan even in narrow
  terminal windows.
- Error-level log lines now use bright text on a red background, matching the
  rest of the raised-surface color scheme.
