# External Skill Toolchain: `skill-api` Proposal

## 1. Problem

External skills are Kotlin files dropped into `~/.gradum/skills/`. They are
compiled at runtime by the server ([`ExternalSkillCompiler`] and loaded through
[`ExternalSkillClassLoader`]), so the server classpath is what makes a build
succeed. But the developer who *writes* these skill files is editing inside
their own IDE, which has no knowledge of `gradum.*` symbols. Result:

- No code completion, no find-usages, no jump-to-definition for `Skill`,
  `SkillResult`, or the schema DSL.
- No type checking at edit time; errors surface only after the server
  compiles the file.

The root cause is architectural: IDE completion is driven by the IDE's
library/module model, not by the server process's classpath. Tweaking the
server classpath can never help the IDE.

## 2. Rejected alternatives

### 2.1 Let the plugin adopt `~/.gradum/skills/` as a source-root module (rejected)

The IntelliJ Gradum plugin is a **pure remote client** ([`plugin/build.gradle.kts`]
declares no `gradum` core dependency; it drives the server over `/events`).
It does not bundle the Kotlin skill core, so there is nothing local to mount as
a library. Bundling the core into the plugin would balloon it and force strict
version-locking between plugin release and server runtime. Worse, it would
shift skill compilation away from the server's pipeline and therefore **invalidate the hot-reload / directory-watch
machinery** built around the
server-side reconcile loop. Rejected on both delivery cost and architectural
conflict.

### 2.2 Server writes a skeleton jar into `~/.gradum/` (downgraded)

Semi-automatic, but "export only the API surface, not the whole fat jar" is
hard to do cleanly on the server side, and manual add-to-classpath is hostile
to developers. Not a durable solution.

## 3. Chosen direction: a standalone `skill-api` artifact + `gradum init`

The approach:

1. Extract a **pure, server-core-free API surface** into a lightweight
   standalone artifact `gradum-skill-api.jar`.
2. Built into the `.app` bundle at package time.
3. `gradum init` generates a skill scaffold that already carries the jar, so a
   developer gets completion, type checking, and a build with zero network and
   zero manual dependency configuration.
4. The skill's build produces a jar that the developer drops into
   `~/.gradum/skills/`; the existing server reconcile pipeline loads it.

This **adds** a developer-facing editing layer on top of the existing runtime
without replacing it. Hot reload, directory watching, fingerprinting, and
reconcile all keep working unchanged.

## 4. `skill-api` surface boundary

### 4.1 Must ship in `skill-api`

Pure, dependency-free types the developer needs to write a skill:

- `SkillResult` (`Success` / `Failure`) plus `makeSuccess` / `makeFailure`
  factories. Drop the `ErrorCode` overload; accept `code: String`.
- `Skill` abstract class — **slim edition**: `alias`, `skillName`,
  `description`, `allowedToolModes` / `allows`, `execute`, `getSchema`,
  `schemaProperties`, `simpleDescription`, `buildFunctionSchema`. The history
  compaction family (`compactHistory`, `prepareHistoryResult`,
  `recordAndCompactHistory`, `historyKeepCount`, `historyVolatileKeys`,
  `resetHistoryCount`, `callCount`) is **excluded** — it is runtime behavior,
  not author-facing API.
- Schema DSL: `ParameterLevel`, `SchemaBuilder`, `SkillParameter`,
  `string` / `integer` / `boolean` / `stringArray` / `objectArray`,
  `IntConstraints`, `ObjectItemBuilder`.
- Result / interaction DSL: `SkillResponseBuilder`, `AskBuilder`,
  `XmlBuilder`, `InteractionTypes`.
- Enums with no server dependencies: `ToolMode`, `ErrorCode`.
- `SkillContext` — **slim to read-only basics**: `toolMode`, `projectRoot`,
  `modelName`, `provider`, `isSimpleModel`. Runtime fields (`agentConfiguration`,
  `Agent`, `MaterializedMcpTools`, `scope`, child-session callbacks) stay out.

### 4.2 Must NOT ship in `skill-api`

Runtime-only machinery the developer should not be exposed to:

- `SkillRegistry`, `SkillStore` — registration is server runtime state.
- `ExternalSkill*` — compiler, class loader, directory watcher, reconcile.
- `builtin/*`, `mcp/*`, `agent/*` — internal skills and infrastructure.
- The history-compaction members listed above.

### 4.3 Why the slim `Skill` does not break built-in skills

Server and `skill-api` carry **separate** copies of `Skill`. Server-side
`Skill` keeps `compactHistory` and friends untouched, so built-in skills that
override them keep compiling as-is. Developers author against the slim copy;
at load time the class loader bridges the two. No hand-maintained "mark as
non-API" shim is needed on the server class.

The one decision left to the developer-facing surface: expose only the most
basic `execute` + schema (cleanest), versus also exposing the history
compaction hooks. This proposal defaults to the slimmest surface; revisit only
if an external skill genuinely needs history control.

## 5. Landing strategy (preferred: lightest touch)

Choose between:

- **A. Physical split (cleaner, wider blast radius):** a new `skill-api`
  Gradle module; server declares `api(project(":skill-api"))`. Touches a batch
  of server imports plus the `SkillContext` slimming.
- **B. Directory-level split (minimal change):** keep the slim surface in a
  clearly named package inside the server (e.g. `gradum.skill.api.*`); the
  `skill-api` module is assembled by selecting only those files plus the slim
  `SkillContext`. Reduces changes to existing classes.

This proposal defaults to **A** (a real module is the durable shape for a
published artifact), accepting the wider change as the cost of a clean boundary.

## 6. `gradum init` scaffold

A JVM `gradum` CLI gains an `init` sub-command. In the current directory it
generates:

```
my-skill/
├─ build.gradle.kts            # implementation(files("libs/gradum-skill-api.jar"))
├─ src/main/kotlin/HelloSkill.kt
└─ libs/gradum-skill-api.jar   # copied out of the .app bundle
```

`./gradlew build` → `build/libs/*.jar` → drop into `~/.gradum/skills/` →
server `findClassesInJar` loads it.

## 7. Bundling the jar into `.app`

`serverPackage` ([`build.gradle.kts:337-393`]) currently stages only the fat
jar and the minimal runtime. Change: add a task producing `gradum-skill-api.jar`
and place a copy in the jpackage `--input` staging dir so it lands under
`Contents/Resources/`. At runtime, `gradum init` locates it relative to the
app bundle.

## 8. Server load path

The scanner already supports `findClassesInJar`; extend `reconcile` to scan
`*.jar` under `~/.gradum/skills/` in addition to `*.kt`. No change to the
watch/fingerprint logic.

## 9. Open items

- Whether the developer-facing `Skill` exposes the history-compaction hooks (default: no).
- Landing strategy A vs B (default: A).
- Exact location of `gradum-skill-api.jar` inside the app bundle.
