# Chat Session History (多会话历史) Implementation Plan

| Field            | Value                                          |
|------------------|------------------------------------------------|
| **Branch**       | `feature/chat-history-sessions`                |
| **Status**       | Implemented (all phases landed on this branch) |
| **Last Updated** | 2026-08-12                                     |

---

## Problem Statement

Today the plugin keeps chat history only in memory (`GradumChatSession.messages`, a `SnapshotStateList`). Every IDE
restart opens a blank "new session" even though the server already persists model context
(`<projectRoot>/.gradum/context.json`).

Additionally, the "New Chat" button (`GradumToolWindowFactory.kt:93`) only clears the plugin UI via `session.reset()` —
it does **not** clear the server-side `context.json`, so the model actually remembers the previous conversation while
the UI claims a fresh start.

We solve both with a **one conversation = one session** model persisted as a **structured Markdown transcript per
session** that can be parsed back into the full bubble UI (thinking, tool calls, errors, token usage) with zero fidelity
loss.

---

## Requirements (agreed)

1. **Multi-session list management** — view previous sessions, switch, delete.
2. **Full-detail restore** — thinking, tool calls, errors, token usage survive restore.
3. **"New Chat" truly starts a new session** — server model memory is isolated per session, so a new session means the
   model really forgets.
4. **Delete cascades** — deleting a session removes the local MD transcript **and** the entire session directory (server
   `context.json` included). No orphans.
5. **`reset()` / New Chat semantics preserved** — New Chat returns to the Welcome screen and starts a fresh session;
   Welcome screen is reached via New Chat (no extra toolbar button in v1).

---

## 1. Directory Layout (`.gradum`)

Single source of truth for where everything lives under the open project.

```
<projectRoot>/
└── .gradum/                          # project-local runtime data (gitignored)
    ├── context.json                  # legacy/default-session context (fallback, backward compat)
    ├── recordings/                   # server · debug playback recordings (unchanged)
    │   └── <scenario>-<timestamp>.json
    ├── run_cmd/                      # server · detached-command logs (unchanged)
    │   └── <timestamp>.log
    └── sessions/
        └── <sessionId>/              # one conversation = one directory
            ├── conversation.md       # plugin · structured transcript (read/write)
            └── context.json          # server · this session's model context
```

### 1.1 Path rules (must never drift)

```
sessionDir(projectRoot, sessionId) = <projectRoot>/.gradum/sessions/<sessionId>
```

- Plugin (`ChatSessionStore`) and server (`Agent`) MUST agree on this single rule.
- With `sessionId == null` / blank the server falls back to the legacy
  `<projectRoot>/.gradum/context.json` (old-clients compatibility).

### 1.2 sessionId format

```
yyyyMMdd-HHmmss-xxxxxx        e.g. 20260812-131500-a1b2c3
```

- Time prefix sorts lexicographically (session list ordering = directory listing order).
- 6-char hex suffix (lowercase) guards against same-second collisions; with
  ~16M possibilities a birthday-paradox collision is negligible even for hundreds of ids generated in the same second.

---

## 2. Server Changes

### 2.1 `src/main/kotlin/gradum/server/Routes.kt`

- Add `val sessionId: String? = null` to `EventsRequestBody`.
- Validate / normalize `sessionId` (trim; blank → null). Reject empty `projectRoot` as today.
- Thread `sessionId` through to `AgentConfiguration`.

### 2.2 `src/main/kotlin/gradum/agent/Agent.kt`

- `AgentConfiguration` gains an optional `sessionId: String?` field.
- `ContextManager` output directory resolves to:
  ```kotlin
  val contextDir: Path = if (configuration.sessionId.isNullOrBlank()) {
      Path.of(configuration.projectRoot).resolve(".gradum")          // legacy
  } else {
      Path.of(configuration.projectRoot).resolve(".gradum/sessions")
          .resolve(configuration.sessionId)                            // per-session
  }
  ```
- **Effect:** switching session = switching model memory; New Chat = fresh context file.

