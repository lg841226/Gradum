# Gradum Refactor Plan

Performance optimization, complex-logic simplification, and comment cleanup for the Gradum agent
framework (`src/main/kotlin/gradum/`) and the IntelliJ plugin (`plugin/src/main/kotlin/gradum/idea/`).

Branch: `feature/@gradum-gwy-framework-refactor`

> **Note:** Copyright header cleanup is explicitly out of scope. The header standard is considered
> obsolete and the blocks stay as-is.

---

## Phase 0 — Baseline verification

Run before any change and again after each phase:

```bash
./gradlew build
./gradlew detekt
./gradlew test
```

---

## Phase 1 — Comment cleanup (mechanical, low risk)

Remove over-commenting. Focus on KDoc on private members, WHAT-restating comments, and
obsolete-history comments ("previous design relied on...", "was tried but..."). Keep genuine WHY
comments (security invariants, non-obvious coupling, magic-value reasons).

### Framework

| File | Action |
| ---- | ------ |
| `skill/Skill.kt` (71% comment density) | Trim the 5 oversized KDocs, drop `@param` tags that restate signatures, delete the `historyKeepCount` history narrative. Keep the "remaining own-message count" WHY. |
| `agent/Agent.kt` (192 comment lines) | Shorten 20-28 line KDocs on private methods (`playToolCallScenario`, `emitToolResult`, `buildUserMessage`, `truncateHistory`, `filterConditionalSections`). Delete WHAT comments (`checkToolRunaway`, `// Inside conditional block`, `// Filter conditional sections...`, `maxHistoryMessages` KDoc). Delete obsolete-history at 99-101. Keep the `runBlocking` TODO and projectRoot security comments. |
| `client/LLMClient.kt` (129 comment lines) | Shorten the 37-line private `projectMessageForBackend` KDoc. Delete the duplicated wire-format comment at 243-253 and 371-375 (same concept explained 3x). Delete KDocs on `optString`/`optInt`/`optObject`. Delete the `formatLlmError` history sentence. Keep the `[DONE]` sentinel and empty-branch WHY comments. |
| `skill/SkillRegistry.kt` | Delete WHAT KDocs on 6 private functions and both `// Skip classes that can't be loaded` instances. Shorten `getSchemas` KDoc. |
| `discovery/ModelRecommender.kt` | Delete duplicated `-500` scoring comments (100-101). Shorten `score` KDoc. |
| `utils/ContextManager.kt` | Shorten `readStringMap` KDoc (drop `L149` compiler-warning archaeology). Delete `convertJsonElement` and `readListOfMaps` WHAT KDocs. |
| `utils/JsonUtil.kt` | Delete `encodeMap` and `toJsonElement` WHAT KDocs. |
| `skill/ExploreProjectSkill.kt` | Delete ~12 `// Apply...` / `// Parse...` restatements. |
| `skill/EditFileSkill.kt` | Delete KDocs on private types (`MatchStrategy`, `MatchResult`, `MatchingResult`) and `executeLocal`/`executeCloud`. |
| `skill/SearchSkills.kt` | Shorten the glob PathMatcher KDoc, delete the old-translator story and `// Skip unreadable files`. |
| `AgentConfiguration.kt` | Delete obsolete-history tails in `Provider` and `fromStringOrDefault` KDocs; delete `resolveAuto` WHAT KDoc. |
| `SchemaVariant.kt`, `SkillContext.kt`, `skill/TodoSkill.kt`, `skill/SaveFileSkill.kt`, `debug/ToolCallScenarioParser.kt`, `server/ServerConfiguration.kt`, `server/App.kt` | Delete WHAT KDocs and redundant `@param` tags. |

### Plugin

