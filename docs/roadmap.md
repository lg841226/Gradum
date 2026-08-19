# Gradum Roadmap — Agent Workflow (AWF) DSL

> Status: **draft v0.1** · Decision record: 2026-08-16 · 5-step plan · Delivery pace: slow

This document records the next phase of Gradum's evolution: **composing the existing Skills / LLM calls / event
streams / recordings into schedulable workflows with a single declarative XML document**. This is not "GitHub Actions,
rebuilt"
— it is local-first, AI-first, with a built-in permission model and zero ops, which is what differentiates it.

**Major decisions (2026-08-16)**:

1. The `<tls>` root element is **fully deprecated** (no alias kept), **not backward compatible**. The project has not
   shipped v1; only 13 files change, and the full switch takes 3-4 hours.
2. The new root element is `<awf>` (Agent Workflow File) — 5 letters, matching the `.awf` / `.awf.xml` file extensions
   and distinguishing it from `.tls.xml`.
3. The 5-step roadmap is designed on the principle of **each step is its own PR, independently shippable** — users get
   value from step 1 today (far less repetitive XML), and the "runs automatically" semantics only appear at step 5.

---

## 1. The 5-Step Roadmap

| Step  | Name                                          | What the user can do with it                                                                | Estimate   |
|-------|-----------------------------------------------|---------------------------------------------------------------------------------------------|------------|
| **1** | **Step identity + outputs + interpolation**   | Give steps an `id`, name tool-result fields as outputs, downstream `${id.field}` reads them | ~200 lines |
| **2** | **DAG scheduling (`needs=`)**                 | Run independent steps in parallel, serialize on dependencies                                | ~250 lines |
| **3** | **Conditionals (`if=`) + retries (`retry=`)** | Skip via `if=${tests.failed}==0`, retry failures 3 times                                    | ~200 lines |
| **4** | **Model step**                                | A `model="..."` node runs an agent loop, using upstream outputs as prompts and tool args    | ~300 lines |
| **5** | **Triggers (cron / file watch / command)**    | `cron=` / `onFileChange=` / `onCommand=`, all defaulting to `enabled="false"`               | ~250 lines |

**Each step**:

- Its own PR
- Its own tests
- Its own demo scenario
- Its own CHANGELOG entry

**Why this order**:

- 1 is the foundation. Without id/outputs nothing downstream makes sense
- 2 is where "actually a workflow" semantics begin (topology), but step 1 already benefits users
- 3 is robustness. It must come before the model step — model steps fail often
- 4 is the differentiator. It must wait until 1-3 are stable
- 5 closes the "product" semantics. Left for last (and it is the most complex)

---

## 2. DSL Design

### 2.1 File Names / Root Element

| Old                        | New                                     |
|----------------------------|-----------------------------------------|
| `.tls.xml`                 | `.awf` / `.awf.xml`                     |
| `<tls>`                    | `<awf>`                                 |
| `<t nam="..." pth="..."/>` | `<step id="..." run="..." path="..."/>` |

> **The 3-letter aliases (`nam` / `pth`, etc.) are not carried over** — v1 is a clean cut.

### 2.2 Root Element

```xml
<awf name="nightly-codereview" model="qwen2.5-coder">
  <env>
    <var name="repo" value="gradum"/>
  </env>
  <step id="scan" run="explore_project" depth="3">
    <out name="tree" from="tree"/>
  </step>
  <!-- ... -->
  <trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
</awf>
```

- `name` **required**. The workflow identifier, written into the recording JSON's `workflow` field
- `model` **optional**. The default model for steps that do **not** declare `model=`
- Three things live at the top level: `<step>`s, `<trigger>`s (step 5), and the `<env>` block (constants)

### 2.3 Step — The Core Node

#### 2.3.1 Tool Step

```xml
<step id="readme" run="read_file" path="README.md">
  <out name="text" from="content"/>
</step>
```

- `id` required and **globally unique**
- `run` required. Its value is a Skill name (`read_file` / `grep` / `glob` / `edit_file` / `save_file` / `run_cmd` /
  `to_do` / `finish_to_do_item` / `explore_project`), **no invented tools**
- The other attributes are the Skill's parameters. They **reuse all of `ParsedToolCall`'s arguments** (`path` /
  `pattern` / `command`, etc.); at step 1 the **old demos run 100% unchanged** — backward compatibility lives in the
  Skill parameter layer
- `<out name="X" from="Y"/>` explicitly declares which fields are outputs. `Y` is a dotted path into the tool result map
  (`content` / `error.message` / `metadata.lines`), **no new syntax invented**