### 2.3 New endpoint: `POST /session/delete`

Delete the entire session directory (plugin cascades with this).

- Request: `{ "projectRoot": "...", "sessionId": "..." }`
- Behavior: delete `<projectRoot>/.gradum/sessions/<sessionId>` recursively; 404 when not present or path resolves
  outside `.gradum/sessions/` (guard against path traversal — resolve then verify `startsWith(sessionsRoot)`).
- Response: `{ "status": "deleted" | "not_found", "sessionId": "..." }`

> Note: legacy `context.json` (no sessionId) may be left untouched / wiped by a
> dedicated call if needed; not in scope for v1.

---

## 3. Plugin Core Changes

### 3.1 New file `plugin/src/main/kotlin/gradum/idea/chat/history/ChatSessionStore.kt`

Project-scoped file store (uses `project.basePath`):

| Method                               | Behavior                                                                               |
|--------------------------------------|----------------------------------------------------------------------------------------|
| `saveSession(id, messages, meta)`    | Write `conversation.md` (atomic temp+move, like `ContextManager`)                      |
| `listSessions(): List<SessionMeta>`  | Scan `.gradum/sessions/`, read only the MD header (first few lines, never the body)    |
| `loadSession(id): List<ChatMessage>` | Parse `conversation.md` back to bubbles                                                |
| `deleteSession(id)`                  | Delete entire `.gradum/sessions/<id>` dir locally + call server `POST /session/delete` |
| `nextSessionId(): String`            | `yyyyMMdd-HHmmss-xxxxxx`                                                               |

`SessionMeta`: `id`, `title` (first user message, truncated), `createdAt`, `updatedAt`, `modelName`.

### 3.2 New file `plugin/src/main/kotlin/gradum/idea/chat/history/ChatTranscript.kt`

Structured MD generation + parsing.

- **Write:** header comment block (format version, sessionId, title, created/updated, model) + one `## user` /
  `## assistant` section per message. Assistant sections carry
  `<thinking>`, `<tool_call>` (arguments JSON + result), `<error code=... tool=...>`, and `<response>` blocks in order;
  token usage in header comment.
- **Read:** line-based state machine → rebuild `ChatEvent` sequence → feed
  `ChatMessage.appendEvent` so `renderBlocks` are re-derived identically to runtime.
- **Attachments:** persisted as `name` + `path`; restored as display chips (`AttachedText`-style) so the user bubble
  keeps its attachment list.

**Round-trip guarantee:** `parseTranscript(generateTranscript(messages)).fullContent == messages.fullContent`.

### 3.3 `GradumChatSession.kt` (`@Service(PROJECT)`)

- New state: `activeSessionId: String?`, `sessions: SnapshotStateList<SessionMeta>`,
  `currentSessionTitle: String`.
- On `session_end` event (stream complete) → `saveSession()` with all current messages.
- New methods: `newSession()` / `switchSession(id)` / `deleteSession(id)`.
- `reset()` **keeps its meaning** (clear UI, return to Welcome) and becomes the entry point of `newSession()` — New
  Chat → `session.reset()` → fresh sessionId + save.
- On tool-window init: `listSessions()` to populate the recent-chats region (async on `Dispatchers.IO`, cancelled
  when superseded). The section renders only when sessions exist; blank titles fall back to `formatTimestamp(updatedAt)`.

### 3.4 `GradumApiClient.sendMessage()`

- Add `sessionId: String?` parameter; include in the `/events` request body (`put("sessionId", sessionId)` when
  non-null).

---

## 4. UI — Welcome Page "Recent Chats" Region

### 4.1 New file `plugin/src/main/kotlin/gradum/idea/chat/ui/home/RecentChatsSection.kt`

- Rendered below `QuickStartSection` inside `WelcomeScreen` (same column).
- Visual style mirrors `SuggestionCard` (`QuickStartSection.kt:105-133`); one row per session:
  `Icon(ArrowRight)` → first user message text (`weight(1f)`, `TextOverflow.Ellipsis`)
  → gray "N days ago" → hover-revealed delete icon.
