<!--
  ~ Copyright (c) 2026 Gradum team, some rights reserved.
  ~ For licensing terms and conditions, see the MIT LICENSE file.
  ~
  ~ PLUGIN_FEATURES.md  2026-07-14 07:46:00 Changed by gwy
  -->

# Gradum Plugin Features

This document describes every user-facing feature of the Gradum IntelliJ IDEA plugin (the `plugin/` module). It is the
canonical reference for the chat tool-window, its composing UI surfaces, and the cross-cutting infrastructure that holds
them together.

For the server-side protocol, the agent loop, and the Skill contract see
[`ARCHITECTURE.md`](ARCHITECTURE.md). For Skill authoring see
[`PLUGIN_DEVELOPMENT.md`](PLUGIN_DEVELOPMENT.md). This file is plugin-only.

---

## At a glance

| Area                                | Count / Scope                               |
|-------------------------------------|---------------------------------------------|
| Kotlin source files (plugin module) | 80 main + 11 test                           |
| Tool-window / chat UI components    | 2 tool windows (chat + Git analysis)        |
| i18n keys                           | 251 (`en`) / 249 (`zh_CN`)                  |
| Icon resources                      | 103 SVGs + 1 TTF font (`GoogleSans.ttf`)    |
| Project-level services              | 1 (`GradumChatSession`)                     |
| HTTP endpoints consumed             | 3 (`/events`, `/models`, `/stop`)           |
| Wire event types handled            | 4 (thinking / tool_call / response / error) |

The plugin is built on JetBrains Jewel + Compose for Desktop. Every visible string is localizable; every color is
theme-aware through `JewelTheme`; every icon is loaded from the plugin classpath at runtime.

---

## 1. Tool window integration

The plugin registers two IntelliJ tool windows:

- **Chat tool window** — `id` `Gradum`, anchored to the **right sidebar**, factory
  `gradum.idea.GradumToolWindowFactory`, icon `icons/logo/logo.svg`. Hosts a Compose tab via `addComposeTab` and seeds
  the chat session from the project-level `GradumChatSession` service. A `New Chat` title-bar action resets the session
  and resets the tab display name to the localized welcome string.