| File | Action |
| ---- | ------ |
| `utils/GradumBundle.kt` (56% density) | Delete the 25-line KDoc documenting the previous bundle implementation. Keep the fallback-marker explanation. |
| `utils/Spacing.kt` (53% density) | Delete the 9 per-constant KDocs and the `welcomeTitleTracking` prose. |
| `chat/ui/markdown/FootnoteRegistry.kt` (43%) | Delete the class KDoc and nearly all member KDocs on internal members. |
| `chat/state/GradumChatSession.kt` (291 comment lines) | Delete private-member KDocs and the 15/20-line history comments at 313-327 and 389-408. |
| `GradumGitAnalysisService.kt` (205 comment lines) | Delete ~20 one-line member KDocs, shorten the object KDoc. |
| `chat/model/ModelInfo.kt`, `chat/model/ChatMessage.kt`, `chat/model/MarkdownTlsScenario.kt`, `chat/ui/markdown/Table.kt`, `chat/ui/markdown/BlockRenderer.kt`, `chat/ui/markdown/InlineMarkdown.kt`, `editor/EditorContext.kt`, `editor/Attachments.kt`, `ImageUpload.kt`, `chat/ui/input/ModelNameFormatter.kt`, `chat/ui/ChatScreen.kt` | Delete WHAT KDocs on private members; shorten over-long public-API docs (ChatScreen 34-line, MarkdownTlsScenario 34-line). |
| `chat/ui/GradumUI.kt`, `chat/ui/input/ChatToolbar.kt`, `ui/GradumState.kt` | Delete section-label restatements. |

---

## Phase 2 — Complex logic simplification

### Framework

| # | File:lines | Problem | Fix |
| - | ---------- | ------- | --- |
| 2.1 | `agent/Agent.kt:605-663` | `executeSingleTool` 3-level if/else; the two `else` branches are verbatim duplicates | Early-return the tool-runaway case; flatten the READ_ONLY+run_cmd guard into a single shared tail path (getSkill → executeSkill → emitToolResult) |
| 2.2 | `agent/Agent.kt:209-258` | Two structurally identical guardrail escalation blocks (bump → emit guardrail → if over threshold: append/revoke/abort/break) | Extract `recordGuardrail(...)` and `handleGuardrailExceeded(...)` helpers |
| 2.3 | `skill/SearchSkills.kt:334-366,626-676` | `executeLocal`/`executeCloud` byte-identical (Glob) / near-identical (Grep, differs only in case-sensitivity) | Merge into one `executeInternal(...)`; Grep takes the ignoreCase flag as a parameter |
| 2.4 | `skill/ExploreProjectSkill.kt:158-298` | ~60 lines of dead filter plumbing — `filterType="all"`, `filterExtension=""`, `filterDirectory=""`, `minLines=0`, `maxLines=null`, `limit=DEFAULT_LIMIT` are never read from request args; two near-duplicate output builders | Delete dead scaffolding + `FilterConfig` dead fields; collapse the two output builders into one parameterized builder |
| 2.5 | `discovery/ModelDiscovery.kt:370-375` | `contains("cloud")` makes `endsWith(":cloud")` / `contains("-cloud")` dead disjuncts | Collapse to a single `contains("cloud", ignoreCase = true)` |
| 2.6 | `agent/Agent.kt:278-286` | Unreachable `AUTO` arm in the second `when` (AUTO fully resolved on line 280) | Delete the dead arm |
| 2.7 | `utils/MessageHistoryTruncator.kt:73-98` | Flag-driven `while (foundBoundary)` loop obscures a simple backward walk | Rewrite as `while (toolRunStart > 0 && roleOf(messages[toolRunStart - 1]) == "tool") toolRunStart--` |
| 2.8 | `skill/SkillRegistry.kt:174-219` | `getClassName` computed twice; identical class-filter logic in directory and jar scanning | Extract `loadConcreteClass(name): Class<*>?` |
| 2.9 | `skill/ReadFileSkill.kt:127-186` | Four repeated `INVALID_PARAMETER` failure blocks; ad-hoc `Triple` return | Extract `parseLineRange(...)` returning a small `LineRange` data class |
| 2.10 | `utils/ContextManager.kt:138-214` | 4-5 level nesting in `cleanMessageHistory`; duplicated add/log shapes; `droppedCallCount` recomputed | Extract per-role helpers; reuse `toolCalls.size - preservedCalls.size` |
| 2.11 | `utils/SyntaxChecker.kt:368-378` | Convoluted triple-disjunct predicate with stray parens; regex recompiled per issue | Name the boolean clauses; hoist the regex |

### Plugin