- Click row → `switchSession(id)`.
- Delete icon (hover) → `deleteSession(id)` with cascade.
- Constant `MAX_RECENT_SESSIONS = 5` (show most-recent N).
- Empty state: soft hint "No previous conversations".

### 4.2 Interaction rules (agreed)

- Recent-chats region exists **only** on the Welcome screen.
- After entering a restored session, return to the list via the **New Chat** button (which resets to Welcome + starts a
  fresh session).

### 4.3 i18n keys (`GradumBundle.properties` + `GradumBundle_zh_CN.properties`)

| Key                    | en                 | zh_CN    |
|------------------------|--------------------|----------|
| `gradum.recent.chats`  | Recent Chats       | 最近会话 |
| `gradum.recent.delete` | Delete conversation | 删除会话 |

> The row's relative time reuses the existing `gradum.timestamp.*` keys via
> `formatTimestamp` (today → `HH:mm`, yesterday, `MMM d`, `N days ago`).

---

## 5. Data / Wire Compatibility

- `sessionId` is optional in `/events`; older server builds ignore unknown request fields (lenient JSON) and old plugins
  omit it → no breaking change.
- Legacy `.gradum/context.json` remains the fallback when no `sessionId` is sent, so upgraded plugins talking to old
  servers still work.

---

## 6. Tests

### 6.1 Server (`src/test/kotlin/gradum/`)

- `Agent` context directory resolves to `.gradum/sessions/<id>/` (and legacy path when null).
  → `SessionContextDirectoryTest`
- `POST /session/delete` deletes directory + guards path traversal (404 / reject outside root).
  → `SessionDeleteEndpointTest`
- `Routes` accepts `sessionId` in `/events`; ignores missing field.
  → `DebugPlaybackEndToEndTest` (`context is written under gradum sessions dir when sessionId is sent`,
  `context falls back to legacy file when sessionId is absent`)

### 6.2 Plugin (`plugin/src/test/kotlin/gradum/idea/chat/history/`)

- `ChatTranscript` round-trip: `parseTranscript(generateTranscript(messages))` → assert `fullContent` equality (thinking / tool calls /
  errors / token usage preserved). → `ChatTranscriptTest` (9 tests: round-trip, thinking, tool calls, errors, token
  usage, trailing-newline stability, meta parse, title extraction, unknown-line handling)
- `ChatSessionStore`: save → list ordering (most recent first) → load → delete (dir gone). → `ChatSessionStoreTest`
  (8 tests: save/load, list ordering, delete, delete guards, meta, `nextSessionId` format + uniqueness +
  lexicographic sortability)

---

## 7. Docs to Update (branch, not committed yet — 撰写于文档)

- `docs/ARCHITECTURE.md` — `.gradum/sessions/` layout, sessionId format, session lifecycle.
  ✅ done on this branch.
- `docs/CODING_STANDARDS_KOTLIN.md` / `docs/CONVENTIONS.md` — UI conventions for the new
  `RecentChatsSection` (modifier last, `onXxx` callbacks, `JewelTheme` colors only).
  ✅ no changes needed — `RecentChatsSection` already follows existing conventions
  (modifier last, `onOpenSession`/`onDeleteSession` callbacks, `JewelTheme` colors only).

---

## 8. Out of Scope (v1)

- Rename session (deferred; only delete in v1).
- Session list on the chat screen / extra toolbar button (Welcome-only in v1).
- Importing legacy `.gradum/context.json` as a historical session.

---

## 9. Implementation Order

1. Server: `sessionId` in `/events` + per-session `ContextManager` path + `POST /session/delete`.
2. Plugin store: `ChatSessionStore` + `ChatTranscript` (+ unit tests).
3. Session state wiring in `GradumChatSession` + `GradumApiClient.sessionId`.
4. UI: `RecentChatsSection` + `WelcomeScreen` integration + i18n keys.
5. Server tests + plugin round-trip tests.
6. Update ARCHITECTURE.md / CONVENTIONS.md.