#### 2.3.2 Model Step (step 4)

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

- A model step = **one full agent turn** (an `Agent.executeTask` call). **No nested multi-turn state machine** — v1
  keeps complexity low
- `<prompt>` content is substituted with `${...}` placeholders
- `<tool>` declares the **whitelist of tools the LLM can call within this step**. Without it, the full SkillRegistry is
  used
- `model` overrides the root `model` attribute

#### 2.3.3 Composite Steps (inline groups, deferred to v2)

Not in v1. Rationale: nested groups + namespacing double the complexity; get the flat form working first.

### 2.4 Variable Interpolation (**string substitution only**)

Syntax: `${scope.path}`

| scope                  | Meaning                    | Example             |
|------------------------|----------------------------|---------------------|
| `steps.<id>.<outName>` | Output of an upstream step | `${readme.text}`    |
| `env.<name>`           | Constant from `<env>`      | `${env.repo}`       |
| `input.<name>`         | Input argument at trigger  | `${input.prNumber}` |
| `now()`                | Current ISO timestamp      | `${now()}`          |
| `uuid()`               | Generates a UUID           | `${uuid()}`         |

**Only supports**: dotted-path lookups + the 2 zero-argument functions above + a minimal subset of string concatenation.
**v1 does not do arithmetic, booleans, lists, map literals, or user functions.**

**Unresolvable value** → `${X}` **left verbatim** + an `interpolation_unresolved` warning event in the recording. **No
error** — visible in debugging instead of aborting.

### 2.5 Expression Engine (**step 3**)

v1 supports:

| Category   | Operators                                                      |
|------------|----------------------------------------------------------------|
| Comparison | `==` / `!=` / `>` / `<` / `>=` / `<=`                          |
| String     | `contains` / `startsWith` / `endsWith` / `empty` / `length`    |
| Boolean    | `&&` / `\|\|` / `!`                                            |
| Literals   | string `"..."` / number `42` / boolean `true` `false` / `null` |

**`+` string concatenation is not supported.** To concatenate: do it in an upstream step, or write two `if`
branches.

### 2.6 Triggers (**step 5 only**)

```xml
<trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
<trigger onFileChange="src/**/*.kt" debounceMs="500" enabled="false"/>
<trigger onCommand="gradum.runWorkflow(name=nightly-codereview)" enabled="false"/>
```

**3 triggers, not mounted at IDE startup; the user must explicitly set `enabled="true"`**:

- Prevents a workflow from silently running after an IDE restart (accidental triggers)
- A trigger is "proactive behavior" — after writing it, the user must explicitly say "I want this to run automatically"

**watchService resources**: registering `onFileChange` checks that **this project** has no other watcher registered, to
avoid duplicates.

**No webhooks** (needs a bound port, firewall, signing — low value; revisit later if needed).

### 2.7 Error Handling

| Field                  | Behavior                                                               | Default      |
|------------------------|------------------------------------------------------------------------|--------------|
| `on-error="fail"`      | Immediately terminates the workflow, marks `WorkflowRun.Status.Failed` | ✓ (default) |
| `on-error="continue"`  | Skips this step and every step that `needs` it, continues              |              |
| `on-error="step.<id>"` | Jumps to that step and continues                                       |              |

`retry="N"` is independent and stacks **before** `on-error`: retry N times first, and only the **final failure**
reaches `on-error`.

---

## 3. What We Are Not Doing (Explicit Boundaries)

