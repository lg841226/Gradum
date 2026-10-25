# Timeline Audit MVP

This document is the full specification for the Timeline Audit MVP feature.
It is written to be handed to a coding agent as-is. Implement exactly what is
described here and nothing more. Every deferred feature is listed in the
Non-Goals section at the bottom.

## Goal

Give the user a read-only visual audit of what the agent did during a single
conversation turn: which operations ran, when, how long each took, and which
one failed. The design metaphor is a video-editing timeline: horizontal axis
is time, vertical axis is operation category.

The core principle is absolute simplicity. This MVP is deliberately minimal.
Later features will be layered on top of it.

## Placement

The feature lives in the plugin settings page, as a new group titled
`Statistics & Analysis`, placed directly below the existing editor group.
It is not a separate tool window and not a new dock. The project already has
two docks and no third one will be added.

The settings page reads session data itself. It does not depend on any other
UI component to supply session context.

## Data Source

All data comes from the session sidecar (session-state) files. The server
stamps the facts the timeline needs onto the history rows when it records
them (`ConversationHistory`): every message row carries `ts` (epoch
milliseconds), and every `role: "tool"` row additionally carries
`displayKind` (the wire value of `gradum.skill.ToolDisplayKind`, the single
source of truth for the operation category), `durationMs` (measured
execution time), and `success`. The client never estimates, derives, or
classifies facts from tool name strings; sessions written before this
stamp existed have no timing data and must render an explanatory empty
state instead of estimated values.

Server-side changes for this feature: additive row fields only (`ts`, `displayKind`, `durationMs`, `success` on the tool
rows). No new
endpoint, no new event type. Old sessions stay readable because all
consumers ignore unknown keys.

### Required Derived Values

For each tool call event, derive:

- `endMs = ts`
- `startMs = ts - durationMs` (a call has exactly one recorded time, its
  completion; the block grows backwards from it)
- `leftPercent = (startMs - turnStartMs) / turnDurationMs`
- `widthPercent = durationMs / turnDurationMs`
- `category = Category.fromDisplayKind(displayKind)`

