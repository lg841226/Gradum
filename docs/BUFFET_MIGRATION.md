# Gradum Buffet: Server-Authoritative State Migration

> Version: **0.1** · Branch: `feature/@gradum-gwy-buffet-projection` · Opened: **2026-10-17** · Status: **Complete — Step 3.5 deferred**

"Buffet" is the working name for moving session-state authority from the IDE
plugin back to the server. The plugin should *serve* state (render it, ask the
user, forward input), not *derive* it. Right now it does a lot of deriving, and
two writers of the same state drift apart.

This document is the step-by-step plan and the alignment point for status. Each
step is independently shippable, keeps the public API and `settings.json`
schema backward compatible, and is accepted only when the existing test suite
stays green.

---

## 1. Problem

Three kinds of responsibility overlap between the plugin and the server:

1. **State reconstruction.** The plugin rebuilds server state in memory from the
   event stream (`thinking` / `response` / `tool_call` / ...). The server's
   `Agent` / `LLMClient` is the real owner; the plugin keeps a second copy that
   can silently diverge when an event is lost.
2. **Duplicated format and semantics.** The same conversation-transcript format,
   title rule, and error-code list are implemented twice, once per module. A
   change on one side that misses the other corrupts history.
3. **Decisions that belong to the server.** Session id, message id, session
   merge, model availability, and "is it running" are decided client-side.

The root cause of (1) is that the event stream has **no reconciliation anchor**:
there is no sequence number and no way to re-fetch the authoritative state. A
stalled or reconnecting client cannot tell whether it missed anything.

## 2. Target model

Split state into two kinds, and give each a clear transport:

- **Cold (finished) state** — the authoritative projection. The plugin *pulls*
  it over REST; the server computes it from its own durable store. The plugin
  never derives a finished message from raw events.
- **Hot (live) state** — the in-flight stream. Stays push (NDJSON), but every
  event carries an anchor (`seq` + `gap`) so the client can detect loss, and the
  client periodically reconciles against the cold projection.

The plugin becomes thin: it renders, it asks, it forwards. Renumbering,
retitling, merging, and availability checks move back to the server.

## 3. Responsibility inventory

The overlap, item by item. "Step" is the step that removes it.

| #  | The plugin currently...                                    | Truth lives in                 | Step |
|----|------------------------------------------------------------|--------------------------------|------|
| 1  | assembles the assistant reply from raw events              | server `Agent` / `LLMClient`   | 3    |
| 2  | guesses the phase ("thinking / connecting / weaving")      | server run state               | 3    |
| 3  | keeps its own sub-agent progress                           | server sub-agent               | 3    |
| 4  | tracks isSending / isWaiting / hasSent / session lifecycle | server run state               | 3    |
| 5  | accumulates token usage per turn                           | server usage ledger            | 3    |
| 6  | remembers dismissed ask-cards                              | server pending questions       | 3    |
| 7  | writes and parses the transcript format                    | one format, server-owned       | 4    |
| 8  | derives the session title (first user line)                | server                         | 4    |
| 9  | duplicates the error-code enum                             | server `ErrorCode`             | 4    |
| 10 | maps error codes to user-facing text                       | server sends code only         | 4    |
| 11 | generates the session id                                   | server                         | 5    |
| 12 | generates the message id (UUID) used for rewind            | server                         | 5    |
| 13 | merges sessions by weaving transcripts client-side         | server merge endpoint          | 5    |
| 14 | numbers merged titles (`N+1`)                              | server                         | 5    |
| 15 | pre-validates that the selected model exists               | server rejects at request time | 5    |
| 16 | blocks image attachments by guessing vision support        | server                         | 5    |
| 17 | guesses "not installed" vs "not started"                   | `/health` capability flags     | 5    |
| 18 | optimistically deletes then calls rewind                   | server-first, then local       | 5    |

## 4. Steps

Legend: ✅ done · 🔜 next · ⬜ planned.

### Step 1 — Stream sequence + drop reporting ✅

Make a lost event *visible*. This is the anchor every later reconciliation
depends on; without it the client cannot know when to reconcile.

- Add `EventStreamSequencer` (`src/main/kotlin/gradum/server/EventStreamSequencer.kt`):
  a per-request monotonic `seq` and a `gap` count of events the transport dropped.