- Remote runners / cross-machine scheduling
- Secrets storage (the IDE's system credentials already handle it)
- Matrix strategies (too costly; 1:1 first)
- Caching (the `actions/cache` key/restoration style — not yet)
- Cross-workflow artifact passing (only in-workflow outputs)
- Action marketplace (the ecosystem is left to the community)
- Composite steps / nested groups (deferred to v2)
- Multi-turn LLM decisions inside a model step (deferred to v2)
- Webhook triggers (deferred to v2)

**This boundary** keeps the entire DSL scoped to "a user reads the syntax in 30 minutes and can write in 1 hour".

---

## 4. Integration Points with the Existing System (the Exact Seam for Step 1)

Step 1 is what "gets the groundwork in today", down to the code level:

**13 files change**:

| File                                                                       | Change                                                                                                                    |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `playground/skills-demo.tls.xml`                                           | Rename to `skills-demo.awf.xml`; `<tls>` → `<awf>`; `<t nam=.../>` → `<step id=... run=.../>`                             |
| `src/main/kotlin/gradum/debug/ToolCallScenarioParser.kt`                   | Drop the `<tls>` branch; accept `<awf>` only; **error** on root-element mismatch (`Use <awf> (see docs/roadmap.md §2.1)`) |
| `src/main/kotlin/gradum/agent/Agent.kt`                                    | `playToolCallScenario` → `playWorkflow`; `playback_start/end` → `workflow_start/end`                                      |
| `src/main/kotlin/gradum/server/Routes.kt`                                  | `toolCallXml` field → `workflowXml`                                                                                       |
| `plugin/src/main/kotlin/gradum/idea/ui/GradumCallbacks.kt`                 | `.tls` extension registration → `.awf`                                                                                    |
| `plugin/src/main/kotlin/gradum/idea/chat/state/GradumChatSession.kt`       | `toolCallXml` → `workflowXml`                                                                                             |
| `plugin/src/main/kotlin/gradum/idea/chat/api/GradumApiClient.kt`           | Same as above                                                                                                             |
| `plugin/src/main/kotlin/gradum/idea/chat/model/MarkdownTlsScenario.kt`     | `<tls>` block → `<awf>` block; class `MarkdownTlsScenario` → `MarkdownAwfScenario`                                        |
| `src/test/kotlin/gradum/debug/ToolCallScenarioParserTest.kt`               | Update the root element and step syntax accordingly                                                                       |
| `src/test/kotlin/gradum/server/DebugPlaybackEndToEndTest.kt`               | Same as above                                                                                                             |
| `plugin/src/test/kotlin/gradum/idea/chat/model/MarkdownTlsScenarioTest.kt` | Same as above                                                                                                             |
| `src/main/java/gradum/ErrorCode.java`                                      | `INVALID_SCENARIO_XML` → `INVALID_WORKFLOW_XML`                                                                           |
| `docs/ARCHITECTURE.md`                                                     | Documentation update (5 references)                                                                                       |

**Net code change**: **approximately -30 lines** (removing the old parser branch subtracts more than the additions)

**Actual hands-on effort**: 3-4 hours

**Risk**: the parser's error message **points users to `docs/roadmap.md` §2.1**, not back to `<tls>`.

---

## 5. Step-1 Code-Level Prefabs (**not touched today, recorded only**)

4 prefabs in step 1 make steps 2-5 follow naturally:

1. **Add an `id` field to `ParsedToolCall`** (30 lines) — makes `${steps.X.outputs.Y}` writable in the future
2. **`${...}` string interpolation** (80 lines + 1 new file `InterpolationEngine.kt`) — the foundation for step-2
   variable passing
3. **Change the recording JSON to the run format** (20 lines) — add `runId` / `workflow` at the top level,
   `calls` → `steps`, each step gets `id` / `startedAt` / `finishedAt` / `outputs`
4. **Add `step_start` / `step_end` boundaries to the event stream** (30 lines) — the basis for future per-step
   collapsing in the UI

**These 4 total 160 lines + tests.** **Done today, steps 2-5 never have to revisit the parser / recorder / events.**

---

## 6. Documentation Update Plan

| Document                         | Change                                                                                                                            |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `docs/roadmap.md`                | This document                                                                                                                     |
| `docs/awf-dsl.md`                | New; the DSL syntax spec (`docs/ARCHITECTURE.md` links to it)                                                                     |
| `docs/ARCHITECTURE.md`           | Change "ToolCallScenarioParser (tls.xml → scripted tool calls)" to "AWF Parser (.awf.xml → workflow run)", link `docs/awf-dsl.md` |
| `README.md`                      | "Quick Start" gets a `.awf` example section                                                                                       |
| `CHANGELOG.md`                   | New; v0.9.3 entry: "BREAKING: `<tls>` → `<awf>`, see `docs/roadmap.md` §2.1"                                                      |
| `playground/skills-demo.awf.xml` | Demo rename + syntax change                                                                                                       |
| `playground/simulate.awf.md`     | Same as above                                                                                                                     |

---

## 7. Versions and Pace

- **v0.9.2** (current): `<tls>` in use, no workflow scheduling
- **v0.9.3** (step 1 ships): `<tls>` removed entirely, `<awf>` takes over
- **v0.9.4** (step 2): DAG scheduling `needs=`
- **v0.9.5** (step 3): `if=` / `retry=`
- **v1.0** (step 4): Model step (the differentiator)
- **v1.1** (step 5): the trigger trio

**Do not ship v1.0 before v0.9.3.** The `awf` spelling / field names / event names are **frozen for 6 months after
v0.9.3 ships**; no breaking changes accepted.
