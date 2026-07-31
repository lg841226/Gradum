# Gradum UI Code Specification

This document defines the coding standards for all UI code under `plugin/src/main/kotlin/gradum/idea/chat/ui/`.

---

## 1. Spacing System

All spacing values must reference `GradumSpacing` constants. Magic numbers are prohibited.

| Token  | Value | Usage                                       |
|--------|-------|---------------------------------------------|
| `xs`   | 2.dp  | Minimal spacing (icon inner padding, etc.)  |
| `sm`   | 4.dp  | Small spacing (separators between elements) |
| `sml`  | 6.dp  | Small-medium spacing                        |
| `md`   | 8.dp  | Medium spacing (inner padding of blocks)    |
| `lg`   | 12.dp | Large spacing (separators between blocks)   |
| `lrl`  | 18.dp | Large-regular spacing                       |
| `ml`   | 20.dp | Medium-large spacing                        |
| `xl`   | 16.dp | Extra large spacing (page-level padding)    |
| `xxl`  | 24.dp | Extra extra large spacing                   |
| `xxxl` | 32.dp | Extra extra extra large spacing             |

## 2. Modifier Specifications

### 2.1 All Composable functions MUST have a `modifier` parameter

```kotlin
// Correct
@Composable
fun MyComponent(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(text = text, modifier = modifier)
}

// Incorrect — missing modifier parameter
@Composable
fun MyComponent(text: String) {
    Text(text = text)
}
```

### 2.2 `modifier` MUST be the last parameter

```kotlin
// Correct
fun MyComponent(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
)

// Incorrect
fun MyComponent(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit
)
```

### 2.3 Modifier Chain Order

Arrange in the following order: layout → spacing → visual → behavioral

```kotlin
Modifier
    .fillMaxWidth()                      // layout
    .height(30.dp)                       // layout
    .padding(horizontal = GradumSpacing.lg) // spacing
    .clip(shape)                         // visual
    .background(color)                   // visual
    .clickable { onClick() }             // behavioral
```

### 2.4 Rounded Background — Unified Approach

Always use `.clip(shape).background(color)` instead of `.background(color, shape)`.

```kotlin
// Correct
Modifier
    .clip(RoundedCornerShape(8.dp))
    .background(JewelTheme.globalColors.borders.normal)

// Incorrect
Modifier
    .background(JewelTheme.globalColors.borders.normal, RoundedCornerShape(8.dp))
```

### 2.5 Spacer Syntax

Always use named parameter syntax.

```kotlin
// Correct
Spacer(modifier = Modifier.height(GradumSpacing.md))

// Incorrect
Spacer(Modifier.height(8.dp))
```

## 3. Import Specifications

### 3.1 Wildcard Imports Are Prohibited

Use explicit imports only. Wildcard `*` imports are forbidden.

```kotlin
// Correct
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

// Incorrect
import androidx.compose.foundation.layout.*
```

### 3.2 Import Order

Group imports in the following order, with blank lines between groups:

```kotlin
// 1. kotlin
import kotlin.collections.isNotEmpty

// 2. java
import java.io.File

// 3. androidx
import androidx.compose.foundation.layout.Column

// 4. com.intellij
import com.intellij.openapi.project.Project

// 5. org.jetbrains
import org.jetbrains.jewel.ui.component.Text

// 6. gradum
import gradum.idea.chat.ui.common.IconTooltipButton
```

## 4. Parameter Naming and Invocation

### 4.1 Callback Parameters Use `onXxx` Prefix

```kotlin
fun MyComponent(
    onSend: () -> Unit,
    onCopy: (String) -> Unit,
    onCancel: () -> Unit
)
```

### 4.2 Boolean Parameters Use `isXxx` / `hasXxx` Prefix

```kotlin
fun MyComponent(
    isLoading: Boolean,
    hasContent: Boolean,
    isExpanded: Boolean
)
```

### 4.3 Named Parameter Invocation Is REQUIRED

```kotlin
// Correct
Text(
    text = message,
    color = JewelTheme.globalColors.text.normal,
    maxLines = 1
)

// Incorrect
Text(message, JewelTheme.globalColors.text.normal, maxLines = 1)
```

Exception: Single-parameter invocations where the type is self-evident may omit the name, e.g.,
`Spacer(modifier = Modifier.height(8.dp))`.

## 5. Color Specifications

- ONLY use `JewelTheme.globalColors.*` to access colors
- Direct references to `LocalColorPalette`, `Color.Green`, `Color.Red`, or any hardcoded colors are prohibited
- Colors MUST be obtained through the theme system

```kotlin
// Correct
color = JewelTheme.globalColors.text.info

// Incorrect
color = LocalColorPalette.current.greenOrNull(9) ?: Color.Green
```

## 6. State Management

When there are more than 3 related parameters, they MUST be encapsulated as State / Actions objects.

```kotlin
// Correct — scanState encapsulation
data class ModelBarState(
    val models: List<Model>,
    val selectedModel: Model?,
    val pinnedModels: List<Model>,
    val isAutoSelected: Boolean
)

data class ModelBarActions(
    val onSelectModel: (Model) -> Unit,
    val onTogglePin: (Model) -> Unit,
    val onSelectAuto: () -> Unit
)

@Composable
fun ModelSelectorBar(
    state: ModelBarState,
    actions: ModelBarActions,
    modifier: Modifier = Modifier
)

// Incorrect — 8 scattered parameters
@Composable
fun ModelSelectorBar(
    models: List<Model>,
    selectedModel: Model?,
    pinnedModels: List<Model>,
    isAutoSelected: Boolean,
    onSelectModel: (Model) -> Unit,
    onTogglePin: (Model) -> Unit,
    onSelectAuto: () -> Unit
)
```

## 7. KDoc Specifications

### 7.1 All public composable functions MUST have KDoc

```kotlin
/** Displays a chat message bubble for assistant responses. */
@Composable
fun AssistantChatBubble(/*...*/)

/**
 * Renders a fenced code block with syntax highlighting.
 *
 * @param block The fenced code block content.
 * @param styling Code block visual styling.
 * @param enabled Whether the block is interactive.
 * @param modifier Modifier applied to the container.
 */
@Composable
fun CodeBlock(
    block: FencedCodeBlock,
    styling: MarkdownStyling.Code.Fenced,
    enabled: Boolean,
    modifier: Modifier = Modifier
)
```

### 7.2 Private Functions

Private composables serving the same file do not require KDoc, unless the logic is complex.

## 8. File Organization

- One public composable per file; file name MUST match the composable name
- Private helper composables reside in the same file as their owning composable
- Data model classes (sealed classes, etc.) go in separate files and are NOT mixed into composable files