| # | File:lines | Problem | Fix |
| - | ---------- | ------- | --- |
| 2.12 | `chat/ui/chat/ErrorMessages.kt:51-52` | Dangling `if` — `append("Tool: $tool")` runs unconditionally (real output bug) | Add braces so the newline guard and append are both inside the `tool.isNotBlank()` branch |
| 2.13 | `chat/state/GradumChatSession.kt:505-691` | 5x duplicated "append error to last assistant message + reset flags" block | Extract `appendAssistantError(code, message)` and `resetSendingState(processQueue)` |
| 2.14 | `chat/ui/chat/skill/spi/ResultParser.kt`, `chat/state/GradumChatSession.kt:910-927`, `GradumGitAnalysisService.kt:718-727` | JSON→Any primitive conversion implemented 3 separate times with subtle ordering differences | Extract one shared `JsonElement.toAny()` extension into a common util |
| 2.15 | `chat/ui/chat/skill/GlobRenderer.kt` / `GrepRenderer.kt` | Near-identical renderer files (differ in one field, one key, one label) | Extract a shared `CountRowRenderer`; both become thin subclasses |
| 2.16 | `editor/EditorContext.kt:99-111` | Redundant `if/else` — both branches construct the same `EditorContext` | Construct directly |
| 2.17 | `GradumGitAnalysisService.kt:512-625` | `ScanCommitsTask`/`AnalyzeDataTask` duplicate the same JSONL read-loop | Extract `consumeJsonl(reader, indicator, onRecord)` |
| 2.18 | `GradumGitAnalysisService.kt:378-400,435,663` | Non-`@Volatile` shared fields mutated from background tasks and EDT; RMW race on `auditFindings` | Mark shared handles `@Volatile`; accumulate findings in a background-owned list, commit once on EDT |
| 2.19 | `ui/GradumCallbacks.kt:385-423,494-518` | Duplicated "add user message + empty assistant + set flags + launch send" | Extract `session.startSend(...)` |
| 2.20 | `chat/state/GradumChatSession.kt:210,411,429` | Job-cancel pattern duplicated 3x | Extract `cancelJob(job, reason)` |
| 2.21 | `chat/ui/markdown/Table.kt:306-310` | Double `rememberGradumMarkdownStyling()` call | Reuse the first result |
| 2.22 | `chat/ui/markdown/InlineMarkdown.kt:706-758` | `when (match)` with `in list` guards is opaque; placeholder push/pop written 3x | Make dispatch explicit; extract `appendInlinePlaceholder` |

---

## Phase 3 — Performance optimization

### Framework — safe wins

| # | File:lines | Problem | Fix |
| - | ---------- | ------- | --- |
| 3.1 | `client/LLMClient.kt:272,390` | New `HttpClient` built + torn down every LLM turn; no connection reuse | Hoist one shared `HttpClient` (top-level private val) reused by both clients, closed at shutdown |
| 3.2 | `client/LLMClient.kt:499-502` | O(n²) string concat accumulating streamed tool-call args | Store a `StringBuilder` per call index; `append(argumentDelta)` |
| 3.3 | `utils/JsonUtil.kt:33` | New `Json` instance allocated on every `encodeMap` call (every NDJSON event) | Cache COMPACT and PRETTY `Json` instances, select between them |
| 3.4 | `agent/Agent.kt:1036-1044` | Re-lowercases full response text per red-line keyword per turn | Lowercase text once; pre-lowercase keywords at load time |
| 3.5 | `agent/Agent.kt:600` | Pretty-prints full tool args (up to 512 KB for `save_file`) just for a log line | Downgrade to debug and/or truncate; never eager-serialize hot payloads |
| 3.6 | `skill/ExploreProjectSkill.kt:478-484` | Immutable `visitedPaths` Set copied per directory descent — O(D²) | Use a single mutable `MutableSet<Path>` |
| 3.7 | `skill/ExploreProjectSkill.kt:509-523` | Regex compiled per file per exclude pattern | Precompile patterns once per `execute` into `List<Regex>` (or use `PathMatcher`) |
| 3.8 | `utils/SyntaxChecker.kt:75,82,240,349,375` | Regexes compiled inside per-line/per-issue loops | Hoist each to file-level `private val` |
| 3.9 | `utils/CommandFilter.kt:91,115,154` | Regexes compiled per `classifyCommand` | Hoist `\s+` and redirect regexes to file-level vals |
| 3.10 | `skill/EditFileSkill.kt:483-485` | `Regex("\\s")` compiled per line comparison | Precompile; prefer `filterNot { it.isWhitespace() }` |
| 3.11 | `skill/ReadFileSkill.kt:200-211` | Selected lines joined twice (once for MD5, once for content) | Build content once, reuse for hash |
| 3.12 | `agent/Agent.kt:1101` | Regex recompiled + per-sentence lowercasing each turn | Hoist sentence-split regex; lowercase joined text once |
| 3.13 | `discovery/ModelRecommender.kt:132-137`, `discovery/ModelCatalog.kt:98-110` | Regex per model in scoring/normalization | Hoist to file-level vals |

