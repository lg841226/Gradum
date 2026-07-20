# Gradum Plugin Infrastructure Pitfall Record (Jewel / Compose / Classloader)

> A record of **7 runtime errors** triggered consecutively in a single day (2026-07-20) while integrating
> IntelliJ Platform 2026.2 + Jewel + Compose. Each section includes: symptoms → root cause → fix → verification.
> The goal is to prevent the next person (and your future self within six months) from **spending 7 hours** on this.

---

## 0. Project Baseline

| Config                          | Value                                     | Notes                                                     |
|---------------------------------|-------------------------------------------|-----------------------------------------------------------|
| IDE                             | IntelliJ IDEA Ultimate 2026.2             | `Build #IU-262.8665.258`, July 15 2026                    |
| Kotlin                          | 2.3.0 (plugin) / 2.4.20-dev (IDE bundled) | `plugin/build.gradle.kts:213`                             |
| Jewel                           | IDE 2026.2 bundled 0.38                   | `Contents/lib/intellij.platform.jewel.*.jar`              |
| Compose                         | CMP 1.11.0                                | IDE bundled                                               |
| Skiko                           | 0.9.x                                     | IDE bundled                                               |
| IntelliJ Platform Gradle Plugin | 2.x                                       | `plugin/build.gradle.kts:6-7`                             |
| Gradle                          | 9.5.1                                     |                                                           |
| JVM Target                      | 25                                        | Supported by Kotlin 2.3.0; not supported by detekt 1.23.7 |

**Project directory**: `/Users/gwy/Documents/Code_Project/Gradum`
**Plugin submodule**: `/Users/gwy/Documents/Code_Project/Gradum/plugin`
**`plugin/libs/`**: 15 IDE jar copies (manually copied from IDE `Contents/lib/`)

---

## 1. StackOverflowError — Compose / Jewel Duplicate Classloader Conflict

### Symptoms

```
java.lang.StackOverflowError
    at androidx.compose.runtime.AbstractComposeApplier...
    (hundreds of AbstractComposeApplier layers calling each other)
```

Or a bunch of `ClassCircularityError`, `IncompatibleClassChangeError`, appearing the moment you first open the Gradum
tool window.

### Root Cause

`io.github.huarangmeng:latex-{base,parser,renderer}:1.4.7` are Compose Multiplatform packages. Their `pom.xml` declares
`org.jetbrains.compose.runtime`, `org.jetbrains.compose.foundation`, `org.jetbrains.compose.ui`,
`org.jetbrains.compose.material3`, `org.jetbrains.compose.animation`, `org.jetbrains.compose.components`,
`org.jetbrains.compose.desktop` as dependencies, **which automatically pulls copies from Maven**.

If `plugin/libs/` also contains a copy of the same-version IDE Compose jars
(`intellij.libraries.compose.foundation.desktop-1.11.0.jar`, etc.), then plugin-lib ends up with **two copies** of
Compose. Compose internally references itself heavily through `init {}` and `companion object`, resulting in a
StackOverflow chain.

### Fix

1. Exclude all Compose / Skiko transitive dependencies from the three LaTeX artifacts, forcing LaTeX to link against the
   local copy:

   ```kotlin
   implementation("io.github.huarangmeng:latex-base:1.4.7") {
     exclude(group = "org.jetbrains.compose.runtime")
     exclude(group = "org.jetbrains.compose.foundation")
     exclude(group = "org.jetbrains.compose.ui")
     exclude(group = "org.jetbrains.compose.material3")
     exclude(group = "org.jetbrains.compose.animation")
     exclude(group = "org.jetbrains.compose.components")
     exclude(group = "org.jetbrains.compose.desktop")
     exclude(group = "org.jetbrains.skiko")
   }
   // Same 9 lines for latex-parser and latex-renderer
   ```

2. **Keep** the Compose copy in `plugin/libs/` (see Section 7 for the decision record).

### Verification

