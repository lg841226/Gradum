# Gradum Buffet: Server-Authoritative State Migration

> Version: **0.1** · Branch: `feature/@gradum-gwy-buffet-projection` · Opened: **2026-10-17** · Status: **Step 1 done**

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
- **2c · the endpoint** — `GET /session/state?projectRoot=…&sessionId=…&since=<seq>`
  returns the stored history as structured JSON plus `isRunning` / `runId`,
  `modelName`, `title`, `createdAt`, `updatedAt`, `lastSeq`. A message's `seq` is
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

### Step 3 — Client reconciliation ⬜

Make the plugin stop trusting its own assembly once a turn is over.

- On `session_end`, on `gap > 0`, and when (re)opening a session, call
  `GET /session/state` and **replace** local messages with the projection.
- Crash / kill with no `session_end` is covered by the reconnect path, not by a
  terminal event.
- `GradumChatSession.switchSession` stops parsing the transcript markdown and
  reads the projection instead.

Acceptance: a forced `gap` or a reopened session yields messages identical to the
server projection; no local-only divergence.

### Step 3.5 — Optional live resume ⬜ (phase 2)

Only if "reconnect mid-run and keep watching" is actually needed: a per-run ring
buffer keyed by `runId` + `seq`, plus `GET /events/{runId}?since=seq`. Not
required for Step 3.

### Step 4 — One authority for the format ⬜

Remove the duplicated format and semantics.

- Conversation transcript format, title rule, and `ErrorCode` become
  server-owned; the plugin consumes structured JSON (`Step 2`), not markdown.
- Delete the client-side parser/writer (`plugin/.../history/ChatTranscript.kt`)
  and the duplicated `ErrorCode` (`plugin/.../model/ErrorCode.kt`) once nothing
  needs them.

Acceptance: history opens correctly with the client parser removed; a single
source defines the format.

### Step 5 — Move decisions back to the server ⬜

- Server allocates the session id and the message id.
- Add `POST /sessions/merge`; delete `ChatSessionStore.mergeSessions` /
  `interleaveMessages` and `nextMergeTitle`.
- Drop the client-side model pre-validation and vision guessing; let the server
  return the error code. Runtime reachability becomes `/health` capability flags.
- Rewind becomes server-first, then local (no optimistic delete).

Acceptance: creating, merging, rewind, and "model gone" flows produce no
client-authoritative ids or decisions.

### Step 5.5 — Thin the plugin ⬜

Only after Steps 2–4 land: drop `renderBlocks` / event-assembly from
`ChatMessage.kt` and `ChatEventHandlers.kt`, keeping rendering only. This is the
payoff step and the riskiest, so it goes last.

## 5. Open decisions

1. ~~**Rich history source.**~~ **Resolved (Step 2b): the JSON sidecar
   `session-state.json`**, written from the same `ContextManager.saveContext`
   call as `conversation.md`. Step 4 then makes the transcript a render of the
   sidecar, so the sidecar becomes the single authority.
2. ~~**Transport: NDJSON or SSE?**~~ **Resolved: stay on NDJSON.** The envelope
   already carries `seq` / `gap`, and reconciliation goes through the REST
   endpoint (`/session/state`), so SSE's built-in resume buys nothing. The
   envelope stays shaped so a later SSE move would be mechanical.
3. **Live resume in scope?** Step 3.5 is optional; the default is "reconcile,
   don't resume".

## 6. Explicit non-goals

- Not changing the public HTTP surface in a breaking way; new fields and new
  endpoints only.
- Not changing `settings.json` (e.g. `commandFilter`) for this work.
- Not moving *logic* into the plugin; the direction is strictly outward.

## 7. Step log

| Step | Status      | Commit    | Note                                                                      |
|------|-------------|-----------|---------------------------------------------------------------------------|
| 1    | ✅ done     | `48cf91d` | `seq` + `gap` on `/events`; client WARN on discontinuity; 457 tests green |
| 2a   | ✅ done     | this PR   | `runId` (per run) vs `sessionId` (durable); `/stop` takes `runId`         |
| 2b/c | ✅ done     | `f4cda06` | `session-state.json` sidecar + `GET /session/state`; 463 tests green      |
| 2d   | ✅ done     | this PR   | `/session/state` also serves `tokenUsage`, `pendingAsks`, `subAgents`     |
| 3    | ⬜          | —         | client reconcile (session_end / gap / reopen)                             |
| 3.5  | ⬜ optional | —         | live resume via per-run ring buffer                                       |
| 4    | ⬜          | —         | transcript / title / error-code single authority                          |
| 5    | ⬜          | —         | id, merge, availability, rewind authority                                 |
| 5.5  | ⬜          | —         | thin the plugin (drop event assembly)                                     |