- `/events` envelope (`src/main/kotlin/gradum/server/Routes.kt`) gains `seq` and
  `gap`. A number is claimed for every event, including dropped ones, so a
  discontinuity equals "dropped + 1". Drops left unreported when the stream ends
  are logged.
- Plugin `GradumApiClient` parses `seq` / `gap` and logs a WARN on a
  discontinuity.

Deliberately **not** changed in Step 1: NDJSON transport, the `sessionId`
semantics `/stop` relied on, and the client state model. Step 2a then did the
rename (no compatibility shims, no users to break).

Acceptance: `./gradlew :test` green; a deterministic playback stream shows
contiguous `seq` from 1 with `gap` always 0.
Commit: `48cf91d`.

### Step 2 — Authoritative state endpoint ✅

Add the server-side projection the client will reconcile against, and split the
two session identities that are currently conflated.

- **2a · one name per thing** — the envelope carries the run as `runId` and the
  durable session as `sessionId` (which is what the request body, `/session/*`,
  `/session/state`, and the ask scope all call it). `/stop` takes a `runId`. No
  compatibility shims: a `sessionId` that meant a run is gone.
- **2b · the rich-history sidecar** — the durable stores are lossy for the UI
  (`ChatDb` / `context.json` hold only the *cleaned model context*: tool results
  stubbed, thinking / token usage dropped), so `ContextManager.saveContext` now
  also writes `session-state.json` next to `conversation.md`, from the same
  in-memory history. One writer, two renderings, so they cannot drift. Base64
  image payloads are stripped (names only), matching the transcript's fidelity.
- **2c · the endpoint** — `GET /session/state?projectRoot=…&sessionId=…&since=<index>`
  returns the stored history as structured JSON plus `isRunning` / `runId`,
  `modelName`, `title`, `createdAt`, `updatedAt`, `lastMessageIndex`. A message's index is
  its 0-based position; `since` returns the delta after it. The endpoint is
  idempotent and keyed on the session (`SessionEntry.sessionId`).
- **2d · the live extras** — the payload also carries `tokenUsage` (summed from
  the messages, with `contextTokens` from the newest), `pendingAsks` (the wire
  payloads of the questions still parked, replayed by `PendingQuestions`), and
  `subAgents` (the `sub_agent:start` payloads of the runs still live). All three
  are computed per request, so the sidecar stays a pure history.

Acceptance: sidecar round-trips every UI field; the endpoint echoes the session,
reports `isRunning: false` for a finished session, returns the messages, and
redraws an open ask card.

### Step 3 — Client reconciliation ✅

Make the plugin stop trusting its own assembly once events may have been lost.

- `GET /session/state` is the cold projection. Its `messages` are already the
  bubble shape the UI renders; the plugin decodes them and replays `pendingAsks`
  onto the last assistant message.
- Reconcile when a `gap` was seen **after the run ends**, and when (re)opening a
  session. A clean `session_end` keeps the live view: the projection does not
  carry UI-only decorations (failed-tool styling, the delegate capsule's
  transcript), so replacing on every turn would regress them for no correctness
  gain. Tapping in on `gap`/reopen still removes the local-only divergence.
- Crash / kill with no `session_end` is covered by the reconnect path, not by a
  terminal event.
- `GradumChatSession.switchSession` reads the projection instead of parsing the
  transcript markdown, falling back to markdown only when the sidecar is absent
  (a session saved before it existed, or a failed write), so old history opens.

Acceptance: a forced `gap` or a reopened session yields messages identical to the
server projection; no local-only divergence.

### Step 3.5 — Optional live resume ⬜ (deferred, phase 2)

Not in scope for now: the default is "reconcile, don't resume". Only if
"reconnect mid-run and keep watching" is actually needed do we add a per-run ring
buffer keyed by `runId` + `seq`, plus `GET /events/{runId}?since=seq`. Not
required for Step 3.

### Step 4 — One authority for the format ✅

The sidecar is the single authority, and the plugin keeps no copy of what it no
longer needs.

- `conversation.md` is now a render of `session-state.json`:
  `ContextManager.saveContext` projects the history once, writes the sidecar, and
  feeds the very same projection to `SessionTranscriptWriter`, so the two files
  cannot drift.
- The plugin's `ErrorCode` (a byte-for-byte copy of `gradum.ErrorCode`) is
  deleted. Codes from the server are opaque strings; only the two the plugin
  raises itself live on, in `plugin/.../model/ChatErrorCode.kt`.