A turn is bounded by the turn start (user message) and the turn end (the
latest `ts` among that turn's messages after the user message).

## MVP Scope

The view shows exactly one turn at a time. Turn navigation is a simple
selector row:

- Left and right navigation buttons are Jewel `IconButton`s with platform
  icon constants (for example the `AllIcons.Actions` chevron or prev/next
  icons). Do not use `OutlineButton`, do not draw custom icons.
- The current turn position text (`3 / 5`) sits between the two buttons.
  Do not prefix it with the word `Turn`.
- The identity line follows as separate `Text` composables, not a single
  concatenated string: start wall-clock time, turn duration, and the
  triggering user message truncated to one line. No `·` separators, each
  value is its own `Text` element.

### Summary Card Content

The identity line shown next to the navigation row contains the turn start
wall-clock time, the total turn duration, and the triggering user message
truncated to one line, for example `14:32:07  27.4s  fix the CI failure in
the…` (as three separate `Text` elements).

Nothing else goes in the summary. It is an index, not a report.

### Timeline Tracks

Vertical rows, top to bottom, in this fixed order:

1. Write
2. Command
3. Network
4. Search
5. Read

Each row shows only the blocks of its category. When two blocks of the same
category overlap in time, the later block shifts down one row slot; no
collision-avoidance algorithm beyond that.

### Timeline Blocks

- Block horizontal position and width are percentages derived from
  timestamps, so the whole view scales proportionally with panel width.
- Minimum rendered block width is 8px. When the percentage is smaller, the
  block still starts at its true percentage position and is widened to 8px.
  Positional accuracy wins over visual completeness.
- Blocks are plain rounded rectangles. No lane background, no row borders.

### Colors

Exactly two colors, both derived from the Compose theme. No new color
values are introduced:

- Normal operations: the theme accent color used elsewhere in the plugin
  UI (the same theme-derived source as the chat minimap accent,
  `JewelTheme.globalColors.outlines.focused`), for every category, painted
  at 0.8 alpha so the fill reads slightly softer against the panel
  background. The failure color below stays fully opaque.
- Failed operations: the theme error color, as fill with a bold border in
  the same color, painted at full opacity.

Verify the exact error color accessor against the Jewel version used by
the plugin before implementing; do not invent color constants.

Do not hardcode the welcome screen gradient blues (`0xFF3070FD`,
`0xFF5C71F6`) or any other literal color. The single allowed opacity is the
0.8 alpha on normal blocks; no other transparency values are permitted.
If a block still looks wrong, the fix is choosing a better theme color,
never adding more opacity tuning.
No per-category palette in the MVP. Category identity is already conveyed
by the row label. Category colors may be introduced later together with
filtering features.

## Layout Rules (Hard Contract)

These rules exist to keep the view visually stable across panel sizes.

- Fixed skeleton: row height 28dp, label column width 64dp, block corner
  radius 6dp, block vertical inset 8dp top and bottom (so block height is
  12dp), minimum block width 8dp, and a 12dp gap between all horizontally
  adjacent elements. These values never change with panel size.
- Navigation buttons use the default Jewel `IconButton` size. Do not
  override button dimensions, padding, or icon size; the platform default
  is correct by definition.
- The only scaling dimension is the horizontal axis (percentage positions
  and widths).
- Fonts: use only the default typography inherited from the Compose theme.
  Do not use reduced font styles, do not derive custom sizes, do not invent
  a font scale. The user's IDE font and zoom settings must apply naturally.
- Sizing values in this document (28dp row height, 64dp label column,
  6dp radius, 8dp minimum block width) are literal values for this view
  only. Do not reuse any shared spacing or dimension constants from the
  plugin codebase as these sizes, and do not register these values in any
  shared constants object. The timeline owns its own metrics.

## Duration Display

- Under 1 second: show milliseconds, for example `412ms`.
- 1 second or more: show seconds with one decimal, for example `1.4s`.
- Never round sub-second durations to `0.x s`.

Timestamps are epoch milliseconds end to end; precision is never lost in
derived values, only in display formatting.

## Code Standards

`docs/CODING_STANDARDS_KOTLIN.md` is binding for this feature. Read it
before writing any code and follow it exactly. Highlights that apply here:

- Functions use camelCase with no underscores; variable names combine two
  words rather than a single word.
- No inline fully-qualified class names; import and use short names.
- Do not add explanatory comments to self-evident code.
- Follow the naming, structure, and formatting conventions already visible
  in the `gradum.idea.settings` package; existing files there are the
  reference examples.

## File Naming and Location

All new files live in `plugin/src/main/kotlin/gradum/idea/settings/`,
matching the existing settings package:

- `TimelineAuditSection.kt`: the `Statistics & Analysis` group composables (navigation row, identity line, host of the
  timeline composable).
- `TurnTimeline.kt`: the single Composable that draws rows, blocks, and the
  axis with the Canvas API.
- Any sidecar reading helper goes into its own file with a two-word name (for example `TurnEventLoader.kt`) instead of
  growing the section file.

Register the new group inside the existing `GradumConfigurable` below the
editor group. Do not create a second `Configurable` class.

## Technical Constraints

- The UI is Compose (Jewel), consistent with the rest of the plugin. The
  settings page hosts the view inside a `ComposePanel`; do not fall back to
  Swing components for any part of the feature.
- Reading sidecar files is IO and must not block UI construction. Render
  the skeleton first, then load data and fill in the content.
- Only the most recent session needs to be supported in the MVP.
- The timeline itself is a single Compose composable that draws the rows,
  blocks, and axis with the Canvas API. No third-party chart library.

## Non-Goals (Explicitly Deferred)

The following are out of scope for the MVP and must not be implemented:

- Hover tooltips or any mouse interaction on blocks (a detail card may come
  later).
- Full-session multi-turn timeline view (the MVP is single turn only).
- Long model pause indicators.
- Per-category colors.
- Filtering by file or category.
- Click-to-jump between the timeline and chat bubbles.
- Live streaming updates (the timeline renders completed turns only).
- Any server-side change.

## Definition of Done

- The settings page shows the `Statistics & Analysis` group below the editor
  group, with the summary card and the timeline for the most recent session.
- Turn navigation switches turns and re-renders correctly.
- Resizing the settings panel keeps the skeleton metrics fixed and scales
  only the horizontal axis.
- A failed operation is visually identifiable at a glance (red block).
- The feature introduces no server-side code changes and no new plugin
  dependencies.
