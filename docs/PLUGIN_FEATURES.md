<!--
  ~ Copyright (c) 2026 Gradum team, some rights reserved.
  ~ For licensing terms and conditions, see the MIT LICENSE file.
  ~
  ~ PLUGIN_FEATURES.md  2026-07-14 07:46:00 Changed by gwy
  -->

# Gradum Plugin Features

This document describes every user-facing feature of the Gradum IntelliJ IDEA
plugin (the `plugin/` module). It is the canonical reference for the chat
tool-window, its composing UI surfaces, and the cross-cutting infrastructure
that holds them together.

For the server-side protocol, the agent loop, and the Skill contract see
[`ARCHITECTURE.md`](ARCHITECTURE.md). For Skill authoring see
[`PLUGIN_DEVELOPMENT.md`](PLUGIN_DEVELOPMENT.md). This file is plugin-only.

---

## At a glance

| Area                                | Count / Scope                               |
|-------------------------------------|---------------------------------------------|
| Kotlin source files (plugin module) | 64 main + 3 test                            |
| Tool-window / chat UI components    | 27                                          |
| i18n keys                           | 127 (`en` + `zh_CN`)                        |
| SVG icon resources                  | 324 SVGs + 1 PNG                            |
| Project-level services              | 1 (`GradumChatSession`)                     |
| HTTP endpoints consumed             | 3 (`/events`, `/models`, `/skills`)         |
| Wire event types handled            | 4 (thinking / tool_call / response / error) |

The plugin is built on JetBrains Jewel + Compose for Desktop. Every visible
string is localizable; every color is theme-aware through `JewelTheme`; every
icon is loaded from the plugin classpath at runtime.

---

## 1. Tool window integration

The plugin registers a single IntelliJ tool window:

- **`id`** — `Gradum`
- **`anchor`** — right sidebar
- **`factoryClass`** — `gradum.idea.GradumToolWindowFactory`
- **`icon`** — `icons/logo/logo.svg`

The factory hosts a Compose tab via `addComposeTab` and seeds the chat session
from the project-level `GradumChatSession` service. A `New Chat` title-bar
action is registered to reset the session and reset the tab display name to
the localized welcome string.

> Project-level state is non-negotiable: when the user collapses the sidebar
> the IntelliJ Platform disposes the tool-window content, so any in-Compose
> `mutableStateOf` is lost on the next expand. `GradumChatSession` is the
> sole owner of cross-collapse state.

Source: [`GradumToolWindowFactory.kt`](../../plugin/src/main/kotlin/gradum/idea/GradumToolWindowFactory.kt),
[`plugin.xml`](../../plugin/src/main/resources/META-INF/plugin.xml).

---

## 2. Welcome screen

The first surface the user sees when the tool window opens against an empty
session.

- **Layout** — outer `Box` with `contentAlignment = Center`; inner `Column`
  capped at `max width = 600.dp` with `start padding = 6.dp` so the header,
  the input area, and the quick-start section all share one vertical line.
- **Rotating greeting** — a `SweepLightText` composable renders a typewriter
  effect across a hand-curated list of welcome messages (anti-repetition
  logic guarantees the same greeting is never shown twice in a row).
- **Quick-start** — four categories with five variants each (chat, code,
  question, text); a fresh `Random.nextInt(5)` is drawn for each category at
  every open, then frozen for the session.

Source: [`WelcomeScreen.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/home/WelcomeScreen.kt),
[`QuickStartSection.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/home/QuickStartSection.kt),
[`SweepLightText.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/SweepLightText.kt).

---

## 3. Chat screen

The `ChatScreen` composable is the root of the message experience and is
mounted as soon as the user sends their first message (`hasSentMessage` flips
to `true`). It composes:

1. A scrollable message list.
2. The input section (toolbar + text field + attachments + model bar).
3. A reactive `isWaitingForResponse` overlay.

The screen takes 11 typed parameters today; the 8-parameter model selector
bar inside it is a known refactor target (a dedicated data class would let
callers pass a single `ModelSelectorState` instead).

Source: [`ChatScreen.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/ChatScreen.kt).

---

## 4. Message bubbles

Every visible message is one of two bubbles, both anchored to the right
edge for symmetry.

### 4.1 `UserChatBubble`

- Renders the user's raw text and a row of attached files.
- Attachments render in a collapsible list (animated visibility toggle).
- A copy button (`MessageCopyButton`) sits in the bubble footer.

### 4.2 `AssistantChatBubble`

