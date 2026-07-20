# LaTeX Math Formula Support — Implementation Plan

## Overview

Add inline (`$...$`) and block-level (`$$...$$`) LaTeX math formula rendering to the Gradum IntelliJ plugin's chat
Markdown module.

## Library Choice

**Primary:** `io.github.huarangmeng:latex` (v1.3.x)

- Kotlin 2.3.0 native compatible
- Renders directly to Compose Canvas (no WebView/AWT)
- Provides `@Composable Latex()` + `InlineTextContent` API for inline math
- MIT license, 378+ LaTeX features

**Fallback:** Regex-based detection + `huarangmeng:latex` composable rendering. If the library fails to render, show raw
LaTeX text as a styled `Text` composable.

## Architecture Decision: Lightweight Approach

| Context             | Strategy                                 | Rationale                                                                                                            |
|---------------------|------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| **Inline `$...$`**  | Regex interception in `renderTextInline` | Matches footnote pattern (`INLINE_FOOTNOTE_REGEX`); no CommonMark extension needed; covers 95%+ real-world cases     |
| **Block `$$...$$`** | CommonMark AST extension node            | Block-level constructs need split-phase recognition in `splitPlainAtBlocks`; cleaner than regex on multi-line blocks |

## Files to Modify

### New Files

| File                     | Purpose                                                                        |
|--------------------------|--------------------------------------------------------------------------------|
| `LatexRenderer.kt`       | Encapsulate `@Composable Latex()` call, fallback handling, theme-aware styling |
| `LatexBlockExtension.kt` | CommonMark extension: parse `$$...$$` into `LatexBlock` AST node               |
| `GradumLatexTest.kt`     | Unit tests for inline/block LaTeX parsing and rendering                        |

### Modified Files

| File                      | Change                                                                                          |
|---------------------------|-------------------------------------------------------------------------------------------------|
| `plugin/build.gradle.kts` | Add `latex-base`, `latex-parser`, `latex-renderer` dependencies                                 |
| `InlineMarkdown.kt`       | Add `INLINE_LATEX_REGEX`, `RenderState.allocateLatex()`,拦截 logic in `renderTextInline`        |
| `BlockSplit.kt`           | Add `is LatexBlock` branch in `splitPlainAtBlocks` + `$$...$$` serialization in `serializeInto` |
| `BlockRenderer.kt`        | Add `is LatexBlock -> RenderLatexBlock(...)` branch in `RenderBlockNode`                        |
| `Table.kt`                | Register `LatexBlockExtension` on `GradumMarkdownProcessor`                                     |
| `Styling.kt`              | Add LaTeX default styles (font size, color, thinking mode adaptation)                           |
| `AssistantChatBubble.kt`  | Likely no changes needed (segment routing handles it)                                           |

## Implementation Phases

### Phase 1: Dependencies & Infrastructure

1. Add Maven dependencies to `plugin/build.gradle.kts`:

```kotlin
implementation("io.github.huarangmeng:latex-base:1.3.0")
implementation("io.github.huarangmeng:latex-parser:1.3.0")
implementation("io.github.huarangmeng:latex-renderer:1.3.0")
```

1. Create `LatexRenderer.kt`:
    - `@Composable RenderLatexFormula(formula: String, isBlock: Boolean, modifier: Modifier)`
    - Wrap `Latex()` composable with `try/catch`
    - On failure: render raw `$...$` / `$$...$$` text in monospace with muted color
    - Theme-aware: respect `JewelTheme.contentColor` for text, thinking mode gray

### Phase 2: Block-Level LaTeX `$$...$$`

1. Create `LatexBlockExtension.kt` — CommonMark block extension:
    - Factory: `LatexBlockExtension` implementing `org.commonmark.parser.Extension`
    - Block processor: detect `$$` on its own line, collect until closing `$$`
    - AST node: `LatexBlock` extending `CustomBlock`, holds `formula: String`
    - Serializer: emit `$$\n<formula>\n$$`

2. Register extension on parsers:
    - `blockSplitParser` (BlockSplit.kt:15) — for `splitPlainAtBlocks`
    - `GradumMarkdownProcessor` (Table.kt:58) — for code block reparse path
    - `commonmarkParser` in BlockRenderer.kt:42 — for `RenderNonProseBlock`

