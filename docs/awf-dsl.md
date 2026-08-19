# Gradum AWF (Agent Workflow File) — DSL Specification

> Version: **0.1 draft** · Status: planned in sync with `docs/roadmap.md` · Delivery pace: slow

AWF is Gradum's workflow DSL. It composes the existing Skills (`read_file` / `grep` / `edit_file` / ...), LLM calls,
event streams, and recordings into a single **declarative XML** document that the Gradum runtime can parse, schedule,
execute, and record.

**Root element**: `<awf>`
**File extensions**: `.awf` / `.awf.xml` (both accepted) **Encoding**: UTF-8 **Document type**: XML 1.0

---

## 1. Document Structure

```xml
<?xml version="1.0" encoding="UTF-8"?>
<awf name="..." model="...">
  <env>
    <var name="..." value="..."/>
  </env>
  <step id="..." run="..." path="...">
    <out name="..." from="..."/>
  </step>
  <step id="..." model="..." needs="..." retry="..." on-error="...">
    <prompt>...</prompt>
    <tool name="..." path="..."/>
    <out name="..." from="..."/>
  </step>
  <trigger cron="..." timeZone="..." enabled="false"/>
</awf>
```

---

## 2. Root Element `<awf>`

| Attribute | Required | Description                                                                                                                |
|-----------|----------|----------------------------------------------------------------------------------------------------------------------------|
| `name`    | ✓       | Workflow identifier. Written into the recording JSON's `workflow` field; must be globally unique (within the same project) |
| `model`   | ✗       | Default model. Model steps without an explicit `model=` inherit this value                                                 |

---

## 3. The `<env>` Block

Declares **read-only** constants, available in `${env.X}` interpolation.

```xml
<env>
  <var name="repo" value="gradum"/>
  <var name="branch" value="main"/>
</env>
```

- `name` required. The identifier.
- `value` required. The constant value (**string literal**; v1 does not support referencing other variables).

---

## 4. `<step>` — The Workflow Node

### 4.1 Tool Step

```xml
<step id="scan" run="explore_project" depth="3">
  <out name="tree" from="tree"/>
</step>
```

| Attribute  | Required | Description                                                              |
|------------|----------|--------------------------------------------------------------------------|
| `id`       | ✓       | Globally unique identifier. Used in `${steps.X.outputs.Y}` interpolation |
| `run`      | ✓       | Skill name. Valid values in the table below                              |
| `path`     | ✗       | Used when `run` needs a path                                             |
| `pattern`  | ✗       | Used by grep / glob                                                      |
| `command`  | ✗       | Used by run_cmd                                                          |
| `depth`    | ✗       | Used by explore_project                                                  |
| `needs`    | ✗       | Comma-separated step-id list. Declares dependencies (step 2)             |
| `if`       | ✗       | Expression string. Conditional execution (step 3)                        |
| `retry`    | ✗       | Failure retry count (step 3)                                             |
| `on-error` | ✗       | `fail` (default) / `continue` / `step.<id>` (step 3)                     |
| `model`    | ✗       | Required only for model steps                                            |

#### Valid `run` values

```
read_file  edit_file  save_file  run_cmd
explore_project  grep  glob
to_do  finish_to_do_item
search_web
```

New Skills added in the future **automatically** appear in this list. **Do not invent new tools.**

### 4.2 `<out>` — Output Declaration

```xml
<out name="text" from="content"/>
```

| Attribute | Required | Description                              |
|-----------|----------|------------------------------------------|
| `name`    | ✓       | Referenced as `${steps.<stepId>.<name>}` |
| `from`    | ✓       | Dotted path into the tool result map     |

Tool result shape (from the existing Skills):

```json
{
  "success": true,
  "content": "...",
  "error": { "code": "...", "message": "..." },
  "metadata": { "lines": 42 }
}
```

`from` supports:

- `content` → `result["content"]`
- `error.message` → `result["error"]["message"]`
- `metadata.lines` → `result["metadata"]["lines"]`

### 4.3 Model Step (step 4)

```xml
<step id="review" model="qwen2.5-coder"
      needs="scan,readme"
      retry="2" on-error="continue">
  <prompt>Review this README against the tree: ${scan.tree}</prompt>
  <tool name="read_file" path="REVIEW.md"/>
  <tool name="edit_file" path="REVIEW.md" content="..."/>
  <out name="text" from="final_reply"/>
</step>
```

- A model step = **one full agent turn** (an `Agent.executeTask` call)
- `<prompt>` content is substituted with `${...}` placeholders
- `<tool>` declares the **whitelist of tools the LLM may call within this step**. Without it, the full SkillRegistry is used
- `model` overrides the root `model` attribute

---

## 5. Variable Interpolation

Syntax: `${scope.path}` or `${fn()}`

### 5.1 Scope