`./gradlew :plugin:buildPlugin` succeeds; `ls .intellijPlatform/sandbox/plugin/IU-2026.2/plugins/plugin/lib/` shows only
**one set** of Compose jars (the plugin's own 10); `./gradlew :plugin:test` — all 293 tests pass.

---

## 2. LinkageError: loader constraint violation on kotlinx.coroutines.CoroutineScope

### Symptoms

```
com.intellij.diagnostic.PluginException: Cannot load class kotlinx.coroutines.CoroutineScope (
  error: loader constraint violation: loader com.intellij.ide.plugins.cl.PluginClassLoader @5bc21546
  wants to load interface kotlinx.coroutines.CoroutineScope. A different interface with the same
  name was previously loaded by com.intellij.util.lang.PathClassLoader @180bc464.
  (kotlinx.coroutines.CoroutineScope is in unnamed module of loader
  com.intellij.util.lang.PathClassLoader @180bc464),
  classLoader=PluginClassLoader(plugin=PluginMainDescriptor(name=Gradum, id=com.gradum.idea,
  descriptorPath=...))
```

Plugin crashes on IDE startup — can't even reach the tool window.

### Root Cause

In addition to Compose, the three LaTeX artifacts also **transitively pull**
`org.jetbrains.kotlinx:kotlinx-coroutines-core` (Compose's Flow / StateFlow implementation depends on it). This package
ends up in plugin/lib alongside Compose:

```
plugin/lib/kotlinx-coroutines-core-jvm-1.11.0.jar     ← the culprit
plugin/lib/kotlinx-coroutines-swing-1.11.0.jar
```

**Meanwhile,** IDE's own `Contents/lib/intellij.libraries.kotlinx.coroutines.core.jar` is also loaded by
`PathClassLoader`. Two classloaders each hold their own copy of the `CoroutineScope` **interface**.

JVM rule: when the same interface is loaded by different classloaders during inheritance / implementation / casting, it
throws `LinkageError: loader constraint violation`. **Only interfaces trigger this**; regular class double-loading
doesn't. That's why `Flow`, `Dispatchers` etc. under `kotlinx.coroutines.*` are fine, but `CoroutineScope` always
crashes.

> Key insight: `kotlinx.serialization.json.Json` won't trigger this error (class, not interface).
> `kotlinx.coroutines.CoroutineScope` always will (interface).

### Fix

Two-layer fix:

1. **Block LaTeX from pulling kotlinx**: add to LaTeX artifact excludes:
   ```kotlin
   exclude(group = "org.jetbrains.kotlinx")          // covers coroutines / swing / etc.
   exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
   ```
2. **Still need kotlinx-coroutines API at compile time** (47 imports), use `compileOnly` to reference IDE's bundled
   copy:
   ```kotlin
   compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
   ```
   `compileOnly` **won't** put the jar into plugin/lib, but the Kotlin compiler can resolve the API. At runtime,
   PluginClassLoader delegates to PathClassLoader to find IDE's copy → **single copy across the JVM** → no conflict.

### Verification

- `plugin/lib` no longer has any `kotlinx-coroutines-*` jars.
- `./gradlew :plugin:compileKotlin` passes (API in compileOnly).
- `./gradlew :plugin:buildPlugin` passes.
- Restart IDE → tool window opens.

---

## 3. NoClassDefFoundError: org/jetbrains/jewel/markdown/rendering/InlinesStyling

### Symptoms

```
java.lang.NoClassDefFoundError: org/jetbrains/jewel/markdown/rendering/InlinesStyling
    at gradum.idea.chat.ui.markdown.StylingKt.gradumInlinesStyling(Styling.kt:130)
    at gradum.idea.chat.ui.markdown.StylingKt.rememberGradumMarkdownStyling(Styling.kt:199)
    at gradum.idea.GradumToolWindowFactory.createToolWindowContent$lambda$0$0(...:59)
    at androidx.compose.runtime.internal.ComposableLambdaImpl.invoke(...:122)
    at org.jetbrains.jewel.bridge.theme.SwingBridgeThemeKt.SwingBridgeTheme$lambda$1$3(...:62)
```

Open tool window → first chat render → crash.

### Root Cause (Critical, Easy to Get Wrong)

IDE 2026.2 places Jewel / Compose / Skiko / kotlinx all in the **`jetbrains` namespace**:

- `intellij.platform.jewel.markdown.core` (namespace=`jetbrains`)
- `intellij.platform.jewel.ui` (namespace=`jetbrains`)
- `intellij.libraries.skiko` (namespace=`jetbrains`)
- `intellij.libraries.kotlinx.coroutines.core` (namespace=`jetbrains`)

But `plugin.xml`'s `<depends>` element only accepts two types of IDs:

1. Module IDs under `com.intellij.modules.*` namespace (IDE platform-layer modules)
2. Actual plugin IDs registered in the IDE plugin registry

`<depends>intellij.platform.jewel.markdown.core</depends>` and similar **JetBrains namespace** IDs are all treated as
plugin IDs by the IDE → not found → IDE refuses to load the plugin.

However! `bundledModule(...)` is **only** a **compile-time** mechanism of the IntelliJ Platform Gradle Plugin: it
provides descriptors to the Gradle resolver so `compileKotlin` can find classes. At runtime, IDE module loading **still
only checks** plugin.xml `<depends>`.

> This is why the error in this section is "plugin compiles fine, but classes not found at runtime."

### Fix

Go back to the **original shadow approach** — manually copy IDE jars to `plugin/libs/`, use `implementation(files(...))`
to put these classes into plugin/lib so PluginClassLoader can access them directly:

```kotlin
// Copy these jars from /Users/gwy/Applications/IntelliJ IDEA.app/Contents/lib/
implementation(files("libs/intellij.libraries.compose.foundation.desktop.jar"))
implementation(files("libs/intellij.libraries.compose.runtime.desktop.jar"))
implementation(files("libs/intellij.libraries.skiko.jar"))
implementation(files("libs/intellij.platform.compose.jar"))
implementation(files("libs/intellij.platform.compose.markdown.jar"))
implementation(files("libs/intellij.platform.jewel.foundation.jar"))
implementation(files("libs/intellij.platform.jewel.ui.jar"))
implementation(files("libs/intellij.platform.jewel.ideLafBridge.jar"))
implementation(files("libs/intellij.platform.jewel.markdown.core.jar"))
implementation(files("libs/intellij.platform.jewel.markdown.ideLafBridgeStyling.jar"))
```

Keep `plugin.xml` `<depends>` to the **minimum two**:

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.compose</depends>
```

> **Do not** add `<depends>` for any `intellij.platform.*` / `intellij.libraries.*`.
> **Do not** use `bundledModule` to replace `implementation(files(...))` — the former only works at compile time; IDE
> can't find classes at runtime.

### Verification

`./gradlew :plugin:buildPlugin` succeeds; `ls .intellijPlatform/sandbox/plugin/IU-2026.2/plugins/plugin/lib/` shows 10
`intellij.platform.jewel.*` / `intellij.libraries.*.jar` files; restart IDE → tool window opens.

---

## 4. PluginException: requires plugin 'intellij.libraries.skiko' to be installed

### Symptoms

```
Plugin 'Gradum' requires plugin 'intellij.libraries.skiko' to be installed
```

(In fact, **any** `intellij.libraries.*` or `intellij.platform.*` added to `<depends>` will trigger this error — skiko
was just the first one found. coroutines has the same issue.)

### Root Cause

Following from Section 3: the IDE treats these IDs as plugin IDs, but there's no plugin named `intellij.libraries.skiko`
in the plugin registry, so it reports "requires plugin to be installed."

### Fix

**Remove all** `intellij.platform.*` / `intellij.libraries.*` entries from plugin.xml `<depends>`. Dependencies are
loaded via `implementation(files("libs/...jar"))` (from the Section 3 fix) through plugin/lib, directly by
PluginClassLoader.

### Verification

Restart IDE → no more "requires plugin" errors.

---

## 5. NoClassDefFoundError: org/jetbrains/jewel/markdown/extensions/autolink/AutolinkProcessorExtension

### Symptoms

```
java.lang.NoClassDefFoundError: org/jetbrains/jewel/markdown/extensions/autolink/AutolinkProcessorExtension
    at gradum.idea.chat.ui.markdown.TableKt.GradumMarkdownProcessor_delegate$lambda$0(Table.kt:73)
    at kotlin.SynchronizedLazyImpl.getValue(LazyJVM.kt:86)
```

Plugin loads, compiles, opens the tool window, but crashes when **the first message renders a Markdown table/link/GFM**.

### Root Cause

The `composedJar` task was misconfigured earlier — it **nested** 5 Jewel Markdown extension jars inside the main jar
(`plugin-0.9.0.jar`) instead of laying them flat:

```kotlin
tasks.named("composedJar", ComposedJarTask::class.java) {
  from(bundledJars)  // ← only nests, doesn't unpack
}
```

Result: `plugin-0.9.0.jar` contains `intellij.platform.jewel.markdown.extensions.autolink.jar` as an embedded file, but
**JVM classloader doesn't dig into nested jars**. The classes were there all along — the classloader just couldn't see
them.

### Fix

Use `implementation(files(...))` directly:

```kotlin
implementation(files("libs/intellij.platform.jewel.markdown.extensions.autolink.jar"))
implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmAlerts.jar"))
implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar"))
implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmTables.jar"))
implementation(files("libs/intellij.platform.jewel.markdown.extensions.images.jar"))
```

**Also delete** the `from(bundledJars)` block from the `composedJar` task (these jars are now handled by
`implementation`).

### Verification

`unzip -l plugin-0.9.0.jar` no longer nests 5 extension jars;
`ls .intellijPlatform/sandbox/plugin/IU-2026.2/plugins/plugin/lib/` shows 5 extension jars **flat at the top level**
(not nested). Restart IDE → Markdown tables / links / GFM all work.

---

## 6. detekt 1.23.7 Does Not Recognize JVM Target 25

### Symptoms

```
* What went wrong:
Execution failed for task ':detekt' (registered by plugin 'io.gitlab.arturbosch.detekt').
> Invalid value (25) passed to -jvm-target, must be one of [1.6, 1.8, 9, 10, 11, 12, 13, 14]
```

`./gradlew :plugin:buildPlugin` fails (detekt is indirectly triggered by `buildPlugin` via
`compileKotlin.dependsOn(detekt)`).

### Root Cause

- Kotlin 2.3.0 compiler supports `JvmTarget.JVM_25` (used by the project, `plugin/build.gradle.kts:213`)
- detekt 1.23.7's built-in `JvmTarget` enum only goes up to 14
- They're incompatible

### Fix (Short-term)

- `./gradlew :plugin:buildPlugin -Pgradum.skipDetektGate=true` — skip the detekt gate (project's built-in escape hatch,
  see `build.gradle.kts:99-138`)

### Fix (Long-term)

Upgrade detekt to >= 1.24.x (stable versions that support JDK 25). Modify `build.gradle.kts:14`,
`plugin/build.gradle.kts:15`, `plugin/build.gradle.kts:226`.

> **Note**: detekt 2.0.0-alpha.x supports JDK 25 but requires Kotlin 2.4.0. The stable 1.23.x line maxes out at JDK 21.

### Verification

`./gradlew :plugin:buildPlugin -Pgradum.skipDetektGate=true` succeeds.

---

## 7. Why We Ultimately Chose Shadow (Not bundledModule) — Decision Record

IDE 2026.2's `Contents/lib/` has 15 jars related to Jewel/Compose/Skiko. JetBrains' official README offers two paths:

| Approach                                                      | Description                                                                            | Fate on 2026.2                                                                          |
|---------------------------------------------------------------|----------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `bundledModule("intellij.platform.jewel.markdown.core")` etc. | Gradle compile classpath gets descriptor; runtime IDE loads via plugin.xml `<depends>` | **Doesn't work** — `<depends>` rejects `jetbrains` namespace IDs                        |
| `plugin/libs/` + `implementation(files(...))`                 | Copy IDE same-name jars into plugin/lib                                                | **Works** — but must exclude all LaTeX Compose transitive deps, otherwise StackOverflow |

With the second path, **plugin/lib's Compose and IDE's own Compose are held by two different classloaders**, but because
`kotlinx.coroutines.CoroutineScope` uses `compileOnly` (not copied), Compose internals don't hold another copy of any
interface that would be compared across classloaders, so no loader constraint violation is triggered. Dual-loading of
Compose's many classes / objects is permitted at the JVM level.

### Copy-Paste Checklist

```bash
# One-time copy of IDE jars
cd /Users/gwy/Documents/Code_Project/Gradum/plugin/libs
for jar in \
  intellij.libraries.compose.foundation.desktop \
  intellij.libraries.compose.runtime.desktop \
  intellij.libraries.skiko \
  intellij.platform.compose \
  intellij.platform.compose.markdown \
  intellij.platform.jewel.foundation \
  intellij.platform.jewel.ideLafBridge \
  intellij.platform.jewel.markdown.core \
  intellij.platform.jewel.markdown.extensions.autolink \
  intellij.platform.jewel.markdown.extensions.gfmAlerts \
  intellij.platform.jewel.markdown.extensions.gfmStrikethrough \
  intellij.platform.jewel.markdown.extensions.gfmTables \
  intellij.platform.jewel.markdown.extensions.images \
  intellij.platform.jewel.markdown.ideLafBridgeStyling \
  intellij.platform.jewel.ui; do
  cp -v "/Users/gwy/Applications/IntelliJ IDEA.app/Contents/lib/${jar}.jar" .
done
```

Key snippet in `plugin/build.gradle.kts`:

```kotlin
dependencies {
  intellijPlatform {
    create("IU", "2026.2")
    bundledPlugin("com.intellij.modules.platform")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }

  // 15 IDE jar copies → plugin/lib
  implementation(files("libs/intellij.libraries.compose.foundation.desktop.jar"))
  // ... 14 more identical implementation(files("libs/...")) lines
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.images.jar"))

  // kotlinx via compileOnly — runtime delegates to IDE PathClassLoader
  compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // LaTeX: exclude all Compose / Skiko / kotlinx / kotlin-stdlib transitive deps
  implementation("io.github.huarangmeng:latex-base:1.4.7") {
    exclude(group = "org.jetbrains.compose.runtime")
    exclude(group = "org.jetbrains.compose.foundation")
    exclude(group = "org.jetbrains.compose.ui")
    exclude(group = "org.jetbrains.compose.material3")
    exclude(group = "org.jetbrains.compose.animation")
    exclude(group = "org.jetbrains.compose.components")
    exclude(group = "org.jetbrains.compose.desktop")
    exclude(group = "org.jetbrains.skiko")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
  }
  // Same 10 lines for latex-parser / latex-renderer
}
```

`plugin/src/main/resources/META-INF/plugin.xml` `<depends>` stays **minimal — just two**:

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.compose</depends>
```

> **Do not** add `<depends>` for any `intellij.platform.*` / `intellij.libraries.*`.
> **Do not** use `bundledModule` to replace `implementation(files(...))`.
> **Do not** use `bundledModule` for `kotlinx-serialization-json` — IDE's version is re-versioned by JetBrains (reports
> 2.3.0), which is incompatible with Kotlin 2.3.0 compiler plugin's strict version check → `PROVIDED_RUNTIME_TOO_LOW`.
> **Do not** use `implementation` for `kotlinx-coroutines-core` — extra CoroutineScope interface in plugin/lib triggers
> `LinkageError`.

---

## 8. One-Line Summary

- **Compile**: `bundledModule` works fine.
- **Runtime**: Use `plugin/libs/` + `implementation(files(...))` to copy IDE same-name jars.
- **kotlinx-\* ecosystem**: Use `compileOnly`, runtime delegates to IDE PathClassLoader.
- **Three LaTeX artifacts**: Exclude all Compose / Skiko / kotlinx / kotlin-stdlib transitive dependencies.
- **plugin.xml `<depends>`**: Keep only `com.intellij.modules.platform` + `com.intellij.modules.compose`.
- **Job.cancel()**: Always pass an explicit `CancellationException("...")`, never `null` and never no-arg.
  Wrapped in try-catch. Defends against both `NoSuchMethodError: cancel$default` and arbitrary coroutines
  version drift. See § 10.
- **runIde caveat**: `./gradlew :plugin:runIde` does NOT hot-reload plugin classes. After code changes,
  stop and restart the task. See § 10.

---

## 9. Timeline (Single Day: 2026-07-20)

| Time      | Event                                                                                                                                                                                                                                                 |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Morning   | Migrated from shadow to bundledModule approach; all 10 Jewel/Compose bundledModule descriptors resolved; all 293 tests passed                                                                                                                         |
| Afternoon | User actually ran the plugin; IDE startup reported `loader constraint violation: CoroutineScope`                                                                                                                                                      |
| 14:00     | Added `bundledModule("intellij.libraries.kotlinx.coroutines.core")` + LaTeX exclude kotlinx → error changed to `PROVIDED_RUNTIME_TOO_LOW` (IDE re-versioned kotlinx-serialization-core) → switched back to `implementation` for kotlinx-serialization |
| 14:30     | CoroutineScope error gone; `NoClassDefFoundError: InlinesStyling` appeared                                                                                                                                                                            |
| 14:45     | Tried adding `<depends>intellij.platform.jewel.markdown.core</depends>` etc. to plugin.xml → compiled, IDE reported `requires plugin 'intellij.libraries.skiko' to be installed`                                                                      |
| 15:00     | Removed all `intellij.libraries.*` `<depends>` for skiko / coroutines → reported `requires plugin 'intellij.platform.compose' to be installed`                                                                                                        |
| 15:15     | User proposed switching paths. Decision: revert to shadow + forced excludes                                                                                                                                                                           |
| 15:30     | Copied 15 jars to plugin/libs/, updated build.gradle.kts, deleted composedJar custom block                                                                                                                                                            |
| 15:45     | `InlinesStyling` error gone; `NoClassDefFoundError: AutolinkProcessorExtension` appeared                                                                                                                                                              |
| 16:00     | Moved 5 markdown extensions to `implementation` (previously via `composedJar.from`, nested inside main jar, invisible to classloader)                                                                                                                 |
| 16:15     | Plugin finally loads! Chat / Markdown / LaTeX all work                                                                                                                                                                                                |
| 16:30     | detekt fails because 1.23.7 doesn't support jvm-target 25 → bypassed with `-Pgradum.skipDetektGate=true`; wrote this document                                                                                                                         |
| 17:00     | User clicked stop button / Gradum tool window icon → `NoSuchMethodError: kotlinx.coroutines.Job.cancel$default(Job, CancellationException, int, Object)`. Initial fix: pass `null` explicitly at the 3 call sites. Final fix (after deeper investigation in § 10): the real root cause is the runIde process serving a **stale plugin class**; the IDE's coroutines rebuild DOES contain the synthetic. Source now uses explicit `CancellationException("...")` + try-catch as defense-in-depth. |

---

## 10. NoSuchMethodError: kotlinx.coroutines.Job.cancel$default

### Symptoms

```
java.lang.NoSuchMethodError: 'void kotlinx.coroutines.Job.cancel$default(kotlinx.coroutines.Job, java.util.concurrent.CancellationException, int, java.lang.Object)'
    at gradum.idea.chat.state.GradumChatSession.stopModelPolling(GradumChatSession.kt:369)
```

Or, in the IDE's UnhandledException UI:

```
Unhandled exception in [androidx.compose.ui.scene.ComposeContainer$DesktopCoroutineExceptionHandler@..., ...]

com.intellij.openapi.diagnostic.UnhandledException: 'void kotlinx.coroutines.Job.cancel$default(...)'
    at com.intellij.openapi.application.impl.ExceptionsKt.processUnhandledException(...)
```

Triggers the **first** time the user clicks either:
- The stop button in the chat
- The Gradum icon in the right sidebar (tool window)

The Gradum icon variant causes a UI freeze because Compose's `DesktopCoroutineExceptionHandler` tries to catch the
`NoSuchMethodError` and dispatches it as if it were a regular coroutine exception, which doesn't actually repair the
broken coroutine state → animation/frame loop deadlocks.

### Root Cause (corrected after a deeper investigation)

> ⚠️ **The first version of this section (v1) claimed that the IDE's coroutines rebuild
> is missing the `cancel$default` synthetic. That was wrong.** The actual situation is more mundane
> but more annoying. The IDE DOES ship `cancel$default`. The error in the user's session was caused
> by the runIde process running a **stale `GradumChatSession.class`** that was compiled before the fix
> was applied. The line number in the stack trace (`GradumChatSession.kt:369`) is the **old** line number —
> in the current source the cancel call lives at line 380 (after the explanatory comment block was added).

What is true:
- IDE 2026.2 ships `kotlinx-coroutines-core:1.10.2-intellij-1` (a JetBrains internal rebuild, confirmed by
  `unzip -p Contents/lib/intellij.libraries.kotlinx.coroutines.core.jar META-INF/kotlinx_coroutines_core.version`).
- The IDE's `Job$DefaultImpls` DOES contain `cancel$default(Job, CancellationException, int, Object)` — verified
  by `javap` on the actual JAR and by a Java reflection probe that ran successfully against it.
- When the plugin is compiled with the no-arg form `pollingJob?.cancel()`, Kotlin 2.3.0 emits a call to that
  `cancel$default` synthetic. Because the synthetic does exist in the IDE's classpath, the call resolves correctly
  — and in the latest build the bytecode does not even call `cancel$default` because we pass an explicit arg.

The real problem is what happens to the error report. `NoSuchMethodError` for a classloader-missed method is a
**hard error**, not a recoverable coroutine exception. When the user reports seeing this error, the most
likely explanations (in order of probability) are:
1. **Stale plugin class in the running dev IDE.** `./gradlew :plugin:runIde` starts a long-lived IDE process.
   It loads the plugin JAR that existed when runIde started. Rebuilding the plugin while runIde is running
   does **not** reload the plugin classes — only restarting the runIde process does. The first time the user
   hits the error is a giveaway: the stack trace points at a source line that no longer contains the cancel call.
2. Stale class from a previous install in the same IDE home (less likely with runIde, but possible if you also
   install the plugin ZIP for testing).
3. Genuine classpath corruption (e.g. an old `kotlinx-coroutines-core-1.11.0.jar` left in `plugin/lib/` from
   an earlier attempt before we moved to `compileOnly`) — verify with
   `unzip -l plugin/build/distributions/plugin-0.9.0.zip | grep -i coroutines`.

### Fix

**Primary fix: just relaunch the runIde process.** Stop the runIde task (close its window or Ctrl-C in the
terminal) and start it again. The new build will be picked up.

**Belt-and-braces hardening applied to the source** (see
`gradum.idea.chat.state.GradumChatSession.stopModelPolling` / `reset` / `stopSession`):

1. Pass an **explicit** `CancellationException("...")` instead of `null`. The Kotlin compiler emits
   `invokeinterface Job.cancel:(Ljava/util/concurrent/CancellationException;)V` directly. No `cancel$default`
   synthetic is referenced, so the call cannot fail with `NoSuchMethodError` for that synthetic — even if
   a future IDE version strips the synthetic from `Job$DefaultImpls`.
2. Wrap the cancel in a `try { ... } catch (t: Throwable) { log.warn(...) }` block. Cancel is a cleanup
   no-op; if it ever does throw, we just log and move on. The UI stays interactive.

```kotlin
// Code that is now in GradumChatSession.kt:
fun stopModelPolling() {
  val job = pollingJob
  pollingJob = null
  if (job != null) {
    try {
      job.cancel(CancellationException("Gradum: stop model polling"))
    } catch (t: Throwable) {
      log.warn("Failed to cancel polling job", t)
    }
  }
}
```

### Verification

After a fresh build, the plugin's bytecode (via
`javap -p -c plugin-0.9.0.jar gradum.idea.chat.state.GradumChatSession`) should show:

```
16: new           #298   // class java/util/concurrent/CancellationException
20: ldc_w         #603   // String Gradum: stop model polling
23: invokespecial #302   // CancellationException.<init>:(Ljava/lang/String;)V
26: invokeinterface #306, 2  // Job.cancel:(Ljava/util/concurrent/CancellationException;)V
```

i.e. `invokeinterface` to the public `Job.cancel(CancellationException)` method, **no** `invokestatic
Job.cancel$default:(...)` anywhere. All 3 call sites (`stopModelPolling`, `reset`, `stopSession`)
verified clean.

### Lessons for Future Code

- **`runIde` is not hot-reload.** If you change plugin code, stop and restart the runIde process to
  pick up the new classes. Gradle's incremental build only updates the JAR; the long-lived IDE process
  has already loaded the old class.
- **Be skeptical of stack-trace line numbers.** If a user reports a `NoSuchMethodError` at a line that
  in your current source is in a comment, the IDE was running an old class. Verify by `javap -p -c` on
  the built JAR — if the JAR's bytecode already uses the correct method, the issue is class loading
  state, not source code.
- **Prefer explicit args over default-parameter shorthand for cross-version-sensitive calls.** Passing
  `CancellationException("...")` instead of `null` makes the call immune to synthetic-availability
  differences between coroutines versions.
- **Wrap cancel in try-catch.** It is a no-op cleanup and should never be allowed to crash the UI.