- **Git analysis tool window** — `id` `Gradum Git`, anchored to the **bottom** (next to the Problems panel), factory
  `gradum.idea.GradumGitAnalysisToolWindowFactory`. On first open it shows a home screen describing the audit ("Audit
  your Git commits with Gradum"); clicking **Begin Analysis** launches the git-history audit. See
  [Section 19](#19-git-analysis-tool-window) for the full feature walkthrough.

Both are declared in
[`plugin.xml`](../plugin/src/main/resources/META-INF/plugin.xml) under the
`com.intellij` extension namespace.

> Project-level state is non-negotiable for the chat: when the user collapses
> the sidebar the IntelliJ Platform disposes the tool-window content, so any
> in-Compose `mutableStateOf` is lost on the next expand. `GradumChatSession`
> is the sole owner of cross-collapse state. The Git analysis tool window
> inverts this: its scan lifecycle lives in the process-wide
> `GradumGitAnalysisService` object so a scan keeps progressing even while
> the bottom tool window is hidden.

Source: [`GradumToolWindowFactory.kt`](../plugin/src/main/kotlin/gradum/idea/GradumToolWindowFactory.kt),
[
`GradumGitAnalysisToolWindowFactory.kt`](../plugin/src/main/kotlin/gradum/idea/GradumGitAnalysisToolWindowFactory.kt),
[`plugin.xml`](../plugin/src/main/resources/META-INF/plugin.xml).

---

## 2. Welcome screen

The first surface the user sees when the tool window opens against an empty session.

- **Layout** — outer `Box` with `contentAlignment = Center`; inner `Column`
  capped at `max width = 600.dp` with `start padding = 6.dp` so the header, the input area, and the quick-start section
  all share one vertical line.
- **Rotating greeting** — a `SweepLightText` composable renders a typewriter effect across a hand-curated list of
  welcome messages (anti-repetition logic guarantees the same greeting is never shown twice in a row).
- **Quick-start** — four categories with five variants each (chat, code, question, text); a fresh `Random.nextInt(5)` is
  drawn for each category at every open, then frozen for the session.

Source: [`WelcomeScreen.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/home/WelcomeScreen.kt),
[`QuickStartSection.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/home/QuickStartSection.kt),
[`SweepLightText.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/SweepLightText.kt).

---

## 3. Chat screen

The `ChatScreen` composable is the root of the message experience and is mounted as soon as the user sends their first
message (`hasSentMessage` flips to `true`). It composes:

1. A scrollable message list.
2. The input section (toolbar + text field + attachments + model bar).
3. A reactive `isWaitingForResponse` overlay.

The screen takes 11 typed parameters today; the 8-parameter model selector bar inside it is a known refactor target (a
dedicated data class would let callers pass a single `ModelSelectorState` instead).

Source: [`ChatScreen.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/ChatScreen.kt).

---

## 4. Message bubbles

Every visible message is one of two bubbles, both anchored to the right edge for symmetry.

### 4.1 `UserChatBubble`

- Renders the user's raw text and a row of attached files.
- Attachments render in a collapsible list (animated visibility toggle).
- A copy button (`MessageCopyButton`) sits in the bubble footer.

### 4.2 `AssistantChatBubble`

- Renders an event timeline — `Thinking`, `ToolCall`, `Response`, `Error` — in arrival order.
- Each event is a `RenderBlock`; consecutive events of the same type are coalesced into a single block so Compose can
  reuse composables without rebuilding the list (see [Section 5](#5-message-event-timeline)).
- A `MessageTimestamp` sits in the bubble footer and formats the timestamp based on how recent the message is —
  see [Section 5.4](#54-message-timestamp).

Source: [`UserChatBubble.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/UserChatBubble.kt),
[`AssistantChatBubble.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/AssistantChatBubble.kt),
[`MessageAttachmentList.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/MessageAttachmentList.kt),
[`MessageCopyButton.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/MessageCopyButton.kt).

---

## 5. Message event timeline

### 5.1 Wire events → UI blocks

The server streams four `ChatEvent` subtypes as NDJSON. The client folds them into three `RenderBlock` kinds:

| Wire event  | Render block           | Behaviour                                                             |
|-------------|------------------------|-----------------------------------------------------------------------|
| `thinking`  | `RenderBlock.Thinking` | Coalesced with the previous thinking block; concatenated as a string. |
| `tool_call` | `RenderBlock.ToolCall` | Never coalesced (one block per invocation).                           |
| `response`  | `RenderBlock.Response` | Coalesced with the previous response block; concatenated as a string. |
| `error`     | `RenderBlock.Error`    | Never coalesced.                                                      |

`ChatMessage.appendEvent` performs an O (1) update — it never re-iterates the existing list. `updateLastError` likewise
does an O (1) `indexOfLast +
set` to patch a failed tool call's error message after the fact.

### 5.2 Per-tool call content

Each server-side skill alias (`Ran`, `Edited`, `Read`, `Saved`, `Explored`,
`Planned`, `Completed`) is rendered by a dedicated `ToolCallRenderer`
implementation registered as a plain Kotlin instance in
`ToolCallRendererRegistry.RENDERERS` (a `List<ToolCallRenderer>`). The chat panel calls
`ToolCallRendererRegistry.find(alias)` once per tool invocation and routes the result to the matching renderer's
`render`
composable. Anything unknown falls through to a wildcard
`DefaultRenderer` (alias `"*"`).

A renderer is responsible for:

- Recognizing a server-side skill alias via `alias()` (e.g. `"Ran"`,
  `"Edited"`). Must match the value emitted by the server-side
  `Skill.alias`.
- Parsing the server `arguments` + `result` JSON into a `ToolCallContent`
  view-model via `parseContent(arguments, result)`.
- Choosing the row's icon (`iconKey()`) and localized label (`labelKey()`, a Gradum resource-bundle key under
  `messages/gradum/`).
- Drawing the actual row via `@Composable render(content, ctx)`. The shared `CommonCapsule` composable in
  `chat/ui/chat/skill/internal/`
  handles the standard icon + label + body + error-state layout, so most renderers only need to compute which fields to
  show in the body and which `ToolCallAction`s to expose (e.g. `OpenInEditor`,
  `CopyToClipboard`).

Adding a new tool call UI is a **plain Kotlin registration** — one line in the registry, no plugin.xml change. The chat
panel consults
[
`ToolCallRendererRegistry.RENDERERS`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill/spi/ToolCallRendererRegistry.kt)
(in a list-of-instances) and dispatches to the first renderer whose
`alias()` matches. To add a new alias:

1. Create a folder `chat/ui/chat/skill/<alias>/` containing a
   `<Alias>Renderer.kt` that implements
   [`ToolCallRenderer`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill/spi/ToolCallRenderer.kt).
2. Append `MyRenderer()` to `RENDERERS` in
   `ToolCallRendererRegistry.kt` (above the wildcard
   `DefaultRenderer()` entry, which must remain last).

**Why a plain list and not an IntelliJ Platform `ExtensionPoint`?**
The Platform's `ExtensionPointName` lookup path has several practical drawbacks for this use case:

- The `<extensionPoint>` element must be a direct child of
  `<idea-plugin>`, which is fragile to refactors and easy to break with a copy / paste of an `<extensions>` block.
- EP resolution goes through the IDE's `Extensions` area, which throws
  `IllegalArgumentException: Missing extension point` at the first chat render if anything is misconfigured — a
  non-recoverable runtime crash that takes down the entire chat panel.
- The EP cannot be defined per-alias in a way that's easy to discover: a developer has to read the EP interface + the
  Gradum source to learn the convention.
- Classloader isolation between Gradum and third-party plugins means the EP lookup frequently fails with "Unable to
  resolve extension point … in plugin dependencies" at parse time, even when the source looks correct.

A plain `val RENDERERS: List<ToolCallRenderer>` solves all four problems at the cost of one line of code per new alias.

The standard payload shape the renderer receives on the wire (an NDJSON `tool_call` event) is
`{tool, arguments, toolCallId, success, result}`.
`parseContent` is given the `arguments: Map<String, Any?>` and the parsed `result: Map<String, Any?>` (see
[`ResultParser.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill/spi/ResultParser.kt)
for the JSON-to-`Map` conversion that the server's `tool_call.result`
JSON string goes through).

The default `R` field rendering per alias (as the LLM sees it on the server side) is:

| Renderer            | Server alias | Skill             | Standard fields                                        |
|---------------------|--------------|-------------------|--------------------------------------------------------|
| `RanRenderer`       | `Ran`        | `run_cmd`         | `reason: String`, `command: String`                    |
| `EditedRenderer`    | `Edited`     | `edit_file`       | `path: String`, `linesAdded: Int`, `linesRemoved: Int` |
| `ReadRenderer`      | `Read`       | `read_file`       | `path: String`, `startLine: Int`, `endLine: Int`       |
| `SavedRenderer`     | `Saved`      | `save_file`       | `path: String`, `bytesWritten: Int`, `mode: String`    |
| `ExploredRenderer`  | `Explored`   | `explore_project` | `projectRoot: String`, `depth: Int`                    |
| `PlannedRenderer`   | `Planned`    | `to_do` (add)     | `tasks: List<String>`                                  |
| `CompletedRenderer` | `Completed`  | `to_do` (done)    | `task: String`                                         |

See
[`docs/PLUGIN_DEVELOPMENT.md`](../docs/PLUGIN_DEVELOPMENT.md) section 16
"Extending the plugin's tool call UI" for the full tutorial and worked example.

### 5.3 Streaming indicators

- **`ThinkingIndicator`** — a three-dot pulsing animation displayed while the server emits `thinking` chunks.
- **`ToolCallIndicator`** — a one-line alias + status icon shown beneath the active tool call (e.g. "Ran read_file");
  lives inside the per-skill renderers under `chat/ui/chat/skill/`.
- **`SweepLightText`** — the typewriter + shimmer effect used both on the welcome screen and on streaming response text.

### 5.4 Message timestamp

`formatTimestamp` is locale-aware (`Locale.getDefault()`) and produces:

- **Today** — `HH:mm` (e.g. `14:30`).
- **Yesterday** — `Yesterday HH:mm` (localized).
- **This year** — `MMM d` (e.g. `Jun 15`).
- **Older** — `N days ago` (localized).

The message list only renders a date separator when the day changes between consecutive messages; everything else gets a
per-message inline timestamp.

Source: [`ChatMessage.kt`](../plugin/src/main/kotlin/gradum/idea/chat/model/ChatMessage.kt),
[`ThinkingIndicator.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/ThinkingIndicator.kt),
[`SweepLightText.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/SweepLightText.kt),
[`MessageTimestamp.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/MessageTimestamp.kt); tool-call indicator
rows are in the per-skill renderers under [
`chat/ui/chat/skill/`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill).

---

## 6. Markdown rendering

Markdown in the chat goes through a **layered pipeline** rather than a single
`Markdown(...)` call, because the JetBrains Jewel `Markdown(...)` composable does not expose an `inlineContent` slot —
and a chat message that mixes **prose with inline code** is the most common case. Each layer handles one job; layers
fall through to one another when their preconditions fail.

```
                       raw assistant response text
                                  │
                                  ▼
                   splitMarkdownAtBlocks(text)      (BlockSplit.kt)
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                       ▼
       MarkdownSegment.Table                  MarkdownSegment.Plain
              │                                       │
              ▼                                       ▼
    ScrollableTable (custom Compose)     rememberInlineMarkdownRender(text)
    + sticky header (StickySection)      (InlineMarkdown, commonmark)
              │                              ┌────────┴────────┐
              │                              ▼                 ▼
              │                        InlineMarkdownRender?  null
              │                              │                 │
              │                              ▼                 ▼
              │                       Text(annotated,     Markdown(...)
              │                       inlineContent)      (Jewel native
              │                              │              renderer)
              ▼                              ▼
   ┌────────────────────────────────────────────────────────────┐
   │  ResponseBlock column  (one child per segment, in order)   │
   └────────────────────────────────────────────────────────────┘
```

### 6.1 Layer 1 — Fenced code blocks (`GradumCodeBlockRenderer`)

A Jewel `MarkdownBlockRenderer` that wraps every fenced code block with a copy button + the IDE's syntax highlighter
(resolved through the Jewel bridge to whatever language services the host project has loaded). This layer is registered
once via `LocalMarkdownBlockRenderer` and is what Jewel's `Markdown(...)` calls into.

Source file: [
`chat/ui/markdown/CodeBlockRenderer.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/CodeBlockRenderer.kt).

Since the 2026-07-30 hardening passes, the code-block surface is far richer than a plain "code + copy":

- **Collapse for long blocks** — any block over `CODE_COLLAPSE_LIMIT = 20`
  lines starts collapsed (the first 19 lines + a strip bar). The strip bar reads `gradum.code.expand` /
  `gradum.code.collapse` with a chevron and the hidden-line count; clicking it toggles with a 200 ms
  `animateContentSize`.
- **Line numbers** — an optional `LineNumberColumn` renders source line numbers aligned with the measured
  `TextLayoutResult`, separated by a 1 dp vertical divider. Wrap-around lines (a source line broken by soft wrap)
  share the same number. Toggled from the toolbar, only when the block is collapsible.
- **Soft wrap / horizontal scroll** — `ContainerOrScrollable` chooses a
  `HorizontalScrollContainer`(+bar) when soft wrap is off, or a plain `Box`
  when it's on. Toggled from the toolbar.
- **Insert as file** — a toolbar action hands the raw code + language to
  `onInsertAsFile`, which creates a new editor file from the snippet.
- **Sticky toolbar** — the toolbar registers itself in
  `LocalStickySectionRegistry` (see §6.5) and is re-rendered by the scroll owner as a floating header while the block
  scrolls out of view.
- **Indent guides** — for blocks with more than 20 lines, per-level vertical segments are drawn via `drawWithContent` on
  the code `Text` at each indentation depth, so nested code keeps its visual structure. The guides use the
  disabled-border color at 50% alpha.
- **Selection hygiene** — the toolbar, line-number column, and collapse strip are wrapped in `DisableSelection` so only
  the code itself is selectable.

### 6.2 Layer 2 — GFM tables (`Table.kt`)

GFM tables are **pulled out of the raw Markdown BEFORE** the rest is handed to `Markdown(...)`. Tables are rendered as
plain Compose inside a horizontally-scrollable `Box` (`ScrollableTable` composable inside
`chat/ui/markdown/Table.kt`), which gives a wide table its own horizontal scrollbar without also scrolling the
surrounding prose. Trying to wrap the whole `Markdown(...)` in `Box.horizontalScroll(...)` instead had the side effect
of making long inline code / URLs horizontally scrollable too — undesirable in a chat panel.
`splitMarkdownAtBlocks(text)` is the pure function that returns `List<MarkdownSegment>` (defined in `BlockSplit.kt`).

Since 2026-07-31 the table also registers **a sticky header row**: while the table scrolls out of view, the scroll owner
re-renders the header (
`RenderTableHeader`, shared between the in-flow table and the sticky overlay)
with the section's top-only rounded corners on top of the message column.

### 6.3 Layer 3 — Inline Markdown hijack (`InlineMarkdown.kt`)

This is the layer the LLM sees most. For every `MarkdownSegment.Plain`
we attempt a custom inline parse:

1. `commonmark-java` (already on the classpath via Jewel's
   `intellij.platform.jewel.markdown.core`, **zero new dependency**)
   reparses the segment and returns a `Document` AST.
2. We walk the AST. If the segment contains a list, heading, blockquote, fenced code block, `LinkReferenceDefinition`,
   or anything other than plain `Paragraph` blocks, the layer **bails out** and returns `null`
   — the caller then falls through to Jewel's native `Markdown(...)`. Lists in chat are rare; re-implementing list /
   heading / blockquote rendering is not worth it.
3. For accepted segments, we build:
    - one `AnnotatedString` with `SpanStyle` overlays (bold, italic, link)
        + `StringAnnotation` tags (URL) + **Private-Use-Area placeholder substrings** for inline code spans
    - one `inlineContent: Map<String, InlineTextContent>` keyed by the PUA placeholder, where each value renders one
      rounded chip via
      `InlineCodeChip`
4. The caller renders the whole segment with a single
   `Text(annotated, inlineContent = …)`, so line wrapping survives across bold / italic / link / code boundaries.

**Why hijack at all?** Two reasons:

- The chip needs a rounded background + 0.5dp border, not just a
  `SpanStyle` background color. The Jewel composable's
  `inlineContent` slot is closed (verified by `javap` on
  `MarkdownKt.class`), so we cannot inject a custom chip renderer through the native path.
- The v1 attempt to bypass `Markdown(...)` for any paragraph containing backticks lost **bold / italic / links** for the
  rest of the paragraph AND the chip rendered empty because the chip's internal `Text` inherited the editor style's
  `lineHeight` (1.5x) and got clipped to ~0 visible pixels. v2 takes the segment wholesale and walks the CommonMark AST
  ourselves — no `Markdown(...)` involved, so no styling escape hatches fire.

**Chip visual spec** (`InlineCodeChip` inside `InlineMarkdown.kt`):

- 4dp rounded corners (`RoundedCornerShape(4.dp)`).
- Background `tint.copy(alpha = 0.12f)`, border 0.5dp
  `tint.copy(alpha = 0.30f)`.
- Padding `horizontal = 4.dp, vertical = 2.dp`.
- Font: same family as the editor text; weight `Medium`; **explicit
  `lineHeight = fontSizeSp.sp`** (1.0x — fixes the v1 zero-pixel bug).
- `maxLines = 1`, `softWrap = false`.

**Bail-out conditions** (return `null` → caller uses `Markdown(...)`):

- Blank input.
- Input contains any non-`Paragraph` block (list, heading, blockquote, fenced code, `LinkReferenceDefinition`).
- `commonmark` throws (defensive — `commonmark` is robust, but a bug in the AST walker or chip rendering should not take
  down the entire chat bubble).
- All 49 unit tests in `GradumInlineMarkdownTest.kt` pin these conditions (bail-out, plain, bold-italic, code, link,
  mixed, multi-para, escape, soft break, defensive).

### 6.4 Block-level splitter + LaTeX (`BlockSplit.kt`)

Before any layer runs, the raw response text goes through
`splitMarkdownAtBlocks(text)` in `chat/ui/markdown/BlockSplit.kt`. Beside GFM tables it also recognizes:

- **Block LaTeX** — `$$` markers on their own lines wrap a formula (`LatexBlock`); the block is serialized back into the
  prose stream so the formula survives table/plain splitting, then rendered by
  `LatexRenderer.kt` (centered, padded vertically).
- **Task lists** — `BlockRenderer.kt` parses `- [ ]` / `- [x]` markers via
  `extractTaskListMarker` and renders `TaskListItem` composables with themed checkboxes (`enabled = false` — task lists
  in chat messages are read-only display).
- **Footnote definitions** — `[^label]: …` blocks are collected into a
  `FootnoteRegistry` (see §6.6) so reference chips can jump to them.

### 6.5 Layer 4 — Native `Markdown(...)` (the fallback)

When a `Plain` segment bails out of `InlineMarkdown.kt`, the caller hands it to `Markdown(markdown = segment.text, modifier = …,
onUrlClick = …)`. This is the original Jewel path: it renders everything from headings to nested lists to thematic
breaks. It also reaches back into Layer 1 to pick up the `GradumCodeBlockRenderer` for fenced code blocks.

**New inline features ride inside `rememberInlineMarkdownRender` itself**
(not the native fallback), so they are available even for plain paragraphs:

- **Inline LaTeX** — `$…$`, `$$…$$` (block-scoped when alone on a line), and `\(…\)` forms are recognized *before*
  CommonMark parsing (`InlineMarkdown.kt`). `\(…\)`, `$…$`, and `$$…$$` formulas are replaced with Private-Use-Area
  placeholder markers so CommonMark's backslash-escape / emphasis rules cannot mangle them; the placeholder width is
  measured live with `LatexMeasurerState` (falling back to a character-width estimate), and the chip renders via the
  `com.hrm.latex`-based `LatexRenderer`. Cached per formula text; distinct PUA ranges for the `\(` / `$` forms.
- **Footnotes** — `[^label]` reference chips look up their label's definition position in the `FootnoteRegistry` and ask
  the scroll owner to animate to it; the definition chip flashes on arrival.
- **Clickable links** — bare URLs and `<https://…>` autolinks are detected before CommonMark and rendered as annotated
  clickable spans.

### 6.6 Styling + sticky sections (`Styling.kt`, `StickySection.kt`, `FootnoteRegistry.kt`)

- **Styling** (`Styling.kt`): configures colors, fonts, list markers, and link styling from `JewelTheme`. A `remember`
  block at the call site keeps the styling stable across recompositions. `rememberGradumParagraphTextStyle()`
  lets the inline hijack and the native `Markdown(...)` path produce visually identical paragraph text.
- **Sticky sections** (`StickySection.kt`): a `StickySectionRegistry`
  (provided via `LocalStickySectionRegistry`) lets the message column track each code block toolbar and table header's
  window bounds (`topInColumn` /
  `bottomInColumn`). While content scrolls, the scroll owner re-renders the topmost *active* toolbar/header over the
  column — this is what powers the sticky code toolbar and the sticky table header. Fading: the fade zone starts at 1.5
  header-heights and completes at 1.0 header-heights.
- **Footnotes** (`FootnoteRegistry.kt`): per-message registry mapping footnote labels to definition positions; positions
  are read live from the scrollable column, shortest-path jump picks the nearest definition, and
  `onJumpComplete` flashes the definition chip.

### 6.7 Streaming integration

`SweepLightText` is fed the accumulated response string so the user sees a typewriter + shimmer effect as the model
emits tokens. Each completed response chunk re-runs the full pipeline above, so tables / chips / bold / links / LaTeX /
footnotes are stable across the stream.

Source: [`chat/ui/markdown/BlockSplit.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/BlockSplit.kt),
[`chat/ui/markdown/BlockRenderer.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/BlockRenderer.kt),
[
`chat/ui/markdown/CodeBlockRenderer.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/CodeBlockRenderer.kt),
[`chat/ui/markdown/Table.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/Table.kt),
[`chat/ui/markdown/InlineMarkdown.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/InlineMarkdown.kt),
[`chat/ui/markdown/Styling.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/Styling.kt),
[`chat/ui/markdown/StickySection.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/StickySection.kt),
[`chat/ui/markdown/FootnoteRegistry.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/FootnoteRegistry.kt),
[`chat/ui/markdown/LatexRenderer.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/LatexRenderer.kt),
[
`chat/ui/markdown/LatexBlockExtension.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/LatexBlockExtension.kt),
[`chat/ui/markdown/NodeChildren.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/NodeChildren.kt). Tests: [
`GradumInlineMarkdownTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumInlineMarkdownTest.kt),
[`GradumMarkdownTableTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumMarkdownTableTest.kt),
[`GradumLatexTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumLatexTest.kt),
[`GradumFootnoteTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumFootnoteTest.kt),
[`GradumTaskListTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumTaskListTest.kt),
[
`GradumNestedCodeBlockTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumNestedCodeBlockTest.kt),
[`GradumLinkStylingTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumLinkStylingTest.kt),
[
`GradumMarkdownBlockSplitTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumMarkdownBlockSplitTest.kt),
[`GradumInlineSegmentTest.kt`](../plugin/src/test/kotlin/gradum/idea/chat/ui/markdown/GradumInlineSegmentTest.kt).

---

## 7. Input panel

The chat input is a layered composition from outer to inner:

```
ChatInputSection               (top-level section, only mounted in chat screen)
  └── ChatInputPanel           (composes toolbar + textarea + attachments)
        ├── ChatToolbar        (add-menu, permission selector, send/stop)
        ├── AttachmentBar      (current attachments row)
        ├── TextField          (auto-growing multi-line)
        ├── ModelSelectorBar   (current model + auto + pinned + all)
        └── ExternalLink       (GitHub feedback)
```

The state for this whole tree is a `ChatInputState` snapshot; the callbacks form a `ChatInputActions` data class.
`ChatInputState` is recomposed via
`mutableStateOf` on the session — never as a `mutableStateListOf` — to keep Compose's snapshot model predictable.

Source: [`ChatInputSection.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ChatInputSection.kt),
[`ChatInputPanel.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ChatInputPanel.kt),
[`ChatToolbar.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ChatToolbar.kt),
[`ChatInputState.kt`](../plugin/src/main/kotlin/gradum/idea/chat/input/ChatInputState.kt),
[`PreviewText.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/PreviewText.kt).

---

## 8. Model selection

The model selector is a `SelectorButton` (icon + label + chevron) that opens a `PopupMenu` with three sections:

1. **`Auto`** — always present, highlighted when `isAutoSelected` is true.
2. **Pinned** — only when the user has pinned at least one model.
3. **All models** — everything else, minus pinned entries.

### 8.1 Auto mode (server-recommended)

When the user picks **Auto**, the plugin does **not** flip a flag and stop there — it actively selects the server's
recommendation. The recommender runs server-side on every `/models` request and is described in detail in
[`ARCHITECTURE.md`](ARCHITECTURE.md#model-recommendation); the short version of the algorithm is:

1. **Context window** — the universal baseline (`min(contextLimit, 200K) / 2000`).
2. **Cloud preference** — cloud models get a flat `+200` bonus because the project is local-first by intent, not by
   capability.
3. **Local parameter count** — `paramsB × 1.5`, where `paramsB` is parsed out of the model name with a
   `(\d+(?:\.\d+)?)b` regex. A 70B beats a 7B decisively, but not so much that a 70B blows past a stronger cloud
   candidate.
4. **Memory gate** — a local model whose estimated VRAM (`paramsB × 0.8`, Ollama Q4 average) exceeds the user's current
   free RAM (sampled fresh per request, then discounted 25% for OS/IDE overhead) is hit with a `-500` penalty. This is
   large enough to demote it below every cloud candidate and every smaller local alternative, but the entry stays
   selectable for users who insist.
5. **Capability flags** — `+20` for reasoning, `+10` for tool-calling,
   `+5` for vision.

When the user is in **Auto** mode the selector button renders as
`Auto - {picked model name}` so the user can see at a glance which model the recommender picked, and the leading "Auto"
makes the implicit mode visible without forcing them to reopen the menu. The menu's Auto row stays highlighted to keep
the mode signal in two places.

When the user is in manual mode, only the model name is shown — the
`Auto -` prefix is suppressed to avoid noise.

### 8.2 Provider icons

`GradumIcons.resolveModelIcon(modelName)` resolves a model to a provider-branded icon by scanning the lower-cased name
for the first keyword hit. The mapping is hard-coded in `PROVIDER_KEYWORD_MAP` and covers every cloud and self-hosted
provider the project advertises:

| Keyword                 | Provider icon |
|-------------------------|---------------|
| `qwen`                  | Alibaba       |
| `claude`                | Anthropic     |
| `deepseek`              | Deepseek      |
| `gemini`, `gemma`       | Google        |
| `llama`                 | Meta          |
| `minimax`               | MiniMax       |
| `mistral`, `mixtral`    | Mistral       |
| `gpt`, `o1`, `o3`, `o4` | OpenAI        |
| `grok`                  | xAI           |
| `mimo`                  | Xiaomi        |
| `glm`                   | ZhipuAI       |

The eleven provider folders under `icons/model-provider/` each ship a light/dark pair so the icon tracks the IDE theme.
`ModelSelectorBar`
prefers this resolution for the selector button, and
`AssistantChatBubble` uses it to render a small leading icon on every assistant message.

### 8.3 Pin / unpin

Every non-Auto model in the menu has a pin toggle. Pinned models surface in their own section above "All models" and
survive across sessions within the project.

### 8.4 Model name formatting

Raw names from the wire look like `qwen2.5-coder-32b-instruct` or
`lmstudio-community/qwen2.5-7b`. `formatModelName` strips the
`username/model` prefix (LM Studio convention), drops the trailing size tag, and looks the remainder up in a
hand-curated display table that maps `qwen2.5-coder` → `Qwen 2.5 Coder`. Unrecognised names fall back to a title-cased
hyphen substitution.

Source: [`ModelSelectorBar.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ModelSelectorBar.kt),
[`ModelNameFormatter.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ModelNameFormatter.kt),
[`SelectorButton.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/common/SelectorButton.kt),
[`GradumIcons.kt`](../plugin/src/main/kotlin/gradum/idea/utils/GradumIcons.kt).

---

## 9. Permission selector

A dropdown that controls the active `ToolMode`:

- **`READ_ONLY`** — `explore_project`, `read_file`, `run_cmd`. No `edit_file`, no `save_file`, no `to_do`.
- **`SINGLE_STEP`** — everything in read-only plus `to_do`.
- **`WRITE`** — every Skill is exposed.

The default for a new session is `READ_ONLY`; the user promotes to
`SINGLE_STEP` or `WRITE` from the dropdown once they understand what each mode unlocks.

The selector writes the chosen mode into a wire-format string and the plugin's `PermissionSelector` enforces the same
gate server-side via
`Skill.allowedToolModes`.

Source: [`PermissionSelector.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/PermissionSelector.kt),
[`Skill.kt`](../src/main/kotlin/gradum/skill/Skill.kt),
[`SkillRegistry.kt`](../src/main/kotlin/gradum/skill/SkillRegistry.kt).

---

## 10. Attachments

The user can attach files and folders to a message. The bar lives between the toolbar and the text field.

- **Maximum count** — `MAX_ATTACHMENTS = 10`. Files, images, and text attachments all share the same pool. The add
  button is disabled (with a tooltip explaining why) once the limit is reached.
- **Add surface** — `AddContextPopup` exposes file pickers for both files and directories. Adding a directory walks it
  and freezes the result at send time so subsequent edits in the IDE do not affect the in-flight message.
- **Remove** — every file chip has a remove button; deletion is non-destructive (the file on disk is untouched).
- **Long-paste detection** — any text longer than 200 characters pasted into the input is automatically lifted out as a
  `text/plain` attachment and removed from the input. The detection uses a reactive diff against the `textState`
  subscription rather than a `KeyEvent` interceptor, so it works equally well for paste, drag-and-drop, and IME input.
- **Collapsible** — the attachment list is collapsible with an animated visibility toggle so a full bar of five files
  does not crowd the input on small tool windows.

Source: [`AttachmentBar.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/AttachmentBar.kt),
[`AddContextPopup.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/AddContextPopup.kt),
[`FileItem.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/input/FileItem.kt),
[`Attachments.kt`](../plugin/src/main/kotlin/gradum/idea/editor/Attachments.kt),
[`EditorContext.kt`](../plugin/src/main/kotlin/gradum/idea/editor/EditorContext.kt).

---

## 11. State management

`GradumChatSession` is a `Service(Service.Level.PROJECT)`. It owns the in-flight UI state and survives the tool window
being collapsed and re-expanded.

### 11.1 Persistent state

| Field              | Type                                 | Notes                                           |
|--------------------|--------------------------------------|-------------------------------------------------|
| `messages`         | `SnapshotStateList<ChatMessage>`     | The visible chat history.                       |
| `models`           | `SnapshotStateList<ModelInfo>`       | Last `/models` response.                        |
| `pinnedModels`     | `SnapshotStateList<ModelInfo>`       | User-pinned models.                             |
| `recommendedModel` | `ModelInfo?`                         | Server's current Auto pick.                     |
| `selectedModel`    | `ModelInfo?`                         | The model that will be used for the next send.  |
| `isAutoSelected`   | `Boolean`                            | True when in Auto mode.                         |
| `modelsLoaded`     | `Boolean`                            | True after the first successful `/models` call. |
| `toolMode`         | `String`                             | The active `ToolMode` (wire format).            |
| `promptVariant`    | `String`                             | `auto` / `cloud` / `local`.                     |
| `attachments`      | `SnapshotStateList<AttachedContext>` | Pending attachments.                            |

### 11.2 Model polling

The session starts a background poller that ticks every
`POLL_INTERVAL_MS = 5_000` (5 seconds). Each tick fires a fresh
`/models` request and, if either the model roster or the server's recommendation has changed since the last tick,
applies the update. The recommendation can flip even when the model list is unchanged — for example, when the user
closes another app, freeing enough memory for a larger local model to win the recommender.

The poller is implemented as `tickerFlow().flatMapLatest { fetchModelsOnce() }`
so a slow request that overlaps with a tick is canceled by the upstream emission rather than racing the next one.

Source: [`GradumChatSession.kt`](../plugin/src/main/kotlin/gradum/idea/chat/state/GradumChatSession.kt).

---

## 12. HTTP API client

`GradumApiClient` is a thin wrapper around the JDK 11 `HttpClient`. The plugin only ever calls three endpoints.

### 12.1 `GET /models`

Returns a JSON object of the shape:

```json
{
  "models": [
    {
      "name": "...",
      "provider": "...",
      "server": "...",
      "serverName": "...",
      "contextLimit": 0,
      "reasoning": false,
      "toolCall": false,
      "openWeights": false,
      "attachment": false
    }
  ],
  "recommended": {
    "name": "...",
    "...": "..."
  }
}
```

`recommended` is nullable for backward compatibility — older server builds that do not include the field decode cleanly
thanks to
`Json { ignoreUnknownKeys = true }`.

### 12.2 `POST /events` (NDJSON stream)

Sends a single user turn and consumes the streamed agent events as NDJSON. The parser is per-line resilient: a malformed
line is logged at
`warn` level and skipped, so a single bad event does not break the stream. The four event types mirror the `ChatEvent`
subtypes in
[Section 5](#5-message-event-timeline).

### 12.3 `POST /stop`

Tells the server to abort the in-flight `Agent` for a given `sessionId`. The plugin calls this when the user clicks the
**Stop** button in the chat toolbar; the server replies with `{"status": "stopped", ...}` and the client drops the
`isSending` flag.

### 12.4 Error handling

A typed `ErrorCode` enum is shared between the server and the plugin (16 codes, including `MODEL_TIMEOUT`,
`TOOL_BLOCKED`, `READ_ONLY_VIOLATION`,
`RATE_LIMITED`, etc.). The plugin maps codes to localized, user-friendly messages — never raw stack traces.

Source: [`GradumApiClient.kt`](../plugin/src/main/kotlin/gradum/idea/chat/api/GradumApiClient.kt),
[`ErrorCode.kt`](../plugin/src/main/kotlin/gradum/idea/chat/model/ErrorCode.kt),
[`ErrorMessages.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/ErrorMessages.kt).

---

## 13. i18n

Every user-visible string in the plugin is loaded through `GradumBundle`, which extends IntelliJ's `DynamicBundle`. The
bundle ships two locales:

- **`en`** — `messages/GradumBundle.properties` (default).
- **`zh_CN`** — `messages/GradumBundle_zh_CN.properties`.

There are **251 keys** in the English file and **249 in the Chinese file**
(lockstep — the few-key delta is transient mid-refactor noise). Both files are kept in lockstep — a key that exists in
one must exist in the other; the bundle is hardened with two safety nets:

1. **Startup probe** — the `init` block of `GradumBundle` looks up a sentinel key (e.g. `gradum.toolwindow.welcome`) to
   confirm the active locale's resource is on the classpath.
2. **Per-key fallback** — `GradumBundle.message(key, ...)` catches
   `MissingResourceException` and returns `???<key>???` so a missing key is obvious in the UI without breaking layout.

The Chinese copy is not a direct translation; it is curated for cultural relevance (Chinese proverbs, regional phrasing)
and the English copy is deliberately terse and tech-flavored.

Source: [`GradumBundle.kt`](../plugin/src/main/kotlin/gradum/idea/utils/GradumBundle.kt),
[`GradumBundle.properties`](../plugin/src/main/resources/messages/GradumBundle.properties),
[`GradumBundle_zh_CN.properties`](../plugin/src/main/resources/messages/GradumBundle_zh_CN.properties).

---

## 14. Icons

The plugin ships **103 SVG icons + one TTF font** (`GoogleSans.ttf`) under
`plugin/src/main/resources/icons/`, organized by purpose:

- `auto/`, `build/`, `cloud/`, `local/` — model-mode indicators.
- `cmd/`, `edit/`, `explore/`, `web/`, `file-type/`, `save/`, `send/`, `search/`, `tools/` — tool affordances.
- `feat/chat/`, `feat/code/`, `feat/question/`, `feat/text/` — quick-start tiles.
- `file-type/` — language-typed file glyphs (Kotlin, Python, TypeScript, JSX, PHP, …).
- `model-provider/` — brand logos for 11 providers, each with a light/dark pair for IDE theme parity.
- `like/`, `like-selected/`, `dislike/`, `dislike-selected/`, `scroll-up/`, `scroll-down/`,
  `expand-all/`, `collapse-all/`, `soft-wrap/`, `numbered-list/`, `table/`, `markdown/`,
  `image/`, `warning/`, `vison/`, `logo/` — UI affordances.

Every icon has a light/dark pair (suffix `_dark`) so it tracks the IDE theme. `GradumIcons` is the single source of
truth for icon lookups; UI code never hard-codes an icon path.

Source: [`GradumIcons.kt`](../plugin/src/main/kotlin/gradum/idea/utils/GradumIcons.kt),
[`icons/`](../plugin/src/main/resources/icons).

---

## 15. Editor integration

The plugin reads three things out of the host IDE:

- **Current selection** — `EditorContext` snapshots the user's text selection in the focused editor at the moment a chat
  message is sent, so the LLM can quote it back.
- **Open file path** — the active file's path is added to the conversation as an implicit attachment.
- **Pending messages** — `PendingMessage` is the data class that snapshots one user message (text + frozen attachments)
  while it is queued. The queue itself lives on `GradumChatSession.pendingMessages` and is capped at
  `MAX_PENDING_MESSAGES = 2`. When the streaming turn finishes, the next pending message is dispatched automatically.

Source: [`EditorContext.kt`](../plugin/src/main/kotlin/gradum/idea/editor/EditorContext.kt),
[`PendingMessage.kt`](../plugin/src/main/kotlin/gradum/idea/editor/PendingMessage.kt).

---

## 16. Styling conventions

- **Theme** — every composable pulls from `JewelTheme` (`globalColors`,
  `typography`, `editorColors`). No hard-coded hex colors; no
  `Color.Red` / `Color.Blue` literals.
- **Spacing** — all paddings, gaps, and margins come from `GradumSpacing`
  in `Spacing.kt`. Popup items use
  `Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)`; section headers use `vertical = 4.dp`;
  icon-to-text gaps use
  `Spacer(Modifier.width(6.dp))`. This is the canonical spacing rule for any new popup or menu in the plugin.
- **Icon-to-tooltip semantics** — pin and unpin, share and unshare, and similar toggle pairs use a state-based icon: the
  icon for the *active*
  state is the "selected" or "filled" variant, the icon for the *inactive*
  state is the "outline" variant. Tooltips mirror the icon's meaning, not the underlying state.
- **Text composables** — every `Text` uses the named `text =` parameter (not positional). This is enforced project-wide
  and lints clean.
- **Single-line `if`** — `if (cond) doThing()` without braces is the default; only wrap when the body is non-trivial.
- **Wildcard imports** — the project allows wildcard imports for the four Compose-for-Desktop key packages
  (`androidx.compose.foundation.layout.*`,
  `org.jetbrains.jewel.ui.component.*`, `androidx.compose.foundation.*`,
  `androidx.compose.runtime.*`); everything else uses explicit imports.

Source: [`Spacing.kt`](../plugin/src/main/kotlin/gradum/idea/utils/Spacing.kt),
[`IconTooltipButton.kt`](../plugin/src/main/kotlin/gradum/idea/chat/ui/common/IconTooltipButton.kt),
[`CONVENTIONS.md`](CONVENTIONS.md),
[`CODING_STANDARDS_KOTLIN.md`](CODING_STANDARDS_KOTLIN.md).

---

## 17. Accessibility & UX guarantees

- **Disabled state tooltips** — every disabled control has a tooltip explaining *why* it is disabled (e.g. "Maximum of
  10 attachments" on the add button when the limit is reached). No silent greying-out.
- **Consistent chat patterns** — user and assistant bubbles, message timestamps, copy buttons, and error states share
  one visual layout across the welcome screen, the chat list, and any future surface (the rule is documented in
  `CONVENTIONS.md`).
- **Date separators** — the chat list inserts a separator only when the day changes between consecutive messages;
  per-message timestamps use the four-tier format described in [Section 5.4](#54-message-timestamp).
- **Typewriter welcome** — the welcome screen uses an anti-repetition rotating greeting so the user does not see the
  same line twice in a row within a session.
- **Streaming animation** — `SweepLightText` is reused for both the welcome greeting and the live response stream, so
  the user gets one visual language for "text that is arriving".

---

## 18. File-by-file index

| Path                                       | Role                                                                                                                                                                                              |
|--------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GradumToolWindowFactory.kt`               | Chat tool-window factory, "New Chat" action, top-level Compose tab.                                                                                                                               |
| `GradumGitAnalysisToolWindowFactory.kt`    | Git analysis tool-window factory; home screen + scan states + banner + findings + commit panel (§19).                                                                                             |
| `GradumGitAnalysisService.kt`              | Process-wide analysis engine: two-phase scan, JSONL parser, `AuditFinding` model, quality/branch state, script resolution (§19).                                                                  |
| `AuditTreeItem.kt`                         | Sealed tree node model: `Branch` / `Group` / `SeverityGroup` / `Finding` / `LoadMore` + severity icons.                                                                                           |
| `AuditFindingsTree.kt`                     | Jewel `Tree` of audit findings; branch wrapper, load-more, expand/collapse, reviewed strikethrough, severity/group rows (§19).                                                                    |
| `GitAuditActionBar.kt`                     | Vertical action bar after a scan: close / refresh / preview / expand-all / group-by / mark-reviewed / copy JSON (§19).                                                                            |
| `CommitInfoPanel.kt`                       | Resizable commit-details side panel (subject, body, time bar, pin, GitHub, copy, +N/-N) (§19).                                                                                                    |
| `GradumBanner.kt`                          | Reusable Jewel-based banner (`Success` / `Warning` / `Error`) with icon + dismiss link + close icon (§19).                                                                                        |
| `ImageUpload.kt`                           | Image-attachment upload helper (paste / drop → file on disk → `AttachedContext`).                                                                                                                 |
| `utils/GradumBundle.kt`                    | i18n bundle with startup probe and per-key fallback.                                                                                                                                              |
| `utils/GradumIcons.kt`                     | Icon registry + provider / model lookups.                                                                                                                                                         |
| `utils/Spacing.kt`                         | `GradumSpacing` token object.                                                                                                                                                                     |
| `chat/api/GradumApiClient.kt`              | HTTP client (`/events`, `/models`, `/stop`).                                                                                                                                                      |
| `chat/input/ChatInputState.kt`             | `ChatInputState` + `ChatInputActions` data classes.                                                                                                                                               |
| `chat/model/ChatMessage.kt`                | `ChatEvent` / `RenderBlock` / `ChatMessage` model + `formatTimestamp`.                                                                                                                            |
| `chat/model/ErrorCode.kt`                  | Shared 16-code error enum.                                                                                                                                                                        |
| `chat/model/ModelInfo.kt`                  | Wire shape for a single model from `/models`.                                                                                                                                                     |
| `chat/state/GradumChatSession.kt`          | Project-level service, state owner, model poller.                                                                                                                                                 |
| `chat/ui/ChatScreen.kt`                    | Top-level chat screen composable.                                                                                                                                                                 |
| `chat/ui/JumpToBottomButton.kt`            | Solid-background jump-to-bottom button with 0.5dp border (auto-shows when the list is scrolled away from the latest message).                                                                     |
| `chat/ui/markdown/CodeBlockRenderer.kt`    | Markdown fenced code block renderer (Layer 1 of the pipeline): copy, soft-wrap, line numbers, insert-as-file, collapse >20 lines, sticky toolbar, indent guides.                                  |
| `chat/ui/markdown/InlineMarkdown.kt`       | Custom CommonMark inline parser + chip renderer + inline LaTeX + footnotes (Layer 3 of the pipeline).                                                                                             |
| `chat/ui/markdown/Styling.kt`              | Markdown styling config from `JewelTheme` + `rememberGradumParagraphTextStyle()`.                                                                                                                 |
| `chat/ui/markdown/Table.kt`                | GFM table parser + `ScrollableTable` Compose + sticky header (Layer 2 of the pipeline).                                                                                                           |
| `chat/ui/markdown/BlockSplit.kt`           | `splitMarkdownAtBlocks` — splits raw text into tables / blocks / plain segments; LaTeX block round-trip.                                                                                          |
| `chat/ui/markdown/BlockRenderer.kt`        | Custom block renderer: headings, blockquotes, paragraphs, task-list items (`RenderTaskListItem`).                                                                                                 |
| `chat/ui/markdown/NodeChildren.kt`         | Shared children helpers for the block renderer.                                                                                                                                                   |
| `chat/ui/markdown/LatexBlockExtension.kt`  | CommonMark block parser for `$$` LaTeX blocks (`LatexBlock`).                                                                                                                                     |
| `chat/ui/markdown/LatexRenderer.kt`        | `Latex` renderer wrappers for block + inline formulas.                                                                                                                                            |
| `chat/ui/markdown/FootnoteRegistry.kt`     | Per-message footnote-label → definition-position registry with nearest-jump + flash.                                                                                                              |
| `chat/ui/markdown/StickySection.kt`        | `StickySectionRegistry` tracking toolbar/header bounds in scroll-column space.                                                                                                                    |
| `chat/ui/chat/AssistantChatBubble.kt`      | Assistant message bubble; the `ResponseBlock` here drives the layered Markdown pipeline.                                                                                                          |
| `chat/ui/chat/ChatMessageList.kt`          | Scrollable list + day-change separators.                                                                                                                                                          |
| `chat/ui/chat/ErrorMessages.kt`            | Localised, code-driven error messages.                                                                                                                                                            |
| `chat/ui/chat/MessageAttachmentList.kt`    | Collapsible attachment list inside the user bubble.                                                                                                                                               |
| `chat/ui/chat/MessageAttachmentPreview.kt` | Inline thumbnail preview for image attachments inside the user bubble.                                                                                                                            |
| `chat/ui/chat/MessageCopyButton.kt`        | Copy button + tooltip semantics.                                                                                                                                                                  |
| `chat/ui/chat/MessageTimestamp.kt`         | Bubble timestamp footer.                                                                                                                                                                          |
| `chat/ui/chat/SweepLightText.kt`           | Typewriter + shimmer animation.                                                                                                                                                                   |
| `chat/ui/chat/ThinkingIndicator.kt`        | Pulsing dots during thinking.                                                                                                                                                                     |
| `chat/ui/chat/skill/spi/`                  | Tool-call renderer SPI: `ToolCallRenderer`, `ToolCallContent`, `ToolCallAction`, `ToolCallRenderContext`, `ToolCallRendererRegistry`, `ResultParser`.                                             |
| `chat/ui/chat/skill/internal/`             | Shared internals used by all renderers: `CommonCapsule` (icon + label + body), `CommonActionButtons` (`OpenInEditor`, `ViewDiff`, `CopyToClipboard`), `ErrorsPanel` (failed-skill error display). |
| `chat/ui/chat/skill/<Alias>Renderer.kt`    | One file per server skill alias: `Ran`, `Edited`, `Read`, `Saved`, `Explored`, `Planned`, `Completed`, `Grep`, `Glob`, plus the wildcard `DefaultRenderer` for `*`.                               |
| `chat/ui/chat/UserChatBubble.kt`           | User message bubble.                                                                                                                                                                              |
| `chat/ui/common/DiffViewer.kt`             | Side-by-side / unified diff viewer used by `ViewDiffButton` for `edit_file` results.                                                                                                              |
| `chat/ui/common/IconTooltipButton.kt`      | Canonical icon button with tooltip.                                                                                                                                                               |
| `chat/ui/common/SelectorButton.kt`         | Canonical selector button (icon + label + chevron).                                                                                                                                               |
| `chat/ui/home/QuickStartSection.kt`        | Welcome quick-start tiles (4 × 5 variants).                                                                                                                                                       |
| `chat/ui/home/WelcomeScreen.kt`            | Welcome screen composable.                                                                                                                                                                        |
| `chat/ui/input/AddContextPopup.kt`         | File / directory add menu.                                                                                                                                                                        |
| `chat/ui/input/AttachmentBar.kt`           | Pending attachments row.                                                                                                                                                                          |
| `chat/ui/input/ChatInputPanel.kt`          | Composes toolbar + textarea + bar.                                                                                                                                                                |
| `chat/ui/input/ChatInputSection.kt`        | Top-level chat input section.                                                                                                                                                                     |
| `chat/ui/input/ChatToolbar.kt`             | Add menu, permission selector, send/stop.                                                                                                                                                         |
| `chat/ui/input/FileItem.kt`                | Single attachment chip.                                                                                                                                                                           |
| `chat/ui/input/ModelNameFormatter.kt`      | Raw-name → display-name lookup.                                                                                                                                                                   |
| `chat/ui/input/ModelSelectorBar.kt`        | Model selector with Auto / Pinned / All.                                                                                                                                                          |
| `chat/ui/input/PermissionSelector.kt`      | Three-tier permission dropdown.                                                                                                                                                                   |
| `chat/ui/input/PreviewText.kt`             | Text-field preview / hint composable.                                                                                                                                                             |
| `editor/Attachments.kt`                    | `AttachedContext` model + file/dir freezing.                                                                                                                                                      |
| `editor/EditorContext.kt`                  | Current editor selection / file snapshot.                                                                                                                                                         |
| `editor/PendingMessage.kt`                 | In-flight message queue.                                                                                                                                                                          |
| `ui/GradumState.kt`                        | Shared chat UI state holder.                                                                                                                                                                      |
| `ui/GradumCallbacks.kt`                    | Callback facade wiring chat actions to the session.                                                                                                                                               |
| `ui/GradumUI.kt`                           | Top-level shared UI composition.                                                                                                                                                                  |

### 18.1 Test files (plugin module)

| Path                                               | Role                                                                                      |
|----------------------------------------------------|-------------------------------------------------------------------------------------------|
| `GradumGitAuditFindingTest.kt`                     | Pinned tests for finding parsing, param formatting, hashing, GitHub URL resolution (§19). |
| `chat/ui/markdown/GradumFootnoteTest.kt`           | Footnote registry / jump tests.                                                           |
| `chat/ui/markdown/GradumInlineMarkdownTest.kt`     | 49 cases pinning bail-out / parse / chip / link behaviour of Layer 3.                     |
| `chat/ui/markdown/GradumInlineSegmentTest.kt`      | Segmented inline parsing cases.                                                           |
| `chat/ui/markdown/GradumLatexTest.kt`              | Inline / block LaTeX placeholder tests.                                                   |
| `chat/ui/markdown/GradumLinkStylingTest.kt`        | Link styling + clickable links tests.                                                     |
| `chat/ui/markdown/GradumMarkdownBlockSplitTest.kt` | Pinned tests for `splitMarkdownAtBlocks`.                                                 |
| `chat/ui/markdown/GradumMarkdownTableTest.kt`      | Pinned tests for table parsing + `ScrollableTable` of Layer 2.                            |
| `chat/ui/markdown/GradumNestedCodeBlockTest.kt`    | Nested code block rendering tests.                                                        |
| `chat/ui/markdown/GradumTaskListTest.kt`           | Task-list marker parsing / rendering tests.                                               |
| `chat/ui/input/ModelNameFormatterTest.kt`          | Pinned cases for the wire-name → display-name lookup.                                     |

---

## 19. Git analysis tool window

Since July 2026 the plugin ships a second, self-contained tool window — the **Git analysis** ("Audit") panel — that
audits the project's Git history and surfaces SXXXX findings and a project quality band. It is the largest feature added
after the chat panel and runs its own lifecycle end-to-end: script resolution → two-phase scan → live finding tree →
commit-details panel → summary banner.

### 19.1 Home screen

When no scan has run (or after **Close Report** / **Back to Home**), the tool window shows a centered home column:

- **Title** — "Audit your Git commits with Gradum" with the color logo.
- **Feature list** — three bullet points describing the audit (identify risk patterns across history, estimate a health
  score, see results in-place). Rendered by `GitAuditFeatureList` with the chat's unordered-list styling.
- **Begin Analysis** (`DefaultButton`) starts the scan.
- **View full edition** (`ExternalLink`) placeholder link.

### 19.2 Scan lifecycle (`GradumGitAnalysisService`)

`GradumGitAnalysisService` is a process-wide `object` that owns every scan:

| State      | Meaning                                           |
|------------|---------------------------------------------------|
| `IDLE`     | No report open (home screen).                     |
| `SCANNING` | Child process running; progress is streamed.      |
| `SUCCESS`  | Process exited cleanly; findings available.       |
| `FAILED`   | Process errored; `lastErrorMessage` explains why. |

State is exposed as Compose `mutableStateOf` fields (`scanState`,
`currentHash`, `currentCommit`, `totalCommits`, `lastErrorMessage`,
`auditFindings`, `overallLevel`, `qualityBand`, `currentBranch`,
`scanCompletedAt`, `bannerDismissed`), so the UI recomposes live without any manual refresh wiring.

**Script resolution** (`resolveScript`) prefers, in order:

1. An executable `scripts/git_stats_log/git_stats.py` found by walking up from the project base path,
2. then one found by walking up from the running plugin JAR's directory,
3. finally the bundled resource, extracted to `PathManager.getTempDir()/gradum/gitstats`
   (config `scripts/configs.jsonc` is extracted alongside it and the script is marked executable).

`startScan` guards against a missing script, a missing project base path, and a project without a `.git` directory
(localized "not a git repo" message), then snapshots the pre-scan state (`restoreStateBeforeScan`) so a canceled scan
never leaves stale findings behind.

**Two-phase progress** (added 2026-07-31):

1. **`ScanCommitsTask`** — a *determinate* background task. It launches the script with `--jsonl` (cwd = project root),
   reads stdout line by line, updates `indicator.fraction` from `scanning commit` records, and collects any premature
   findings. When the script emits the `scanned` marker it reads the `branch` field into `currentBranch`, sets
   `isScanCompleted`, and hands control to phase two *without* destroying the process.
2. **`AnalyzeDataTask`** — an *indeterminate* background task that keeps reading the same stdout until EOF, now
   collecting the project-wide
   `analyzed` / `quality` / `period` records and the SXXXX findings. On a clean exit it stamps `scanCompletedAt` and
   sets `SUCCESS`; otherwise
   `handleFailure` reads the script's stderr temp file for a message.

Script stdout is JSONL (one JSON object per line); stderr is redirected to a temp file read only on failure. A `FAILED`
scan surfaces
`lastErrorMessage` (localized via `gradum.gitstats.error.<code>` when the script sends an `error` field, else the raw
message) with a "Back to Home"
button.

### 19.3 The audit findings tree (`AuditFindingsTree`)

After a successful scan the main area is a Jewel `LazyTree` of
`AuditTreeItem` nodes:

- **Branch row** — when `currentBranch` is available a top-level row (VCS branch icon + bold branch name + `N Problems`)
  wraps the groups below it. The wrapper expands automatically on scan completion.
- **Grouping toggle** — findings bucket into **four audit groups**
  (`Suspected LLM Involvement`, `Potential Code Engineering Risks`, `Team
  Process Observation`, `Other`) or, with **Group by Severity**, into severity rows (Very High / High / Watch /
  Information). The toggle is an eye icon in the action bar with a spinner during the 300 ms transition.
- **Finding rows** — one per SXXXX finding: severity icon + localized body (e.g.
  `S1001: A single commit changed +500/-200 lines...`). Hovering shows the full `(SXXXX) <body>` tooltip. Findings with
  a real commit hash are clickable.
- **Load-more** — each group shows at most `DEFAULT_FINDING_LIMIT = 50`
  findings; a "Continue expanding N more issues" row appends the next batch (added 2026-08-08).
- **Review marking** — toggling the bookmark action strikes the finding through with a line-through and swaps the icon
  to the outline variant; a hover-expanding orange "Reviewed" label appears. The reviewed set is tracked per finding via
  `findingKey = "<code>|<hash>|<index>"`.

The body of each finding is localized client-side: the script sends raw
`params`, and `AuditFinding.formatBody()` rebuilds the message from
`gradum.audit.<code>` bundle keys using `AUDIT_PARAM_ORDER` (which also weaves the commit hash into the sentence for
hash-bearing codes).

### 19.4 The action bar (`GitAuditActionBar`)

A vertical rail of `IconTooltipButton`s with i18n tooltips:

| Action            | Icon key                  | Notes                                                                        |
|-------------------|---------------------------|------------------------------------------------------------------------------|
| Close report      | `General.Close`           | Returns to home.                                                             |
| Rescan            | `General.Refresh`         | Restarts the analysis.                                                       |
| Commit preview    | `LayoutEditorPreview`     | Toggles the right-hand commit panel; enabled only for hash findings.         |
| Expand / collapse | `ExpandAll`/`CollapseAll` | Toggles all group rows.                                                      |
| Group by severity | `General.Show`            | Spinner while switching grouping.                                            |
| Mark reviewed     | `ToolWindowBookmarks`     | Toggles reviewed state of the selected finding.                              |
| Copy JSON         | `Copy`/`Checked`          | Copies all non-reviewed findings as a JSON document (`auditFindingsToJson`). |

### 19.5 Commit details panel (`CommitInfoPanel`)

Selecting a finding with a real commit hash (`hasRealCommitHash()`) and enabling the preview shows the right-hand panel:

- **Header actions** — Open on GitHub (`openCommitOnGitHub` resolves the
  `origin` remote and browses `https://github.com/<path>/commit/<hash>`; SSH and HTTPS forms both work), copy
  subject+body, pin/unpin (so the panel keeps showing one finding while others are selected), and mark reviewed.
- **Time bar** — collapsible row with author, relative date (`just now` / `N days ago`), and diff stats `+N`/`-N` (green
  additions, red deletions).
- **Body** — subject in the editor font (bold), followed by the full commit body rendered through the chat's Markdown
  pipeline (inline chips, tables, code blocks all work).
- **Resize handle** — a `CommitInfoPanelResizeHandle` drags the panel width between `320.dp` and `500.dp` (default 320),
  implemented with a raw pointer loop so the start width is captured at pointer-down.

### 19.6 Success banner (`GradumBanner`)

On success the tool window shows an animated (300 ms slide+fade) banner at the top of the report. It reads the quality
band (Excellent / Good / Fair / Needs Attention / Caution) and the scan-completion time (`scanCompletedAgo`, e.g.
"completed 23 minutes ago"), rendered as "Local analysis complete. The project is currently in the {band} tier." It
carries a green success icon, a **Don't show again** dismiss link (persists via
`bannerDismissed`), and a close button.

`GradumBanner` is a reusable component based on Jewel's
`DefaultBannerStyle` with three severities (`Success` / `Warning` / `Error`)
and configurable icon, link, and close-content slots — it is deliberately independent of the git-analysis feature and
can be reused elsewhere.

### 19.7 The analysis script and its JSONL protocol

The plugin launches `scripts/git_stats_log/git_stats.py --jsonl` (a pure-stdlib Python audit engine; an older
`scripts/git_stats.py` CLI with charts/CSV/report packaging exists at repo root). The script walks every commit via
`git log -c --numstat` and emits records in order:

1. `INFO` header (`message`, `version`), `start` (`repo`, `branches`, `since`).
2. One `scanning commit` record per commit — `current`, `total`, `hash`
   (drives the determinate progress bar).
3. `scanned` — `commits`, `repo`, `elapsed_ms`, `branch` (drives the phase-two hand-off and the tree's branch row).
4. When `enableQualityAnalysis` is set: `Analyze Quality`, `Audit Deletions`,
   `analyzed` (`commits`, `problems`, `deletion_percent`, `overall_level`),
   `quality` (`band`, `score`, `factor_recency`, `factor_ai`,
   `factor_deletion`, `factor_scale`, `factor_hero`), one **S-finding record**
   per finding, and per-period `period` records.
5. `complete` (`elapsed_ms`).

**S-finding record schema**: `code` (`SXXXX`), `level` (`critical` / `alert` /
`watch` / `normal` / `clean`), `type`, `hash` (short hash or placeholder like
`-`, `Cluster #N`, `Recent Spike`), `index` (-1 for aggregate findings),
`date`, `days`, `subject`, `author`, `body`, and a `params` object with the template values. The Kotlin parser routes
any record whose `code` starts with
`S` into `AuditFinding`; everything else is matched by `message`.

**Audit code catalog** (severity ranking: critical > alert > watch > normal):

| Code    | Level       | Type / trigger                                                                 |
|---------|-------------|--------------------------------------------------------------------------------|
| `S1001` | critical    | Single heavy commit (`+additions ≥ 500 AND deletions ≥ 500` in core files).    |
| `S1002` | critical    | Net reduction: global deletions/additions ≥ 1.0.                               |
| `S1003` | critical    | Single-author project (≤ 1 non-bot author, ≥ 10 commits).                      |
| `S1004` | critical    | Mass rewrite: one commit ≥ 50% of total lines (> 1000 lines).                  |
| `S2001` | alert       | Deletion cluster: ≥ 3 consecutive heavy-deletion commits.                      |
| `S2002` | alert       | Mature-project churn: ≥ 3 heavy deletions in the last 90 days.                 |
| `S2003` | alert       | Core net deletion: heavy deletion of core source files.                        |
| `S2004` | alert       | Accumulation-only: deletions ratio < 0.05.                                     |
| `S2005` | alert       | AI volume spike: avg lines/commit above the LLM-generation threshold.          |
| `S2006` | alert       | AI bootstrap: early add/delete ratio signals LLM-generated code.               |
| `S2007` | alert       | AI uniformity: commit sizes too uniform (low CV of additions).                 |
| `S2008` | alert       | AI focus deviation: files-per-commit far from the 3.0 target.                  |
| `S2009` | alert       | Firework burst: too many commits per day over a short active window.           |
| `S2010` | alert       | Claude flood: co-author signatures (Claude/OpenCode) above threshold.          |
| `S2011` | alert       | Hero dependency / bus factor: top-5 contributors too concentrated.             |
| `S2012` | alert       | Abandoned: last commit older than the recency half-life (90 days).             |
| `S2013` | alert       | AI agent artifacts: marker files for Claude Code, Cursor, Copilot, … detected. |
| `S3001` | watch       | Non-core deletion: heavy deletion with zero core-source files.                 |
| `S3002` | watch       | Heavy churn: 0.50 ≤ deletion ratio < 1.0.                                      |
| `S3003` | watch       | Bot-like author matching `bot` / `agent` patterns.                             |
| `S3004` | watch       | Weekend warrior: > 50% of commits on non-working days (≥ 10 commits).          |
| `S3005` | watch       | Day burst: > 10 commits on a single day.                                       |
| `S3006` | watch       | No merges: fully linear history (≥ 20 commits).                                |
| `S3007` | watch       | Tiny commits: > 30% under the 10-line threshold.                               |
| `S3008` | watch       | Vague messages: > 30% match generic-message regex.                             |
| `S4001` | information | Low cleanup: churn ratio in 0.05–0.25.                                         |
| `S4002` | information | Small project: < 1000 total lines (≥ 5 commits).                               |

**Quality band** — `composite` score in `[0, 1]` maps to the first band whose minimum is satisfied: `≥0.8 Excellent`,
`≥0.6 Good`, `≥0.4 Fair`,
`≥0.2 Needs Attention`, else `Caution` (band `Archived` when no commits in 730 days). Weights: recency 0.286, anti-AI
0.202, deletion-health 0.218, scale 0.134, hero 0.160; the composite blends raw weighted factors with a confidence
factor `1 − 1/(√n+1)` and a small personality term.

### 19.8 i18n for the audit feature

All strings live under the `gradum.toolwindow.git.analysis.*` and
`gradum.audit.*` key families in `GradumBundle` (en + zh_CN): scan title, progress
(`Scanning commits {0} / {1} completed`), action tooltips, severity labels, relative-time strings (`just now`,
`N minutes/hours/days ago`), the banner text + band labels + dismiss/close, `N Problem(s)` pluralization, the load-more
row, the four audit group labels, and one template per S-code.

Sources: [
`GradumGitAnalysisToolWindowFactory.kt`](../plugin/src/main/kotlin/gradum/idea/GradumGitAnalysisToolWindowFactory.kt),
[`GradumGitAnalysisService.kt`](../plugin/src/main/kotlin/gradum/idea/GradumGitAnalysisService.kt),
[`AuditFindingsTree.kt`](../plugin/src/main/kotlin/gradum/idea/AuditFindingsTree.kt),
[`AuditTreeItem.kt`](../plugin/src/main/kotlin/gradum/idea/AuditTreeItem.kt),
[`GitAuditActionBar.kt`](../plugin/src/main/kotlin/gradum/idea/GitAuditActionBar.kt),
[`CommitInfoPanel.kt`](../plugin/src/main/kotlin/gradum/idea/CommitInfoPanel.kt),
[`GradumBanner.kt`](../plugin/src/main/kotlin/gradum/idea/GradumBanner.kt),
[`scripts/git_stats_log/git_stats.py`](../scripts/git_stats_log/git_stats.py),
[`scripts/configs.jsonc`](../scripts/configs.jsonc).