| scope                  | Meaning                    | Example              |
|------------------------|----------------------------|----------------------|
| `steps.<id>.<outName>` | Output of an upstream step | `${readme.text}`     |
| `env.<name>`           | Constant from `<env>`      | `${env.repo}`        |
| `input.<name>`         | Trigger input argument     | `${input.prNumber}`  |

### 5.2 Zero-Argument Functions

| Function | Returns               |
|----------|-----------------------|
| `now()`  | Current ISO timestamp |
| `uuid()` | Random UUID           |

### 5.3 Behavior

- **Resolvable** → string substitution
- **Unresolvable** (missing id, undeclared outName, undefined env) → **left verbatim** as `${X}`, plus an
  `interpolation_unresolved` warning event in the recording. **No error.**

---

## 6. Expression Engine (step 3)

**Used only by `if=`.** All other attributes (`path` / `pattern`, etc.) accept string literals only.

### 6.1 Supported

| Category   | Operators                                                      |
|------------|----------------------------------------------------------------|
| Comparison | `==` / `!=` / `>` / `<` / `>=` / `<=`                          |
| String     | `contains` / `startsWith` / `endsWith` / `empty` / `length`    |
| Boolean    | `&&` / `\|\|` / `!`                                            |
| Literals   | string `"..."` / number `42` / boolean `true` `false` / `null` |
| Variables  | `${...}`, same syntax as interpolation                         |

### 6.2 Unsupported

- String concatenation (`+`)
- Arithmetic (`+` / `-` / `*` / `/`)
- List / map literals
- User-defined functions
- String interpolation (deferred to step 5)

### 6.3 Examples

```xml
<step id="deploy" if='${tests.failed} == 0 && ${env.dryRun} == "false"'
      run="run_cmd" command="deploy.sh"/>

<step id="warn" if='${readme.text.contains("TODO")}'
      run="run_cmd" command="echo TODO exists"/>
```

---

## 7. `<trigger>` (step 5)

```xml
<trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
<trigger onFileChange="src/**/*.kt" debounceMs="500" enabled="false"/>
<trigger onCommand="gradum.runWorkflow(name=nightly-codereview)" enabled="false"/>
```

**3 trigger types, all defaulting to `enabled="false"`.** Only an explicit `enabled="true"` mounts them.

### 7.1 `cron`

| Attribute  | Required | Description                                |
|------------|----------|--------------------------------------------|
| `cron`     | ✓       | 5-field cron: `min hour day month weekday` |
| `timeZone` | ✗       | IANA time zone, e.g. `Asia/Shanghai`       |
| `enabled`  | ✗       | `true` / `false`, default `false`          |

### 7.2 `onFileChange`

| Attribute      | Required | Description                           |
|----------------|----------|---------------------------------------|
| `onFileChange` | ✓       | Glob pattern, relative to projectRoot |
| `debounceMs`   | ✗       | Debounce delay in milliseconds        |
| `enabled`      | ✗       | Default `false`                       |

### 7.3 `onCommand`

| Attribute   | Required | Description     |
|-------------|----------|-----------------|
| `onCommand` | ✓       | IDE command ID  |
| `enabled`   | ✗       | Default `false` |

### 7.4 Unsupported

- Webhooks (deferred to v2)
- Cross-workflow triggers
- Matrix concurrency

---

## 8. Error Handling

| `on-error` value | Behavior                                                               |
|------------------|------------------------------------------------------------------------|
| `fail` (default) | Immediately terminates the workflow, marks `WorkflowRun.Status.Failed` |
| `continue`       | Skips this step and every step that `needs` it, keeps going            |
| `step.<id>`      | Jumps to that step and continues                                       |

`retry="N"` takes effect **before** `on-error`: retry N times first, and only the **final failure** reaches `on-error`.

---

## 9. Correspondence with Existing Skill Parameters

A tool step's non-control attributes (everything except `id` / `run` / `needs` / `if` / `retry` / `on-error` /
`model`) **map directly to the Skill's input parameters**. This means adding a new Skill **requires no AWF parser
changes**.

Example:

```xml
<!-- run_cmd skill accepts command, reason, detached, ... -->
<step id="branch" run="run_cmd" command="git branch --show-current" reason="curiosity"/>
```

Parameter semantics follow the Skill documentation; AWF does not redefine them.

---

## 10. Minimal Complete Example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<awf name="nightly-codereview" model="qwen2.5-coder">
  <env>
    <var name="repo" value="gradum"/>
  </env>

  <step id="scan" run="explore_project" depth="3">
    <out name="tree" from="tree"/>
  </step>

  <step id="readme" run="read_file" path="README.md">
    <out name="text" from="content"/>
  </step>

  <step id="review" model="qwen2.5-coder" needs="scan,readme">
    <prompt>Review this README against the project tree.

Project tree:
${scan.tree}

README:
${readme.text}
    </prompt>
    <tool name="edit_file" path="REVIEW.md"/>
    <out name="text" from="final_reply"/>
  </step>

  <step id="commit" needs="review" run="run_cmd" command="git add REVIEW.md"/>

  <trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
</awf>
```
