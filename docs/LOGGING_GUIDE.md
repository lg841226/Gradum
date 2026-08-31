# Logging Guide

## Log Format

All Gradum server logs follow a Logcat-inspired format:

```
2026-08-25 21:52:32.123  14204-8765  I  SkillRegistry  Registered skill: grep (Grep)
```

| Component                       | Description                                        | Example                                          |
|---------------------------------|----------------------------------------------------|--------------------------------------------------|
| `2026-08-25 21:52:32.123`       | Timestamp (ISO 8601 with milliseconds)             | `2026-08-25 21:52:32.123`                        |
| `14204-8765`                    | PID and server port, separated by `-`              | `14204-8765`                                     |
| `I`                             | Single-letter log level with ANSI background color | `I` (green), `W` (yellow), `E` (red), `D` (blue) |
| `SkillRegistry`                 | Source class name (simple name, no package)        | `SkillRegistry`, `ModelIdentity`, `PortUtil`     |
| `Registered skill: grep (Grep)` | Log message                                        | descriptive text                                 |

## Log Levels

| Level | Letter | Background Color | Foreground Color | Usage                       |
|-------|--------|------------------|------------------|-----------------------------|
| TRACE | T      | Gray             | White            | Very detailed debugging     |
| DEBUG | D      | Blue             | White            | Debugging information       |
| INFO  | I      | Green            | Black            | Normal operations           |
| WARN  | W      | Yellow           | Black            | Expected/recoverable issues |
| ERROR | E      | Red              | White            | Unexpected failures         |

## Logger Names

Use the simple class name as the logger name. Do not invent custom tags or
abbreviations. The class name is automatically rendered by logback's
`%logger{0}` pattern converter.

```kotlin
private val logger: Logger = LoggerFactory.getLogger(SkillRegistry::class.java)
```

## Stack Trace Policy

Do not pass exception objects as the last argument to log calls for expected
or recoverable errors. This prevents logback from printing the full stack
trace, which is noise for predictable failures.

**Bad** (prints full stack trace for expected errors):
```kotlin
logger.warn("Connection failed", connectException)
```

**Good** (message only, no stack trace):
```kotlin
logger.warn("Connection failed: ${connectException.message}")
```

**Only** pass the exception object when the error is genuinely unexpected
and the stack trace is needed for debugging:
```kotlin
logger.error("Uncaught exception in probe loop", probeError)
```

## Tree Logging

For batch operations (skill registration, model discovery, etc.), use tree
format with `├──` and `└──` characters:

```
2026-08-26 16:32:19.764  14204-8765  I  SkillRegistry  ── Skills ──────
2026-08-26 16:32:19.764  14204-8765  I  SkillRegistry  ├── grep (Grep)
2026-08-26 16:32:19.808  14204-8765  I  SkillRegistry  └── finish_to_do_item (Completed)
```

Rules:
- The first line uses `── Title ──────` as a section header
- Each item uses `├── ` for all but the last item
- The last item uses `└── `
- Every line is a complete, independently greppable log entry
- Do not use ANSI colors for tree characters

## Conventions

- **Every log line must be independently grepable.** A reader should be able
  to filter by log level, source class, or message content and still
  understand the line.

- **Write messages in plain English.** Start with a capital letter, end
  without a period. Use present tense for ongoing operations, past tense for
  completed actions.

- **Include context in the message.** Do not rely on preceding log lines for
  context. A filtered log line should make sense on its own.

- **Use `{}` placeholders for variable data.** This allows logback to
  defer string formatting when the log level is not enabled:
  ```kotlin
  logger.debug("Probing {} models on {}", modelCount, serverName)
  ```

## Custom Converters

Two custom logback converters are registered in `logback.xml`:

| Conversion Word | Class                  | Description                                          |
|-----------------|------------------------|------------------------------------------------------|
| `%gradumLevel`  | `GradumLevelConverter` | Single-letter level with ANSI background color       |
| `%gradumPort`   | `GradumPortConverter`  | `PID-PORT` from JVM runtime bean and system property |

Both are in the `gradum.logging` package.