- The client-side title rule is deleted (`ChatTranscript.titleFor`,
  `PluginConfig.MAX_TITLE_LENGTH`); `SessionTranscriptWriter.titleFor` is the
  only one left.

The last Markdown dependencies were finished in Step 5b: the
`conversation.md` fallback in `GradumChatSession.switchSession`, the delegate
capsule's Markdown round-trip, and `plugin/.../history/ChatTranscript.kt` are all
gone.

Acceptance: the transcript is a render of the sidecar, and no client code
duplicates the title rule or the error-code vocabulary.

### Step 5 — Move decisions back to the server ✅

Split into five shippable sub-steps:

- **5a — Server-side merge ✅.** `POST /sessions/merge` reads each source's
  sidecar, concatenates the messages in timeline order (sources keyed by their
  transcript `updatedAt`, ties keeping the request order), numbers the title,
  and writes the merged session's sidecar + transcript itself. The plugin's
  `mergeSessions` is a thin call; `interleaveMessages` and `nextMergeTitle` are
  deleted.
- **5b — Sidecar-only cold load ✅.** `GradumChatSession.switchSession` drops the
  `conversation.md` fallback and cold-loads only the `GET /session/state`
  projection; the delegate capsule now carries the sub-agent's structured
  `subChatMessages` instead of a Markdown round-trip. Deleted:
  `plugin/.../history/ChatTranscript.kt`, `ChatSessionStore.loadSession`,
  `GradumApiClient.loadTranscript` / `saveTranscript`, and the server's
  `GET /session/transcript` / `POST /session/save` /
  `SessionTranscriptWriter.loadTranscript` / `saveTranscript`. Also fixes the
  long-standing `/session/rewind` wipe: the route no longer re-renders the
  transcript from the (empty, file-mode) chat DB, since `rewindTo` already
  re-renders sidecar + transcript from the surviving history.
- **5c — Server-allocated ids ✅.** `/events` names the session when the request
  omits one (`SessionIds.next()`), and the agent names the turn's user message
  (a fresh UUID when omitted). Both are echoed on `session_start`, so the plugin
  deleted `ChatSessionStore.nextSessionId` and every client-side message
  `UUID.randomUUID()`: a fresh chat sends no session id, and the send path
  adopts both from the envelope.
- **5d — Availability ✅.** The send-time `GET /models` probe keeps only its
  reachability job (retry + backoff): the "model is gone" verdict and the
  client-side vision guess (`gradum.model.no.vision`) are deleted, and the
  bundle key with them. `ModelsListResponse` moves to its only remaining
  consumer, `GradumChatSession`.
- **5e — Server-first rewind ✅.** `deleteMessage` asks the server to truncate
  first and withdraws the bubble only on a `200`, so a failed rewind leaves the
  history and the input untouched and reports `gradum.rewind.failed.*` instead.

Acceptance: creating, merging, rewind, and "model gone" flows produce no
client-authoritative ids or decisions.

### Step 5.5 — Thin the plugin ✅

Only after Steps 2–4 land: the plugin should hold one representation of a
message and never re-derive it. Two shippable sub-steps:

- **5.5a — one representation per message ✅.** `ChatMessage` keeps only
  `renderBlocks`: the `ChatEvent` sealed class and the `events` list it mirrored
  are deleted, along with `ToolCallInfo` (the block already carried every field
  the UI reads, and tool renderers dispatch on the alias, never the tool name).
  The handlers append blocks directly, so the two lists can no longer drift and
  the assembly machinery in `ChatMessage.kt` loses its `events` half.
- **5.5b — the server folds the history ✅.** The raw-history reading has one
  home, `server/.../persistence/SessionHistoryReader.kt`: `SessionTranscriptWriter`
  renders `conversation.md` from it row by row, and `toBubbles` folds the same rows
  into one bubble per turn. `GET /session/state` serves those bubbles, so the
  cold view and the transcript cannot disagree, and the plugin's
  `SessionProjection` is a ~120-line decoder with no knowledge of the payload
  (OpenAI vs Ollama arguments, `tool` rows, blank thinking). The sidecar keeps
  storing raw rows: it is the durable authority, and `SessionMerger` / token
  aggregation read it as-is.

The live stream stays event-shaped: making the server stream render blocks would
only relocate the assembly into the server and freeze the UI vocabulary into the
wire.

## 5. Open decisions