### Framework — deeper refactors

| # | File:lines | Problem | Fix |
| - | ---------- | ------- | --- |
| 3.14 | `discovery/ModelDiscovery.kt:172-187,205-319` | Nested `runBlocking` defeats the `async` fan-out — cloud probes run effectively serial; fresh `HttpClient` per probe blocks the event loop for tens of seconds | Make `probeServer`/`probeOllamaCloudModel` true `suspend` functions; run under `Dispatchers.IO`; share one `HttpClient` |
| 3.15 | `discovery/ModelCatalog.kt:31-68` | Full models.dev catalog re-downloaded every 60 s TTL | Separate catalog cache TTL from probe TTL (catalog loads once / long TTL); cache `HttpClient` |
| 3.16 | `agent/Agent.kt:922-931` | Full O(n) history scan + JSON decode/re-encode per tool call | Maintain a per-alias index of history indices (compaction-aware after `truncateHistory`), or cheap pre-check in `stripVolatileKeys` |

### Plugin — safe wins

| # | File:lines | Problem | Fix |
| - | ---------- | ------- | --- |
| 3.17 | `chat/ui/input/ModelNameFormatter.kt:434,435,492,550,615,625` | Regexes compiled inside functions called from composition | Hoist all 5 to top-level/companion `private val` |
| 3.18 | `chat/model/ChatMessage.kt:243-264` | `formatTimestamp` allocates 2 `Calendar` + 1 `SimpleDateFormat` per call | Cache formatter / use `java.time` `DateTimeFormatter` |
| 3.19 | `chat/ui/markdown/CodeBlockRenderer.kt:440-454` | `truncateAnnotatedString` rebuilds newline-index list per recomposition | `remember(collapsed)` |

### Plugin — deeper refactors

| # | File:lines | Problem | Fix |
| - | ---------- | ------- | --- |
| 3.20 | `chat/state/GradumChatSession.kt:711-725` + `chat/ui/chat/AssistantChatBubble.kt:188,231` + `chat/ui/ChatScreen.kt:164` | Whole-message markdown re-parse + full-list recomposition on every streamed chunk (O(n²)) — the single highest-leverage fix | Throttle/batch stream updates (flush ~50-100 ms or once per frame); render incrementally so only the new suffix is parsed |
| 3.21 | `ImageUpload.kt:63-75`, `ui/GradumCallbacks.kt:134,355,452`, `chat/ui/chat/MessageAttachmentPreview.kt:112` | File I/O + base64 + image decode on the EDT | Move to `withContext(Dispatchers.IO)`; cache decoded `ImageBitmap` |
| 3.22 | `GradumGitAnalysisService.kt:663` | `auditFindings = auditFindings + auditFinding` per finding — O(n²) list copy + whole-tree recomposition per finding | Batch findings and commit once per scan (or use a `SnapshotStateList` with `add`) |
| 3.23 | `chat/model/ChatMessage.kt:166,171,179` | `appendEvent` full-list copy per chunk (O(n²)) | Special-case appending to the last merged-Response block in place |

---

## Verification

- After Phase 1: `./gradlew build`
- After Phase 2: `./gradlew build && ./gradlew test` (all logic changes covered by existing tests)
- After Phase 3: `./gradlew build && ./gradlew test && ./gradlew detekt` (zero new violations)

## Out of scope

- Copyright header removal (standard considered obsolete — blocks stay).
- Any behavior changes beyond the fixes listed above.
- The plugin-side `GradumApiClient` already reuses one `HttpClient` — no change needed (verified).
