# Gradum Skill Development Guide

This document explains how to develop new skills (Skill) for Gradum.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Skill Type System](#2-skill-type-system)
3. [Skill Abstract Base Class](#3-skill-abstract-base-class)
4. [SkillResult Output Format](#4-skillresult-output-format)
5. [getSchema() Format](#5-getschema-format)
6. [Developing a New Skill: Step-by-Step Guide](#6-developing-a-new-skill-step-by-step-guide)
7. [Complete Example: File Counter Skill](#7-complete-example-file-counter-skill)
8. [Best Practices](#8-best-practices)
9. [Existing Skills Reference](#9-existing-skills-reference)
10. [Declaring
    `allowedToolModes` — The Three-Tier Permission Model](#10-declaring-allowedtoolmodes--the-three-tier-permission-model)
11. [Using `SkillContext` for Project Root and Mode](#11-using-skillcontext-for-project-root-and-mode)
12. [Conversation History Management](#12-conversation-history-management)
13. [Troubleshooting](#13-troubleshooting)
14. [Coding Style](#14-coding-style)

## 1. Architecture Overview

Gradum uses **classpath scanning** for automatic skill discovery — skills are NOT hardcoded and require NO manual
registration. A skill is a Kotlin class that extends the `Skill` abstract base class and lives under
`src/main/kotlin/gradum/skill/`.

**Key characteristics:**

- Skills are fully decoupled from each other
- All skills share a unified `SkillResult` output shape
- Skills are discovered automatically by scanning the `gradum.skill` package
- Just create a class extending `Skill` — no configuration needed

**Registration flow (classpath scanning):**

```mermaid
flowchart TD
    subgraph Registry["SkillRegistry (singleton)"]
        Init["init { discoverSkills() }"]
        Init --> SCAN["Scan gradum.skill package"]
        SCAN --> R1["ReadFileSkill"]
        SCAN --> R2["EditFileSkill"]
        SCAN --> R3["SaveFileSkill"]
        SCAN --> R4["RunCommandSkill"]
        SCAN --> R5["TodoSkill"]
        SCAN --> R6["CompletePlanSkill"]
        SCAN --> R7["ExploreProjectSkill"]
    end

    subgraph Map["registeredSkills: Map<String, Skill>"]
        Key1["'read_file' -> ReadFileSkill"]
        Key2["'edit_file' -> EditFileSkill"]
        Key3["'save_file' -> SaveFileSkill"]
        Key4["'run_cmd' -> RunCommandSkill"]
        Key5["'explore_project' -> ExploreProjectSkill"]
        Key6["'to_do' -> TodoSkill"]
        Key7["'finish_to_do_item' -> CompletePlanSkill"]
    end

    R1 --> Key1
    R2 --> Key2
    R3 --> Key3
    R4 --> Key4
    R5 --> Key5
    R6 --> Key6
    R7 --> Key7
    Agent[Agent.kt] -->|"skillRegistry.getSkill(name)"| SkillLook[Look up by key]
    SkillLook -->|matches| Skills[Skills in the registry]
    style Registry fill:#3b82f6
    style Map fill:#34d399
    style Agent fill:#f59e0b
```

### Adding a New Skill (Fully Automatic)

No code changes, no configuration files — just create the class:

1. Create your skill class extending `Skill` in `src/main/kotlin/gradum/skill/`
2. That's it! The skill is automatically discovered at startup

```kotlin
package gradum.skill

class YourCustomSkill : Skill() {
    override val skillName: String = "your_tool"
    override val alias: String = "Done"
    override val description: String = "What your skill does"

    override fun getSchema(): Map<String, Any> = /* ... */
    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult = /* ... */
}
```

## 2. Skill Type System

```mermaid
classDiagram
    class Skill {
        <<abstract>>
        +skillName: String
        +description: String
        +alias: String
        +execute(arguments: Map) SkillResult
        +getSchema() Map
        +historyKeepCount: Int // Recent N results keep volatile keys
        +historyVolatileKeys: List~String~ // Keys stripped from older results
        +prepareHistoryResult(result: Map) Map
    }

    class SkillResult {
        <<sealed>>
    }

    class Success {
        +data: Map
    }

    class Failure {
        +code: String
        +message: String
        +context: Map
    }

    class SkillRegistry {
        -registeredSkills: Map
        +getSkill(name: String) Skill?
        +getAllSkills() Collection
        +getSchemas() List
    }

    class ReadFileSkill
    class EditFileSkill
    class SaveFileSkill
    class RunCommandSkill
    class TodoSkill
    class CompletePlanSkill

    SkillResult <|-- Success
    SkillResult <|-- Failure
    Skill <|-- ReadFileSkill
    Skill <|-- EditFileSkill
    Skill <|-- SaveFileSkill
    Skill <|-- RunCommandSkill
    Skill <|-- TodoSkill
    Skill <|-- CompletePlanSkill
    SkillRegistry o-- Skill
```

---

## 3. Skill Abstract Base Class

All skills extend `skill/Skill.kt`:

```kotlin
abstract class Skill {
    abstract val skillName: String
    abstract val description: String
    abstract val alias: String

    /**
     * The set of ToolMode tiers under which this skill is allowed to
     * execute. The agent enforces this both at schema-filter time
     * (hides the tool from the LLM in modes where it is not allowed)
     * and at runtime (rejects the call with TOOL_NOT_PERMITTED if the
     * model hallucinates a forbidden call). The default is "all three".
     */
    open val allowedToolModes: Set<ToolMode> = setOf(
        ToolMode.WRITE, ToolMode.SINGLE_STEP, ToolMode.READ_ONLY,
    )

    /**
     * Hint for documentation. Whether this skill mutates the project
     * (filesystem, git, process environment). The actual gate is
     * `allowedToolModes`; this is just a self-declared label.
     */
    open val mutatesProject: Boolean = false

    /**
     * Execute the skill with the LLM-supplied arguments and the
     * per-session SkillContext (tool mode + project root).
     *
     * @param arguments LLM-supplied tool-call arguments.
     * @param context per-session state owned by the agent — see §11.
     */
    abstract fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult

    abstract fun getSchema(): Map<String, Any>

    /**
     * How many recent results keep their [historyVolatileKeys] in conversation history.
     * Older results beyond this count will have those keys stripped to save context.
     * Default [Int.MAX_VALUE] keeps all results intact (no stripping).
     */
    open val historyKeepCount: Int = Int.MAX_VALUE

    /**
     * Keys to strip from history result when exceeding [historyKeepCount].
     * Only relevant when [historyKeepCount] is not [Int.MAX_VALUE].
     */
    open val historyVolatileKeys: List<String> = emptyList()

    private var prepareHistoryCallCount: Int = 0

    /**
     * Resets the internal history call counter.
     * Should be called at the start of each session to ensure
     * [historyKeepCount] behaves correctly across sessions.
     */
    fun resetHistoryCount() {
        prepareHistoryCallCount = 0
    }

    open fun prepareHistoryResult(result: Map<String, Any>): Map<String, Any> {
        prepareHistoryCallCount++
        if (historyKeepCount == Int.MAX_VALUE || historyVolatileKeys.isEmpty()) {
            return result
        }
        return if (prepareHistoryCallCount <= historyKeepCount) {
            result
        } else {
            result.filterKeys { it !in historyVolatileKeys }
        }
    }
}
```

> **Heads up — signature change.** `execute` now takes a second argument
> `context: SkillContext`. The previous single-argument signature
> `execute(arguments: Map<String, Any>)` is gone. See [§11](#11-using-skillcontext-for-project-root-and-mode).

### Required Class Properties

| Property      | Type     | Purpose                                 | Example                               |
|---------------|----------|-----------------------------------------|---------------------------------------|
| `skillName`   | `String` | Unique name used for LLM function calls | `"edit_file"`                         |
| `description` | `String` | Description shown to the LLM            | `"Atomic find-and-replace in a file"` |
| `alias`       | `String` | Verb (past-tense) used in NDJSON events | `"Ran"`, `"Planned"`                  |

### Required Methods

| Method                                                        | Return                                       | Purpose                                               |
|---------------------------------------------------------------|----------------------------------------------|-------------------------------------------------------|
| `execute(arguments: Map<String, Any>, context: SkillContext)` | Main entry point for skill logic             | Dispatched by the Agent when the LLM invokes the tool |
| `getSchema(): Map<String, Any>`                               | Returns an OpenAI-compatible function schema | Determines what parameters the LLM sees               |

### Optional Properties

| Property              | Type            | Default                           | Purpose                                                                                                              |
|-----------------------|-----------------|-----------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `allowedToolModes`    | `Set<ToolMode>` | `{WRITE, SINGLE_STEP, READ_ONLY}` | The tiers under which this skill is allowed to run. See [§10](#10-declaring-allowedtoolmodes--the-three-tier-model). |
| `mutatesProject`      | `Boolean`       | `false`                           | Self-declared "this skill writes to the project" hint. The actual gate is `allowedToolModes`.                        |
| `historyKeepCount`    | `Int`           | `Int.MAX_VALUE`                   | Keep this many recent results intact; strip volatile keys beyond                                                     |
| `historyVolatileKeys` | `List<String>`  | `emptyList()`                     | Keys to remove from history result when exceeding `historyKeepCount`                                                 |

### Optional Hooks

| Method                                                             | Return                                                     | Purpose                                                     |
|--------------------------------------------------------------------|------------------------------------------------------------|-------------------------------------------------------------|
| `prepareHistoryResult(result: Map<String, Any>): Map<String, Any>` | Post-process result before saving to `conversationHistory` | By default handles `historyKeepCount`/`historyVolatileKeys` |

---

## 4. SkillResult Output Format

The result of every skill is unified as the `SkillResult` sealed class, defined in `SkillResult.kt`:

```kotlin
sealed class SkillResult {
    data class Success(val data: Map<String, Any>) : SkillResult()
    data class Failure(
        val code: String,
        val message: String,
        val context: Map<String, Any> = emptyMap()
    ) : SkillResult()
}
```

The project ships two factory functions — always use them:

```kotlin
makeSuccess(mapOf("path" to resolvedPath.toString(), "bytesWritten" to bytesWritten))

makeFailure(
    "FILE_NOT_FOUND",
    "File not found: $path",
    mapOf("path" to resolvedPath.toString())
)
```

The Agent flattens `SkillResult` into the following shape before writing to the conversation history and NDJSON:

```mermaid
flowchart LR
    Exec["skill.execute(arguments)"] --> Result{SkillResult}
    Result -->|" Success(data) "| SUCC["success: true<br/>+ data fields flattened"]
    Result -->|" Failure(code, msg, ctx) "| FAIL["success: false<br/>+ error: {code, message}"]
    SUCC --> NDJSON["emitEvent(tool_call)"]
    FAIL --> NDJSON
    NDJSON --> HIST["append to conversation history"]
    style Exec fill: #3b82f6
    style SUCC fill: #34d399
    style FAIL fill: #f87171
    style NDJSON fill: #0ea5e9
    style HIST fill: #a78bfa
```

```
// Success ->
{"success": true, ...data fields flattened}

// Failure ->
{"success": false, "error": {"code": "FILE_NOT_FOUND", "message": "..."}}
```

### Standard Error Codes

| Error Code            | Applicable Scenario                                              |
|-----------------------|------------------------------------------------------------------|
| `INVALID_PARAMETER`   | Missing or malformed parameter                                   |
| `FILE_NOT_FOUND`      | Target file does not exist                                       |
| `FILE_TOO_LARGE`      | File size or line count exceeds limits                           |
| `IO_ERROR`            | Filesystem or process exception                                  |
| `CODE_NOT_FOUND`      | Edit search text does not appear in the file                     |
| `MULTIPLE_MATCHES`    | Edit search text appears multiple times in the file              |
| `EMPTY_RESULT`        | The file becomes empty after editing, prevents accidental wiping |
| `COMMAND_BLOCKED`     | Command rejected by the safety filter                            |
| `TIMEOUT`             | Operation timed out                                              |
| `ALREADY_INITIALIZED` | Duplicate initialization (e.g. to_do)                            |
| `NOT_INITIALIZED`     | Operation requires initialization that has not happened          |
| `ALL_COMPLETED`       | All tasks are already completed                                  |
| `CLIENT_ERROR`        | Plugin-side error (e.g. model validation failed)                 |

---

## 5. getSchema() Format

Return a Kotlin `Map<String, Any>` whose structure is fully compatible with the OpenAI function calling schema. Use
Kotlin Map literals — do not embed a JSON string:

```kotlin
override fun getSchema(): Map<String, Any> {
    return mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to skillName,
            "description" to "What the skill does. Include when to use and constraints.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf(
                        "type" to "string",
                        "description" to "File path to read. Use relative path."
                    ),
                    "lineRange" to mapOf(
                        "type" to "string",
                        "description" to "Optional. Line range to read. Format: '10-50'."
                    )
                ),
                "required" to listOf("path")
            )
        )
    )
}
```

The `description` field is the primary mechanism for guiding the LLM to use the tool correctly. It should include:

- When to use the skill
- Limitations and constraints
- Usage examples
- Hints about related skills

---

## 6. Developing a New Skill: Step-by-Step Guide

### 6.1 Create the File

Create `src/main/kotlin/gradum/skill/YourSkill.kt`:

```kotlin
package gradum.skill

import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.io.File
import java.nio.file.Path

/**
 * Brief description of what the skill does.
 *
 * What this skill does: when it is called, what it outputs.
 */
class YourSkill : Skill() {

    override val skillName: String = "your_skill"
    override val alias: String = "Done"
    override val description: String =
        "What this skill does. Include when to use it, constraints, and output format."

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "input" to mapOf(
                            "type" to "string",
                            "description" to "What this parameter means."
                        )
                    ),
                    "required" to listOf("input")
                )
            )
        )
    }

    /**
     * @param arguments LLM-supplied tool-call arguments. The
     *   `projectRoot` is provided by the agent; do NOT read it from
     *   here — use [context].projectRoot instead.
     * @param context per-session state (tool mode + project root).
     */
    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val inputValue: String = arguments["input"] as? String ?: ""

        if (inputValue.isBlank()) {
            return makeFailure("INVALID_PARAMETER", "Missing 'input' parameter")
        }

        // Read the project root from the SkillContext, not from arguments
        // or from a process-global. See §11 for the full rationale.
        val projectRoot: String = context.projectRoot
        val resolvedPath: Path = Path.of(projectRoot, inputValue).toAbsolutePath().normalize()
        val targetFile: File = resolvedPath.toFile()

        return try {
            val fileContent: String = targetFile.readText(Charsets.UTF_8)
            // your logic here
            makeSuccess(
                mapOf(
                    "path" to resolvedPath.toString(),
                    "size" to fileContent.length
                )
            )
        } catch (exception: Exception) {
            makeFailure("IO_ERROR", exception.message ?: "Unknown error", mapOf("path" to resolvedPath.toString()))
        }
    }
}
```

### 6.2 Register the Skill

**No registration required!** The `SkillRegistry` automatically discovers all classes
in the `gradum.skill` package that extend `Skill`. Just place your class file in
`src/main/kotlin/gradum/skill/` and it will be found at startup.

### 6.3 Build and Test

```bash
./gradlew build          # compile, verify no compile errors
./gradlew run            # or launch the server to test

# after launch, call the skills endpoint to verify registration
curl http://localhost:8765/skills | jq
```

---

## 7. Complete Example: File Counter Skill

```kotlin
package gradum.skill

import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import java.io.File
import java.nio.file.Path

class FileCounterSkill : Skill() {

    override val skillName: String = "file_counter"
    override val alias: String = "Counted"
    override val description: String =
        "Count lines, words, and characters in a file. Use when you need to know file size or complexity."

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "File path to analyze"
                        )
                    ),
                    "required" to listOf("path")
                )
            )
        )
    }

    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        val filePath: String = arguments["path"] as? String ?: ""

        if (filePath.isBlank()) {
            return makeFailure("INVALID_PARAMETER", "Missing 'path' parameter")
        }

        // Resolve relative to the session's projectRoot (NOT the server CWD).
        val projectRoot: String = context.projectRoot
        val resolvedPath: Path = Path.of(projectRoot, filePath).toAbsolutePath().normalize()
        val targetFile: File = resolvedPath.toFile()

        val fileContent: String = try {
            targetFile.readText(Charsets.UTF_8)
        } catch (_: java.io.FileNotFoundException) {
            return makeFailure("FILE_NOT_FOUND", "File not found: $filePath", mapOf("path" to resolvedPath.toString()))
        } catch (exception: Exception) {
            return makeFailure(
                "IO_ERROR",
                exception.message ?: "Failed to read file",
                mapOf("path" to resolvedPath.toString())
            )
        }

        val lines: Int = fileContent.lines().size
        val words: Int = fileContent.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val chars: Int = fileContent.length

        return makeSuccess(
            mapOf(
                "path" to resolvedPath.toString(),
                "lines" to lines,
                "words" to words,
                "characters" to chars
            )
        )
    }
}
```

---

### 6.4 Optimize Token Usage (Optional)

If your skill returns large data (like file content), use `historyKeepCount` and `historyVolatileKeys` to keep recent
results intact while stripping older ones from conversation history. The stripped data still appears in the NDJSON event
stream for the frontend, but the LLM won't re-read older large payloads every turn:

```kotlin
class YourSkill : Skill() {
    override val historyKeepCount: Int = 2          // Keep last 2 results with full data
    override val historyVolatileKeys: List<String> = listOf("largeField")  // Strip this key from older results
}
```

For more granular control, you can override `prepareHistoryResult` directly instead.

Refer to `ReadFileSkill` for a real-world example.

---

### 6.5 Real-World Skill Examples

#### ReadFileSkill (Read-only inspection)

| Property              | Value                              |
|-----------------------|------------------------------------|
| `skillName`           | `"read_file"`                      |
| `alias`               | `"Read"`                           |
| `allowedToolModes`    | Default (all three modes)          |
| `historyKeepCount`    | 5 (keeps content for last 5 calls) |
| `historyVolatileKeys` | `listOf("content")`                |

**Key behavior**: Resolves relative paths against `context.projectRoot`, computes MD5 hash of content, returns path +
lineRange + totalLines + contentHash + content. Max file size 1MB, max 10,000 lines.

#### EditFileSkill (Mutating, search-and-replace)

| Property              | Value                                                                        |
|-----------------------|------------------------------------------------------------------------------|
| `skillName`           | `"edit_file"`                                                                |
| `alias`               | `"Edited"`                                                                   |
| `allowedToolModes`    | `setOf(WRITE, SINGLE_STEP)` — **excludes READ_ONLY**                         |
| `historyKeepCount`    | 2                                                                            |
| `historyVolatileKeys` | `listOf("syntaxErrors", "linesAdded", "linesRemoved", "totalEdits", "path")` |

**Key behavior**: Two edit modes — **Sequential** (apply one-by-one, stop on failure) and **Atomic** (all-or-nothing
rollback). Custom `prepareHistoryResult` strips `originalContent` and `modifiedContent` from ALL history entries.

#### RunCommandSkill (Shell execution)

| Property              | Value                     |
|-----------------------|---------------------------|
| `skillName`           | `"run_cmd"`               |
| `alias`               | `"Ran"`                   |
| `allowedToolModes`    | Default (all three modes) |
| `historyKeepCount`    | 2                         |
| `historyVolatileKeys` | `listOf("output")`        |

**Key behavior**: Pre-classifies every command via `classifyCommand()`. 45-second hard timeout. Two execution modes: *
*Blocking** (waits for completion) and **Detached** (background, returns PID + log path).

#### ExploreProjectSkill (Directory tree scan)

| Property              | Value                                                           |
|-----------------------|-----------------------------------------------------------------|
| `skillName`           | `"explore_project"`                                             |
| `alias`               | `"Explored"`                                                    |
| `allowedToolModes`    | Default (all three modes)                                       |
| `historyKeepCount`    | 1 (only keeps full tree for first call)                         |
| `historyVolatileKeys` | Custom `prepareHistoryResult` collapses tree to top-level names |

**Key behavior**: Recognizes and truncates build/dependency dirs (`.git`, `build`, `node_modules`, `__pycache__`,
`.venv`, `target`, etc.). All dotfile dirs are truncated.

#### TodoSkill and CompletePlanSkill (Task planning)

| Property           | Value                                                   |
|--------------------|---------------------------------------------------------|
| `skillName`        | `"to_do"` / `"finish_to_do_item"`                       |
| `allowedToolModes` | `setOf(WRITE)` — **excludes READ_ONLY and SINGLE_STEP** |

**Key behavior**: Both share a singleton `TodoManager` that maintains the in-memory task list. Reminder text is injected
automatically after each tool call to keep the model on track.

---

## 8. Best Practices

### 8.1 Parameter Handling

- Always validate required parameters
- Read from `Map<String, Any>` with `as? String ?: ""`
- Trim string inputs consistently

```kotlin
val filePath: String = arguments["path"] as? String ?: ""
if (filePath.isBlank()) {
    return makeFailure("INVALID_PARAMETER", "Missing 'path' parameter")
}
```

### 8.2 Error Handling

- Catch specific exceptions first (FileNotFoundException before the generic Exception)
- Always return a structured `SkillResult.Failure`, **never** throw exceptions
- Provide diagnostic information inside `context`

```kotlin
return try {
    targetFile.readText(Charsets.UTF_8)
    makeSuccess(/*...*/)
} catch (exception: FileNotFoundException) {
    makeFailure("FILE_NOT_FOUND", "File not found: $filePath", mapOf("path" to resolvedPath.toString()))
} catch (exception: Exception) {
    makeFailure("IO_ERROR", exception.message ?: "Failed to read", mapOf("path" to resolvedPath.toString()))
}
```

### 8.3 File I/O

- Always use `Charsets.UTF_8`
- Resolve paths with `Path.toAbsolutePath().normalize()`
- `File.readText()` is the recommended way to read small files
- For large files, consider streaming or bounded reads (see the FILE_TOO_LARGE handling in ReadFileSkill)

### 8.4 LLM Guidance

The `description` field in `getSchema()` is the key mechanism for guiding the LLM to use the tool correctly. Include:

- When to use the skill
- Scenarios where it should not be used
- Example parameter formats
- Hints about related skills

### 8.5 Shell Command Safety

If your skill involves `ProcessBuilder` or `Runtime.exec()`:

- **Must** perform a pre-check via `classifyCommand(commandText: String): CommandVerdict`
- When blocked, return the `COMMAND_BLOCKED` error code
- Mark the call site with `@OptIn(DangerousOperation::class)`

```kotlin
@OptIn(DangerousOperation::class)
override fun execute(arguments: Map<String, Any>): SkillResult {
    val commandText: String = arguments["command"] as? String ?: ""
    val classification: CommandVerdict = classifyCommand(commandText)
    if (classification is CommandVerdict.Blocked) {
        return makeFailure(
            "COMMAND_BLOCKED",
            "Blocked by safety filter: ${classification.description}",
            mapOf("command" to commandText, "rule" to classification.ruleName)
        )
    }
    // ... execute the command
}
```

### 8.6 Path Handling

- Use `java.nio.file.Path` instead of `java.io.File` for path construction
- Always normalize: `Path.of(path).toAbsolutePath().normalize()`

### 8.7 State Management with TodoManager

Need to maintain state across multiple tool calls (e.g. the task list in TodoSkill)? Use a package-level private
singleton:

```
private val sharedTodoManager: TodoManager = TodoManager()

fun getTodoManagerInstance(): TodoManager = sharedTodoManager
```

- Do not use mutable state inside a `companion object` — the pattern above is clearer.

---

## 9. Existing Skills Reference

| Skill Class         | skillName           | Purpose                                          |
|---------------------|---------------------|--------------------------------------------------|
| `ReadFileSkill`     | `read_file`         | Read a file (full content or a line range)       |
| `EditFileSkill`     | `edit_file`         | Search-and-replace editing (sequential / atomic) |
| `SaveFileSkill`     | `save_file`         | Write to or create a file                        |
| `RunCommandSkill`   | `run_cmd`           | Execute a shell command (blocking / detached)    |
| `TodoSkill`         | `to_do`             | Initialize a task list                           |
| `CompletePlanSkill` | `finish_to_do_item` | Mark a task as completed                         |

---

## 10. Declaring `allowedToolModes` — The Three-Tier Permission Model

Gradum exposes **three permission tiers** through a single field on every
`Skill` — `allowedToolModes`. The agent enforces it twice (schema filter at
LLM time, runtime gate at execution time) so the two views can never drift.
The `SkillRegistrySchemaTest` pins this invariant.

### 10.1 The three tiers

| Tier          | Wire format     | UI Label   | When to use                                                                     |
|---------------|-----------------|------------|---------------------------------------------------------------------------------|
| `READ_ONLY`   | `"read_only"`   | Read-only  | Pure inspection (read file, scan tree, run `cat`/`ls`/`grep`)                   |
| `SINGLE_STEP` | `"single_step"` | Edit mode  | Single-shot edits (`edit_file`, `save_file`) — no multi-step planning           |
| `WRITE`       | `"write"`       | Agent mode | Full autonomy including multi-step task planning (`to_do`, `finish_to_do_item`) |

A skill should declare the **narrowest** set of tiers that covers what it does.
Anything else weakens the safety net for the user.

### 10.2 Decision table

| Does the skill ...                                                | Declare `allowedToolModes`                                  | Example skills                                                            |
|-------------------------------------------------------------------|-------------------------------------------------------------|---------------------------------------------------------------------------|
| Never writes the filesystem, never starts a mutating process      | `{READ_ONLY, SINGLE_STEP, WRITE}` (the default — all three) | `read_file`, `explore_project`, `run_cmd` (with `classifyCommand` filter) |
| Writes the filesystem but doesn't multi-step plan                 | `{SINGLE_STEP, WRITE}`                                      | `edit_file`, `save_file`                                                  |
| Drives the agent loop (initializes a task list, marks completion) | `{WRITE}`                                                   | `to_do`, `finish_to_do_item`                                              |

### 10.3 Worked example

```kotlin
class EditFileSkill : Skill() {
    override val skillName: String = "edit_file"
    override val alias: String = "Edited"
    override val description: String = "Atomic find-and-replace in a file."

    // EditFileSkill mutates the project. It is allowed in SINGLE_STEP
    // (single-shot edit) and WRITE (full agent loop), but NEVER in
    // READ_ONLY. A user in READ_ONLY mode physically cannot trigger it,
    // even if the LLM hallucinates a call.
    override val allowedToolModes: Set<ToolMode> = setOf(
        ToolMode.SINGLE_STEP,
        ToolMode.WRITE,
    )

    override val mutatesProject: Boolean = true

    override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
        // ...
    }
}
```

### 10.4 How the gate is enforced

The agent runs the same `toolMode in skill.allowedToolModes` check at two
points, so the LLM cannot bypass it:

1. **Schema filter** (LLM side). `SkillRegistry.getSchemas(toolMode)` returns
   only the tools whose `allowedToolModes` includes the active tier. The LLM
   is never told the tool exists in modes where it is forbidden.
2. **Runtime gate** (Agent side). `Agent.executeSingleTool` does the same
   check before dispatch. If the LLM hallucinates an `edit_file` call in
   `READ_ONLY`, the agent returns `TOOL_NOT_PERMITTED` and the file on disk
   is byte-for-byte unchanged.

For a `READ_ONLY` `run_cmd`, the agent also runs `classifyCommand(...)`
with the active `ToolMode` so `touch`, `rm`, `git commit` etc. are blocked
with `COMMAND_BLOCKED` even when the schema filter let them through.

### 10.5 Testing your tier declaration

`SkillRegistrySchemaTest` (5 cases) and `ToolModeGateTest` (6 cases) pin
the contract. A new skill that declares a tier set must satisfy both:

```kotlin
// SkillRegistrySchemaTest style:
val allSkills = SkillRegistry.discoverSkills()
val writeTier = SkillRegistry.getSchemas(ToolMode.WRITE)
assert(writeTier.any { it.matches(yourSkill) })  // WRITE always sees everything

val readOnlyTier = SkillRegistry.getSchemas(ToolMode.READ_ONLY)
if (yourSkill.allowedToolModes == setOf(ToolMode.READ_ONLY, ToolMode.SINGLE_STEP, ToolMode.WRITE)) {
    assert(readOnlyTier.any { it.matches(yourSkill) })
} else {
    assert(readOnlyTier.none { it.matches(yourSkill) })
}
```

---

## 11. Using `SkillContext` for Project Root and Mode

`SkillContext` is a small data class the agent constructs **once per
session** and hands to every `Skill.execute` call:

```kotlin
data class SkillContext(
    val toolMode: ToolMode,
    val projectRoot: String,
)
```

It replaces the legacy process-global `ProjectPaths.setProjectRoot` (and
removes the previous blind spot: Skills had no way to read `toolMode` at
all). The two properties are immutable for the lifetime of the session, and
the agent guarantees every Skill in that session sees the same instance.

### 11.1 Why a parameter, not a global

Three reasons — the third one is the one that bit us before this refactor:

1. **Sessions can run concurrently.** Two `/events` requests in flight at
   once would have shared `ProjectPaths` and overwritten each other's
   project root. With `SkillContext` constructed per `Agent`, sessions are
   fully isolated.
2. **Auditability.** Reading `context.projectRoot` in a Skill makes the
   dependency visible in the function signature. There is no way to
   "forget" to pass it, and unit tests can construct a deterministic
   `SkillContext` without touching process-globals.
3. **The bug this fixes.** Before this refactor, `ContextManager` was
   constructed from `ProjectPaths.outputDirectory()` — which defaulted to
   the server's CWD, not the IDE's project. So if the developer started
   the server from a workspace different from the project they had open
   in IntelliJ, `context.json` would silently be written to the wrong
   project. With `SkillContext.projectRoot` flowing from `Project.basePath`
   all the way down, every file path in the agent loop resolves against
   the project the user is actually working on.

### 11.2 Where the values come from

```mermaid
sequenceDiagram
    participant IDE
    participant Plugin
    participant Server
    participant Agent
    participant Skill
    IDE ->> Plugin: User opens project → Project.basePath
    Plugin ->> Server: POST /events {message, projectRoot: basePath, toolMode: "read_only"}
    Note over Server: Routes validates projectRoot is non-empty<br/>+ points to an existing directory
    Server ->> Agent: new Agent(AgentConfiguration(toolMode, projectRoot))
    Note over Agent: ContextManager(<root>/.gradum)<br/>+ SkillContext(toolMode, projectRoot)
    Agent ->> Skill: skill.execute(arguments, skillContext)
    Note over Skill: read context.projectRoot for file ops
```

The plugin is the **only** source of truth for `projectRoot`. The server
has no fallback — if the plugin forgets to send it, `Routes` returns
`400 INVALID_PARAMETER` instead of guessing from CWD.

### 11.3 How to use it in your Skill

```kotlin
override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    // Read these from context — never from arguments, never from a global.
    val projectRoot: String = context.projectRoot
    val toolMode: ToolMode = context.toolMode

    // Resolve a relative path the LLM gave you against the session's root.
    val inputPath: String = arguments["path"] as? String ?: ""
    val resolved: Path = Path.of(projectRoot, inputPath).toAbsolutePath().normalize()

    // For mode-aware behavior, branch on context.toolMode. The agent
    // owns the actual gate; this is informational.
    val logDirectory: Path = when (toolMode) {
        ToolMode.READ_ONLY -> Path.of(projectRoot, ".gradum", "logs", "readonly")
        else -> Path.of(projectRoot, ".gradum", "logs", "write")
    }

    // ...
}
```

### 11.4 What NOT to do

```kotlin
// WRONG — reads from arguments. The agent injects projectRoot for
// audit/NDJSON reasons, but Skills should treat the arguments as
// "what the LLM sent us" and read session state from context.
val projectRoot: String = arguments["projectRoot"] as? String ?: ""

// WRONG — process-global. Two concurrent sessions will overwrite
// each other.
val projectRoot: String = ProjectPaths.outputDirectory().toString()

// WRONG — derives from CWD. This is the bug SkillContext replaces.
val projectRoot: String = System.getProperty("user.dir")

// RIGHT — single source of truth, per-session, immutable.
val projectRoot: String = context.projectRoot
```

---

## 12. Conversation History Management

### 12.1 Retention Policy

History is retained **indefinitely** — there is no time-based expiry. The only constraint is message count:

| Layer                            | Constant               | Limit | File & Line            |
|----------------------------------|------------------------|-------|------------------------|
| **Persistence** (ContextManager) | `MAX_CONTEXT_MESSAGES` | 30    | `ContextManager.kt:19` |
| **Runtime** (Agent)              | `maxHistoryMessages`   | 20    | `Agent.kt:113`         |

### 12.2 How History is Cleaned

Before saving, `ContextManager.cleanMessageHistory()` removes:

- System messages
- Tool call results
- Empty assistant messages
- Messages matching "fully read" file content

The remaining messages are capped at 30 via `takeLast(30)`.

### 12.3 Encryption

History is encrypted using custom **HMAC-CTR + HMAC-SHA256**:

- Key source: `GRADUM_CONTEXT_KEY` env var, or hardcoded fallback
- Only `user` and `assistant` messages with content are encrypted
- Storage: `<projectRoot>/.gradum/context.json`
- Write strategy: full overwrite (not append)

### 12.4 Agent-Side Truncation

Before each LLM turn, `Agent.truncateHistory()` trims to 20 messages + system prompt, preserving the most recent
messages. This prevents local LLMs from being overwhelmed.

---

## 13. Troubleshooting

### The skill is never called by the LLM

- Verify the `description` in `getSchema()` clearly explains the purpose and when to use the tool
- Confirm the skill is registered in `SkillRegistry.discoverSkills()`
- Check that parameter names are clear and reasonable
- Inspect server logs (INFO level should show `Registered skill: ...`)

### Skill execution fails

- Check parameter conversion: could `arguments["foo"] as? String` produce null?
- Verify no uncaught exceptions escape (there should always be a `catch (exception: Exception)` fallback)
- Verify file paths resolve correctly to absolute paths (use `context.projectRoot` as the base, not CWD)

### "Tool not permitted" / "Command blocked" in read-only mode

- The skill's `allowedToolModes` excludes `READ_ONLY`, or `classifyCommand`
  rejected the command. Either switch the IDE to `SINGLE_STEP`/`WRITE` mode
  (user action) or declare a broader `allowedToolModes` (skill author action).
  The agent's gate is the same on both sides — the LLM is told the tool
  doesn't exist AND the runtime rejects the call if it tries anyway.

### Context file is written to the wrong project

- Check that the plugin sends `projectRoot` in the `/events` body — without
  it, `Routes` returns 400 and the agent is never constructed. If it does
  send it, but the file still lands in the wrong directory, the Skill is
  reading from `arguments["projectRoot"]` (deprecated) or from a process
  global — both are the legacy paths this refactor replaces. Update the
  Skill to read `context.projectRoot`.

### NDJSON event fields are wrong

- Confirm the `makeSuccess()` / `makeFailure()` factory functions are used
- The Agent is responsible for converting `SkillResult` into tool messages and events — manual handling is not required

---

## 14. Coding Style

Follow `docs/CODING_STANDARDS_KOTLIN.md`:

- All public methods require full type annotations, explicit type declarations required
- Use `Map<String, Any>` rather than a bare `map`; be explicit about generic parameters
- Prefer string templates over `+` concatenation
- Use `sealed class` for result types (SkillResult)
- Use `@OptIn` for dangerous operations and experimental APIs
- KDoc on public classes and methods should explain **why**, not **what**
- 100-character line width
- Error codes: uppercase underscore format (`SCREAMING_SNAKE_CASE`)