3. `BlockSplit.kt` — `splitPlainAtBlocks`:
    - Add `is LatexBlock -> MarkdownSegment.NonProseBlock(text = serializeInto(topLevelBlock))`
    - Or introduce `MarkdownSegment.LatexBlock(formula: String)` for cleaner routing

4. `BlockSplit.kt` — `serializeInto`:
    - Add `is LatexBlock -> output.append("$$\n${currentNode.formula}\n$$")`

5. `BlockRenderer.kt` — `RenderBlockNode`:
    - Add `is LatexBlock -> RenderLatexBlock(block.formula, modifier)`

### Phase 3: Inline LaTeX `$...$`

1. `InlineMarkdown.kt` — add regex:
   ```kotlin
   internal val INLINE_LATEX_REGEX: Regex = Regex("""\$([^$\n]+?)\$""")
   ```
   (Non-greedy, no newlines, requires at least 1 char between `$` signs)

2. `RenderState` — add allocator:
   ```kotlin
   var latexCounter: Int = 0
   fun allocateLatex(formulaText: String): String {
       val placeholderKey = LATEX_PLACEHOLDER_BASE.toString().repeat(latexCounter + 1)
       latexCounter++
       // Measure formula dimensions, create Placeholder, register InlineTextContent
       // with @Composable { RenderLatexFormula(formulaText, isBlock = false) }
       return placeholderKey
   }
   ```

3. `renderTextInline` — intercept before literal append:
    - Same pattern as footnote: split `literal` at `INLINE_LATEX_REGEX` matches
    - For each match: `allocateLatex()`, push PUA placeholder + inline content tag
    - Non-matching segments: append as plain text with current style

### Phase 4: Parser Registration

1. Ensure all 4 parser instances can recognize `$$...$$` blocks:
    - `blockSplitParser` (BlockSplit.kt) ✅ (Phase 2)
    - `GradumMarkdownProcessor` (Table.kt) ✅ (Phase 2)
    - `commonmarkParser` (BlockRenderer.kt) ✅ (Phase 2)
    - `commonmarkParser` (InlineMarkdown.kt:85) — add extension here too for `parseInlineMarkdown`

### Phase 5: Styling & Testing

1. `Styling.kt` — add LaTeX styles:
    - Block: centered, with vertical padding matching blockquote
    - Inline: inherit surrounding text size, use `JewelTheme.contentColor`
    - Thinking mode: use `thinkingGray` color

2. `GradumLatexTest.kt` — test cases:
    - Inline: `$x^2$`, `$\frac{a}{b}$`, `$$x^2 + y^2 = z^2$$`
    - Edge cases: `$$` not closed, empty `$ $`, nested with bold `**$x$**`
    - Fallback: invalid LaTeX `\invalidformula`
    - Round-trip: serialize → parse → render

## Rendering Pipeline After Changes

```
Raw Markdown
    │
    ▼
splitMarkdownAtTables() ──→  Table segments (unchanged)
    │
    ▼
splitPlainAtBlocks()   ──→  LatexBlock segments (NEW: $$...$$ recognized)
    │                       Plain segments (paragraphs)
    │                       NonProseBlock segments (headings, lists, etc.)
    ▼
AssistantChatBubble routing:
    ├── Plain       → renderTextInline (with INLINE_LATEX_REGEX intercept)
    ├── NonProseBlock → RenderBlockNode → RenderLatexBlock (NEW for $$...$$)
    └── Table       → ScrollableTable (unchanged)
```

## Constraints & Notes

- **No changes to `AssistantChatBubble.kt`** if using `MarkdownSegment.NonProseBlock` for block LaTeX (simplest path).
  If introducing `MarkdownSegment.LatexBlock`, add a branch in `ResponseBlock`.
- **Inline LaTeX regex limitation:** Cannot handle `$` inside code spans (`` `$x$` ``). This is acceptable — same
  limitation as footnotes. The regex runs on `Text` node literals, which never contain code span content.
- **Performance:** `huarangmeng:latex` parses formulas on first render and caches. Use `remember(formula)` to avoid
  reparsing on recomposition.
- **Accessibility:** Consider adding `contentDescription` to the LaTeX composable for screen readers (e.g., formula text
  as alt text).