- Renders an event timeline — `Thinking`, `ToolCall`, `Response`, `Error` —
  in arrival order.
- Each event is a `RenderBlock`; consecutive events of the same type are
  coalesced into a single block so Compose can reuse composables without
  rebuilding the list (see [Section 5](#5-message-event-timeline)).
- A `MessageTimestamp` sits in the bubble footer and formats the timestamp
  based on how recent the message is — see [Section 5.3](#53-message-timestamp).

Source: [`UserChatBubble.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/UserChatBubble.kt),
[`AssistantChatBubble.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/AssistantChatBubble.kt),
[`MessageAttachmentList.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/MessageAttachmentList.kt),
[`MessageCopyButton.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/MessageCopyButton.kt).

---

## 5. Message event timeline

### 5.1 Wire events → UI blocks

The server streams four `ChatEvent` subtypes as NDJSON. The client folds
them into three `RenderBlock` kinds:

| Wire event  | Render block           | Behaviour                                                             |
|-------------|------------------------|-----------------------------------------------------------------------|
| `thinking`  | `RenderBlock.Thinking` | Coalesced with the previous thinking block; concatenated as a string. |
| `tool_call` | `RenderBlock.ToolCall` | Never coalesced (one block per invocation).                           |
| `response`  | `RenderBlock.Response` | Coalesced with the previous response block; concatenated as a string. |
| `error`     | `RenderBlock.Error`    | Never coalesced.                                                      |

`ChatMessage.appendEvent` performs an O(1) update — it never re-iterates
the existing list. `updateLastError` likewise does an O(1) `indexOfLast +
set` to patch a failed tool call's error message after the fact.

### 5.2 Per-tool call content

Each server-side skill alias (`Ran`, `Edited`, `Read`, `Saved`, `Explored`,
`Planned`, `Completed`) is rendered by a dedicated `ToolCallRenderer`
implementation registered as a plain Kotlin instance in
`ToolCallRendererRegistry.RENDERERS` (a `List<ToolCallRenderer>`). The
chat panel calls `ToolCallRendererRegistry.find(alias)` once per tool
invocation and routes the result to the matching renderer's `render`
composable. Anything unknown falls through to a wildcard
`DefaultRenderer` (alias `"*"`).

A renderer is responsible for:

- Recognizing a server-side skill alias via `alias()` (e.g. `"Ran"`,
  `"Edited"`). Must match the value emitted by the server-side
  `Skill.alias`.
- Parsing the server `arguments` + `result` JSON into a `ToolCallContent`
  view-model via `parseContent(arguments, result)`.
- Choosing the row's icon (`iconKey()`) and localized label (`labelKey()`,
  a Gradum resource-bundle key under `messages/gradum/`).
- Drawing the actual row via `@Composable render(content, ctx)`. The
  shared `CommonCapsule` composable in `chat/ui/chat/skill/internal/`
  handles the standard icon + label + body + error-state layout, so most
  renderers only need to compute which fields to show in the body and
  which `ToolCallAction`s to expose (e.g. `OpenInEditor`,
  `CopyToClipboard`).

Adding a new tool call UI is a **plain Kotlin registration** — one
line in the registry, no plugin.xml change. The chat panel consults
[
`ToolCallRendererRegistry.RENDERERS`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill/spi/ToolCallRendererRegistry.kt)
(in a list-of-instances) and dispatches to the first renderer whose
`alias()` matches. To add a new alias:

1. Create a folder `chat/ui/chat/skill/<alias>/` containing a
   `<Alias>Renderer.kt` that implements
   [`ToolCallRenderer`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill/spi/ToolCallRenderer.kt).
2. Append `MyRenderer()` to `RENDERERS` in
   `ToolCallRendererRegistry.kt` (above the wildcard
   `DefaultRenderer()` entry, which must remain last).

**Why a plain list and not an IntelliJ Platform `ExtensionPoint`?**
The Platform's `ExtensionPointName` lookup path has several practical
drawbacks for this use case:

- The `<extensionPoint>` element must be a direct child of
  `<idea-plugin>`, which is fragile to refactors and easy to break
  with a copy / paste of an `<extensions>` block.
- EP resolution goes through the IDE's `Extensions` area, which
  throws `IllegalArgumentException: Missing extension point` at the
  first chat render if anything is misconfigured — a non-recoverable
  runtime crash that takes down the entire chat panel.
- The EP cannot be defined per-alias in a way that's easy to
  discover: a developer has to read the EP interface + the Gradum
  source to learn the convention.
- Classloader isolation between Gradum and third-party plugins
  means the EP lookup frequently fails with "Unable to resolve
  extension point … in plugin dependencies" at parse time, even
  when the source looks correct.

A plain `val RENDERERS: List<ToolCallRenderer>` solves all four
problems at the cost of one line of code per new alias.

The standard payload shape the renderer receives on the wire (an
NDJSON `tool_call` event) is `{tool, arguments, toolCallId, success, result}`.
`parseContent` is given the `arguments: Map<String, Any?>` and the
parsed `result: Map<String, Any?>` (see
[`ResultParser.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/skill/spi/ResultParser.kt)
for the JSON-to-`Map` conversion that the server's `tool_call.result`
JSON string goes through).

The default `R` field rendering per alias (as the LLM sees it on the
server side) is:

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
[`docs/PLUGIN_DEVELOPMENT.md`](../PLUGIN_DEVELOPMENT.md) section 16
"Extending the plugin's tool call UI" for the full tutorial and worked
example.

### 5.3 Streaming indicators

- **`ThinkingIndicator`** — a three-dot pulsing animation displayed while
  the server emits `thinking` chunks.
- **`ToolCallIndicator`** — a one-line alias + status icon shown beneath the
  active tool call (e.g. "Ran read_file").
- **`SweepLightText`** — the typewriter + shimmer effect used both on the
  welcome screen and on streaming response text.

### 5.3 Message timestamp

`formatTimestamp` is locale-aware (`Locale.getDefault()`) and produces:

- **Today** — `HH:mm` (e.g. `14:30`).
- **Yesterday** — `Yesterday HH:mm` (localized).
- **This year** — `MMM d` (e.g. `Jun 15`).
- **Older** — `N days ago` (localized).

The message list only renders a date separator when the day changes between
consecutive messages; everything else gets a per-message inline timestamp.

Source: [`ChatMessage.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/model/ChatMessage.kt),
[`ThinkingIndicator.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/ThinkingIndicator.kt),
[`ToolCallIndicator.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/ToolCallIndicator.kt),
[`ToolCallContent.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/ToolCallContent.kt),
[`MessageTimestamp.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/MessageTimestamp.kt).

---

## 6. Markdown rendering

Markdown in the chat goes through a **layered pipeline** rather than a single
`Markdown(...)` call, because the JetBrains Jewel `Markdown(...)` composable
does not expose an `inlineContent` slot — and a chat message that mixes
**prose with inline code** is the most common case. Each layer handles one
job; layers fall through to one another when their preconditions fail.

```
                       raw assistant response text
                                  │
                                  ▼
                   splitMarkdownAtTables(text)       (GradumMarkdownTable)
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                       ▼
       MarkdownSegment.Table                  MarkdownSegment.Plain
              │                                       │
              ▼                                       ▼
   ScrollableTable (custom Compose)      rememberInlineMarkdownRender(text)
   inside a horizontally-scrollable Box   (GradumInlineMarkdown, commonmark)
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

A Jewel `MarkdownBlockRenderer` that wraps every fenced code block with a
copy button + the IDE's syntax highlighter (resolved through the Jewel
bridge to whatever language services the host project has loaded). This
layer is registered once via `LocalMarkdownBlockRenderer` and is what
Jewel's `Markdown(...)` calls into.

### 6.2 Layer 2 — GFM tables (`GradumMarkdownTable`)

GFM tables are **pulled out of the raw Markdown BEFORE** the rest is handed
to `Markdown(...)`. Tables are rendered as plain Compose inside a
horizontally-scrollable `Box` (`ScrollableTable` composable inside
`GradumMarkdownTable.kt`), which gives a wide table its own horizontal
scrollbar without also scrolling the surrounding prose. Trying to wrap
the whole `Markdown(...)` in `Box.horizontalScroll(...)` instead had the
side effect of making long inline code / URLs horizontally scrollable too —
undesirable in a chat panel. `splitMarkdownAtTables(text)` is the pure
function that returns `List<MarkdownSegment>`.

### 6.3 Layer 3 — Inline Markdown hijack (`GradumInlineMarkdown`)

This is the layer the LLM sees most. For every `MarkdownSegment.Plain`
we attempt a custom inline parse:

1. `commonmark-java` (already on the classpath via Jewel's
   `intellij.platform.jewel.markdown.core`, **zero new dependency**)
   reparses the segment and returns a `Document` AST.
2. We walk the AST. If the segment contains a list, heading, blockquote,
   fenced code block, `LinkReferenceDefinition`, or anything other than
   plain `Paragraph` blocks, the layer **bails out** and returns `null`
   — the caller then falls through to Jewel's native `Markdown(...)`.
   Lists in chat are rare; re-implementing list / heading / blockquote
   rendering is not worth it.
3. For accepted segments, we build:
    - one `AnnotatedString` with `SpanStyle` overlays (bold, italic, link)
        + `StringAnnotation` tags (URL) + **Private-Use-Area placeholder
          substrings** for inline code spans
    - one `inlineContent: Map<String, InlineTextContent>` keyed by the
      PUA placeholder, where each value renders one rounded chip via
      `InlineCodeChip`
4. The caller renders the whole segment with a single
   `Text(annotated, inlineContent = …)`, so line wrapping survives
   across bold / italic / link / code boundaries.

**Why hijack at all?** Two reasons:

- The chip needs a rounded background + 0.5dp border, not just a
  `SpanStyle` background color. The Jewel composable's
  `inlineContent` slot is closed (verified by `javap` on
  `MarkdownKt.class`), so we cannot inject a custom chip renderer
  through the native path.
- The v1 attempt to bypass `Markdown(...)` for any paragraph
  containing backticks lost **bold / italic / links** for the rest
  of the paragraph AND the chip rendered empty because the chip's
  internal `Text` inherited the editor style's `lineHeight` (1.5x) and
  got clipped to ~0 visible pixels. v2 takes the segment wholesale and
  walks the CommonMark AST ourselves — no `Markdown(...)` involved, so
  no styling escape hatches fire.

**Chip visual spec** (`InlineCodeChip` inside `GradumInlineMarkdown.kt`):

- 4dp rounded corners (`RoundedCornerShape(4.dp)`).
- Background `tint.copy(alpha = 0.12f)`, border 0.5dp
  `tint.copy(alpha = 0.30f)`.
- Padding `horizontal = 4.dp, vertical = 2.dp`.
- Font: same family as the editor text; weight `Medium`; **explicit
  `lineHeight = fontSizeSp.sp`** (1.0x — fixes the v1 zero-pixel bug).
- `maxLines = 1`, `softWrap = false`.

**Bail-out conditions** (return `null` → caller uses `Markdown(...)`):

- Blank input.
- Input contains any non-`Paragraph` block (list, heading, blockquote,
  fenced code, `LinkReferenceDefinition`).
- `commonmark` throws (defensive — `commonmark` is robust, but a bug in
  the AST walker or chip rendering should not take down the entire
  chat bubble).
- All 28 unit tests in `GradumInlineMarkdownTest.kt` pin these
  conditions (bail-out, plain, bold-italic, code, link, mixed,
  multi-para, escape, soft break, defensive).

### 6.4 Layer 4 — Native `Markdown(...)` (the fallback)

When a `Plain` segment bails out of `GradumInlineMarkdown`, the caller
hands it to `Markdown(markdown = segment.text, modifier = …,
onUrlClick = …)`. This is the original Jewel path: it renders
everything from headings to nested lists to thematic breaks. It also
reaches back into Layer 1 to pick up the `GradumCodeBlockRenderer` for
fenced code blocks.

### 6.5 Styling (`GradumMarkdownStyling`)

Configures colors, fonts, list markers, and link styling from
`JewelTheme`. A `remember` block at the call site keeps the styling
stable across recompositions. The new public
`rememberGradumParagraphTextStyle()` lets the inline hijack and the
native `Markdown(...)` path produce visually identical paragraph text.

### 6.6 Streaming integration

`SweepLightText` is fed the accumulated response string so the user
sees a typewriter + shimmer effect as the model emits tokens. Each
completed response chunk re-runs the full pipeline above, so
tables / chips / bold / links are stable across the stream.

Source: [`GradumMarkdownStyling.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/GradumMarkdownStyling.kt),
[`GradumCodeBlockRenderer.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/GradumCodeBlockRenderer.kt),
[`GradumMarkdownTable.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/GradumMarkdownTable.kt),
[`GradumInlineMarkdown.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/GradumInlineMarkdown.kt),
[`GradumInlineMarkdownTest.kt`](../../plugin/src/test/kotlin/gradum/idea/chat/ui/GradumInlineMarkdownTest.kt),
[`GradumMarkdownTableTest.kt`](../../plugin/src/test/kotlin/gradum/idea/chat/ui/GradumMarkdownTableTest.kt).

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

The state for this whole tree is a `ChatInputState` snapshot; the callbacks
form a `ChatInputActions` data class. `ChatInputState` is recomposed via
`mutableStateOf` on the session — never as a `mutableStateListOf` — to keep
Compose's snapshot model predictable.

Source: [`ChatInputSection.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ChatInputSection.kt),
[`ChatInputPanel.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ChatInputPanel.kt),
[`ChatToolbar.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ChatToolbar.kt),
[`ChatInputState.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/input/ChatInputState.kt),
[`PreviewText.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/PreviewText.kt).

---

## 8. Model selection

The model selector is a `SelectorButton` (icon + label + chevron) that opens
a `PopupMenu` with three sections:

1. **`Auto`** — always present, highlighted when `isAutoSelected` is true.
2. **Pinned** — only when the user has pinned at least one model.
3. **All models** — everything else, minus pinned entries.

### 8.1 Auto mode (server-recommended)

When the user picks **Auto**, the plugin does **not** flip a flag and stop
there — it actively selects the server's recommendation. The recommender
runs server-side on every `/models` request and is described in detail in
[`ARCHITECTURE.md`](ARCHITECTURE.md#model-recommendation); the short version
of the algorithm is:

1. **Context window** — the universal baseline (`min(contextLimit, 200K) / 2000`).
2. **Cloud preference** — cloud models get a flat `+200` bonus because the
   project is local-first by intent, not by capability.
3. **Local parameter count** — `paramsB × 1.5`, where `paramsB` is parsed
   out of the model name with a `(\d+(?:\.\d+)?)b` regex. A 70B beats a 7B
   decisively, but not so much that a 70B blows past a stronger cloud
   candidate.
4. **Memory gate** — a local model whose estimated VRAM
   (`paramsB × 0.8`, Ollama Q4 average) exceeds the user's current free RAM
   (sampled fresh per request, then discounted 25% for OS/IDE overhead) is
   hit with a `-500` penalty. This is large enough to demote it below every
   cloud candidate and every smaller local alternative, but the entry stays
   selectable for users who insist.
5. **Capability flags** — `+20` for reasoning, `+10` for tool-calling,
   `+5` for vision.

When the user is in **Auto** mode the selector button renders as
`Auto - {picked model name}` so the user can see at a glance which model
the recommender picked, and the leading "Auto" makes the implicit mode
visible without forcing them to reopen the menu. The menu's Auto row stays
highlighted to keep the mode signal in two places.

When the user is in manual mode, only the model name is shown — the
`Auto -` prefix is suppressed to avoid noise.

### 8.2 Provider icons

`GradumIcons.resolveModelIcon(modelName)` resolves a model to a
provider-branded icon by scanning the lower-cased name for the first
keyword hit. The mapping is hard-coded in `PROVIDER_KEYWORD_MAP` and
covers every cloud and self-hosted provider the project advertises:

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

The eleven provider folders under `icons/model-provider/` each ship a
light/dark pair so the icon tracks the IDE theme. `ModelSelectorBar`
prefers this resolution for the selector button, and
`AssistantChatBubble` uses it to render a small leading icon on every
assistant message.

### 8.3 Pin / unpin

Every non-Auto model in the menu has a pin toggle. Pinned models surface in
their own section above "All models" and survive across sessions within
the project.

### 8.4 Model name formatting

Raw names from the wire look like `qwen2.5-coder-32b-instruct` or
`lmstudio-community/qwen2.5-7b`. `formatModelName` strips the
`username/model` prefix (LM Studio convention), drops the trailing
size tag, and looks the remainder up in a hand-curated display table that
maps `qwen2.5-coder` → `Qwen 2.5 Coder`. Unrecognised names fall back to a
title-cased hyphen substitution.

Source: [`ModelSelectorBar.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ModelSelectorBar.kt),
[`ModelNameFormatter.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/ModelNameFormatter.kt),
[`SelectorButton.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/common/SelectorButton.kt),
[`GradumIcons.kt`](../../plugin/src/main/kotlin/gradum/idea/icons/GradumIcons.kt).

---

## 9. Permission selector

A dropdown that controls the active `ToolMode`:

- **`READ_ONLY`** — `explore_project`, `read_file`, `run_cmd`. No `edit_file`,
  no `save_file`, no `to_do`.
- **`SINGLE_STEP`** — everything in read-only plus `to_do`.
- **`WRITE`** — every Skill is exposed.

The default for a new session is `READ_ONLY`; the user promotes to
`SINGLE_STEP` or `WRITE` from the dropdown once they understand what each
mode unlocks.

The selector writes the chosen mode into a wire-format string and the
plugin's `PermissionSelector` enforces the same gate server-side via
`Skill.allowedToolModes`.

Source: [`PermissionSelector.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/PermissionSelector.kt),
[`Skill.kt`](../../src/main/kotlin/gradum/skill/Skill.kt),
[`SkillRegistry.kt`](../../src/main/kotlin/gradum/skill/SkillRegistry.kt).

---

## 10. Attachments

The user can attach files and folders to a message. The bar lives between
the toolbar and the text field.

- **Maximum count** — `MAX_ATTACHMENTS = 10`. Files, images, and text
  attachments all share the same pool. The add button is disabled
  (with a tooltip explaining why) once the limit is reached.
- **Add surface** — `AddContextPopup` exposes file pickers for both files
  and directories. Adding a directory walks it and freezes the result at
  send time so subsequent edits in the IDE do not affect the in-flight
  message.
- **Remove** — every file chip has a remove button; deletion is
  non-destructive (the file on disk is untouched).
- **Long-paste detection** — any text longer than 200 characters pasted
  into the input is automatically lifted out as a `text/plain` attachment
  and removed from the input. The detection uses a reactive diff against
  the `textState` subscription rather than a `KeyEvent` interceptor, so
  it works equally well for paste, drag-and-drop, and IME input.
- **Collapsible** — the attachment list is collapsible with an animated
  visibility toggle so a full bar of five files does not crowd the input
  on small tool windows.

Source: [`AttachmentBar.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/AttachmentBar.kt),
[`AddContextPopup.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/AddContextPopup.kt),
[`FileItem.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/input/FileItem.kt),
[`Attachments.kt`](../../plugin/src/main/kotlin/gradum/idea/editor/Attachments.kt),
[`EditorContext.kt`](../../plugin/src/main/kotlin/gradum/idea/editor/EditorContext.kt).

---

## 11. State management

`GradumChatSession` is a `Service(Service.Level.PROJECT)`. It owns the
in-flight UI state and survives the tool window being collapsed and
re-expanded.

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
`/models` request and, if either the model roster or the server's
recommendation has changed since the last tick, applies the update. The
recommendation can flip even when the model list is unchanged — for
example, when the user closes another app, freeing enough memory for a
larger local model to win the recommender.

The poller is implemented as `tickerFlow().flatMapLatest { fetchModelsOnce() }`
so a slow request that overlaps with a tick is canceled by the upstream
emission rather than racing the next one.

Source: [`GradumChatSession.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/state/GradumChatSession.kt).

---

## 12. HTTP API client

`GradumApiClient` is a thin wrapper around the JDK 11 `HttpClient`. The
plugin only ever calls three endpoints.

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

`recommended` is nullable for backward compatibility — older server builds
that do not include the field decode cleanly thanks to
`Json { ignoreUnknownKeys = true }`.

### 12.2 `POST /events` (NDJSON stream)

Sends a single user turn and consumes the streamed agent events as
NDJSON. The parser is per-line resilient: a malformed line is logged at
`warn` level and skipped, so a single bad event does not break the
stream. The four event types mirror the `ChatEvent` subtypes in
[Section 5](#5-message-event-timeline).

### 12.3 `POST /stop`

Tells the server to abort the in-flight `Agent` for a given `sessionId`.
The plugin calls this when the user clicks the **Stop** button in the
chat toolbar; the server replies with `{"status": "stopped", ...}` and
the client drops the `isSending` flag.

### 12.4 Error handling

A typed `ErrorCode` enum is shared between the server and the plugin (16
codes, including `MODEL_TIMEOUT`, `TOOL_BLOCKED`, `READ_ONLY_VIOLATION`,
`RATE_LIMITED`, etc.). The plugin maps codes to localized, user-friendly
messages — never raw stack traces.

Source: [`GradumApiClient.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/api/GradumApiClient.kt),
[`ErrorCode.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/model/ErrorCode.kt),
[`ErrorMessages.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/chat/ErrorMessages.kt).

---

## 13. i18n

Every user-visible string in the plugin is loaded through `GradumBundle`,
which extends IntelliJ's `DynamicBundle`. The bundle ships two locales:

- **`en`** — `messages/GradumBundle.properties` (default).
- **`zh_CN`** — `messages/GradumBundle_zh_CN.properties`.

There are **132 keys** in each file. Both files are kept in lockstep — a
key that exists in one must exist in the other; the bundle is hardened
with two safety nets:

1. **Startup probe** — the `init` block of `GradumBundle` looks up a
   sentinel key (e.g. `gradum.toolwindow.welcome`) to confirm the active
   locale's resource is on the classpath.
2. **Per-key fallback** — `GradumBundle.message(key, ...)` catches
   `MissingResourceException` and returns `???<key>???` so a missing key
   is obvious in the UI without breaking layout.

The Chinese copy is not a direct translation; it is curated for cultural
relevance (Chinese proverbs, regional phrasing) and the English copy is
deliberately terse and tech-flavored.

Source: [`GradumBundle.kt`](../../plugin/src/main/kotlin/gradum/idea/bundle/GradumBundle.kt),
[`GradumBundle.properties`](../../plugin/src/main/resources/messages/GradumBundle.properties),
[`GradumBundle_zh_CN.properties`](../../plugin/src/main/resources/messages/GradumBundle_zh_CN.properties).

---

## 14. Icons

The plugin ships **325 icon assets** (324 SVGs + one PNG) under
`plugin/src/main/resources/icons/`, organized by purpose:

- `auto/`, `build/`, `cloud/`, `local/` — model-mode indicators.
- `cmd/`, `edit/`, `explore/`, `web/`, `file-type/` — tool affordances.
- `feat/chat/`, `feat/code/`, `feat/question/`, `feat/text/` — quick-start tiles.
- `file-type/` — language-typed file glyphs (Kotlin, Python, TypeScript, JSX, PHP, …).
- `model-provider/` — brand logos for 11 providers, each with a
  light/dark pair for IDE theme parity.
- `like/`, `like-selected/`, `send/`, `tools/`, `search/`, `warning/`, `vison/`, `image/`, `markdown/`, `logo/` — UI
  affordances.
- `hands.png` — a 1600 × 1600 PNG used in the welcome screen's empty
  state; kept as raster because the artwork uses a continuous gradient
  that does not survive the SVG simplification pass.

Every icon has a light/dark pair (suffix `_dark`) so it tracks the IDE
theme. `GradumIcons` is the single source of truth for icon lookups;
UI code never hard-codes an icon path.

Source: [`GradumIcons.kt`](../../plugin/src/main/kotlin/gradum/idea/icons/GradumIcons.kt),
[`icons/`](../../plugin/src/main/resources/icons).

---

## 15. Editor integration

The plugin reads three things out of the host IDE:

- **Current selection** — `EditorContext` snapshots the user's text
  selection in the focused editor at the moment a chat message is sent,
  so the LLM can quote it back.
- **Open file path** — the active file's path is added to the
  conversation as an implicit attachment.
- **Pending messages** — `PendingMessage` is the data class that snapshots
  one user message (text + frozen attachments) while it is queued. The
  queue itself lives on `GradumChatSession.pendingMessages` and is
  capped at `MAX_PENDING_MESSAGES = 2`. When the streaming turn
  finishes, the next pending message is dispatched automatically.

Source: [`EditorContext.kt`](../../plugin/src/main/kotlin/gradum/idea/editor/EditorContext.kt),
[`PendingMessage.kt`](../../plugin/src/main/kotlin/gradum/idea/editor/PendingMessage.kt).

---

## 16. Styling conventions

- **Theme** — every composable pulls from `JewelTheme` (`globalColors`,
  `typography`, `editorColors`). No hard-coded hex colors; no
  `Color.Red` / `Color.Blue` literals.
- **Spacing** — all paddings, gaps, and margins come from `GradumSpacing`
  in `Spacing.kt`. Popup items use
  `Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)`;
  section headers use `vertical = 4.dp`; icon-to-text gaps use
  `Spacer(Modifier.width(6.dp))`. This is the canonical spacing rule
  for any new popup or menu in the plugin.
- **Icon-to-tooltip semantics** — pin and unpin, share and unshare, and
  similar toggle pairs use a state-based icon: the icon for the *active*
  state is the "selected" or "filled" variant, the icon for the *inactive*
  state is the "outline" variant. Tooltips mirror the icon's meaning,
  not the underlying state.
- **Text composables** — every `Text` uses the named `text =` parameter
  (not positional). This is enforced project-wide and lints clean.
- **Single-line `if`** — `if (cond) doThing()` without braces is the
  default; only wrap when the body is non-trivial.
- **Wildcard imports** — the project allows wildcard imports for the
  four Compose-for-Desktop key packages (`androidx.compose.foundation.layout.*`,
  `org.jetbrains.jewel.ui.component.*`, `androidx.compose.foundation.*`,
  `androidx.compose.runtime.*`); everything else uses explicit imports.

Source: [`Spacing.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/Spacing.kt),
[`IconTooltipButton.kt`](../../plugin/src/main/kotlin/gradum/idea/chat/ui/common/IconTooltipButton.kt),
[`CONVENTIONS.md`](CONVENTIONS.md),
[`CODING_STANDARDS_KOTLIN.md`](CODING_STANDARDS_KOTLIN.md).

---

## 17. Accessibility & UX guarantees

- **Disabled state tooltips** — every disabled control has a tooltip
  explaining *why* it is disabled (e.g. "Maximum of 10 attachments" on
  the add button when the limit is reached). No silent greying-out.
- **Consistent chat patterns** — user and assistant bubbles, message
  timestamps, copy buttons, and error states share one visual layout
  across the welcome screen, the chat list, and any future surface
  (the rule is documented in `CONVENTIONS.md`).
- **Date separators** — the chat list inserts a separator only when the
  day changes between consecutive messages; per-message timestamps use
  the four-tier format described in [Section 5.3](#53-message-timestamp).
- **Typewriter welcome** — the welcome screen uses an anti-repetition
  rotating greeting so the user does not see the same line twice in a
  row within a session.
- **Streaming animation** — `SweepLightText` is reused for both the
  welcome greeting and the live response stream, so the user gets one
  visual language for "text that is arriving".

---

## 18. File-by-file index

| Path                                       | Role                                                                                                                                                                                              |
|--------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GradumToolWindowFactory.kt`               | Tool-window factory, "New Chat" action, top-level Compose tab.                                                                                                                                    |
| `ImageUpload.kt`                           | Image-attachment upload helper (paste / drop → file on disk → `AttachedContext`).                                                                                                                 |
| `bundle/GradumBundle.kt`                   | i18n bundle with startup probe and per-key fallback.                                                                                                                                              |
| `chat/api/GradumApiClient.kt`              | HTTP client (`/events`, `/models`, `/stop`).                                                                                                                                                      |
| `chat/input/ChatInputState.kt`             | `ChatInputState` + `ChatInputActions` data classes.                                                                                                                                               |
| `chat/model/ChatMessage.kt`                | `ChatEvent` / `RenderBlock` / `ChatMessage` model + `formatTimestamp`.                                                                                                                            |
| `chat/model/ErrorCode.kt`                  | Shared 16-code error enum.                                                                                                                                                                        |
| `chat/model/ModelInfo.kt`                  | Wire shape for a single model from `/models`.                                                                                                                                                     |
| `chat/state/GradumChatSession.kt`          | Project-level service, state owner, model poller.                                                                                                                                                 |
| `chat/ui/ChatScreen.kt`                    | Top-level chat screen composable.                                                                                                                                                                 |
| `chat/ui/GradumCodeBlockRenderer.kt`       | Markdown fenced code block renderer (Layer 1 of the pipeline).                                                                                                                                    |
| `chat/ui/GradumInlineMarkdown.kt`          | Custom CommonMark inline parser + chip renderer (Layer 3 of the pipeline; ~520 lines main + 27 unit tests).                                                                                       |
| `chat/ui/GradumMarkdownStyling.kt`         | Markdown styling config from `JewelTheme` + `rememberGradumParagraphTextStyle()`.                                                                                                                 |
| `chat/ui/GradumMarkdownTable.kt`           | GFM table parser + `ScrollableTable` Compose (Layer 2 of the pipeline).                                                                                                                           |
| `chat/ui/JumpToBottomButton.kt`            | Solid-background jump-to-bottom button with 0.5dp border (auto-shows when the list is scrolled away from the latest message).                                                                     |
| `chat/ui/Spacing.kt`                       | `GradumSpacing` token object.                                                                                                                                                                     |
| `chat/ui/chat/AssistantChatBubble.kt`      | Assistant message bubble; the `ResponseBlock` here drives the four-layer Markdown pipeline.                                                                                                       |
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
| `icons/GradumIcons.kt`                     | Icon registry + provider / model lookups.                                                                                                                                                         |

### 18.1 Test files (plugin module)

| Path                                      | Role                                                                      |
|-------------------------------------------|---------------------------------------------------------------------------|
| `chat/ui/GradumInlineMarkdownTest.kt`     | 27 cases pinning the bail-out / parse / chip / link behaviour of Layer 3. |
| `chat/ui/GradumMarkdownTableTest.kt`      | Pinned tests for `splitMarkdownAtTables` + `ScrollableTable` of Layer 2.  |
| `chat/ui/input/ModelNameFormatterTest.kt` | Pinned cases for the wire-name → display-name lookup.                     |