1. ~~**Rich history source.**~~ **Resolved (Step 2b): the JSON sidecar
   `session-state.json`**, written from the same `ContextManager.saveContext`
   call as `conversation.md`. **Step 4 renders the transcript from the sidecar's
   projection**, so the sidecar is the single authority.
2. ~~**Transport: NDJSON or SSE?**~~ **Resolved: stay on NDJSON.** The envelope
   already carries `seq` / `gap`, and reconciliation goes through the REST
   endpoint (`/session/state`), so SSE's built-in resume buys nothing. The
   envelope stays shaped so a later SSE move would be mechanical.
3. ~~**Live resume in scope?**~~ **Resolved (Step 3): deferred.** Step 3.5 is
   phase 2; the default is "reconcile, don't resume", so Step 3 ships without it.
4. **One session, several live runs.** `GET /session/state` resolves `runId`
   with a `firstOrNull` over `activeSessions` keyed by `sessionId`, so when two
   runs share one session the reported run is arbitrary. Single-client usage
   makes this unreachable today; revisit only if concurrent runs per session
   become a real scenario (then the endpoint needs a `runId` parameter or a
   defined pick).
5. **Runtime reachability via `/health` capability flags.** Skipped: `/health`
   is served by the server itself, so it cannot classify "never installed" vs
   "installed but not started" when the server is down. The plugin keeps
   `classifyFailure` + `isRuntimeInstalled()` until a non-server signal exists.

## 6. Explicit non-goals

- Keeping the public HTTP surface byte-compatible. New endpoints and fields are
  additive, but the plan deliberately deletes dead endpoints (5b:
  `GET /session/transcript`, `POST /session/save`) and renames the `/stop` body
  field `sessionId` → `runId` (2a). No alias shims: there are few users and no
  released client to keep working.
- Not changing `settings.json` (e.g. `commandFilter`) for this work.
- Not moving *logic* into the plugin; the direction is strictly outward.

## 7. Step log

| Step | Status     | Commit    | Note                                                                                                     |
|------|------------|-----------|----------------------------------------------------------------------------------------------------------|
| 1    | ✅ done    | `48cf91d` | `seq` + `gap` on `/events`; client WARN on discontinuity; 457 tests green                                |
| 2a   | ✅ done    | this PR   | `runId` (per run) vs `sessionId` (durable); `/stop` takes `runId`                                        |
| 2b/c | ✅ done    | `f4cda06` | `session-state.json` sidecar + `GET /session/state`; 463 tests green                                     |
| 2d   | ✅ done    | this PR   | `/session/state` also serves `tokenUsage`, `pendingAsks`, `subAgents`                                    |
| 3    | ✅ done    | this PR   | client reconcile on `gap`/reopen; projection→bubbles; 498 plugin tests green                             |
| 3.5  | ⬜ phase 2 | —         | live resume (`runId` ring buffer + `GET /events/{runId}?since=`)                                         |
| 4    | ✅ done    | this PR   | sidecar→transcript render; plugin `ErrorCode`/title rule dropped; 466 server + 496 plugin green          |
| 4b   | ✅ done    | `5900b18` | cold projection merges a turn's assistant records into one bubble                                        |
| 5a   | ✅ done    | this PR   | server-side merge; client `interleaveMessages` / `nextMergeTitle` deleted; 472 server + 497 plugin green |
| 5b   | ✅ done    | this PR   | sidecar-only cold load; `ChatTranscript` + Markdown endpoints deleted; 473 server + 490 plugin green     |
| 5c   | ✅ done    | this PR   | `/events` allocates both ids; plugin `nextSessionId` + message UUIDs deleted; 474 server + 492 plugin green |
| 5d   | ✅ done    | this PR   | `GET /models` probe is reachability only; model + vision guesses deleted; 474 server + 492 plugin green  |
| 5e   | ✅ done    | this PR   | `deleteMessage` rewinds server-first; bubble withdrawn on success; 474 server + 492 plugin green         |
| 5.5a | ✅ done    | this PR   | `ChatEvent` + `ToolCallInfo` deleted; one block list per message; 474 server + 497 plugin green          |
| 5.5b | ✅ done    | this PR   | server folds the raw history into bubbles (`SessionHistoryReader`); plugin `SessionProjection` is a decoder; 484 server + 494 plugin green |
| 5.5b | ⬜         | —         | server folds the history into blocks; plugin decodes                                                     |
