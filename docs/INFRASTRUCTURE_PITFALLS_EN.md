# Gradum Plugin Infrastructure Pitfall Record (Jewel / Compose / Classloader)

> A record of **runtime errors, API traps, and engineering lessons** from integrating
> IntelliJ Platform 2026.2 + Jewel + Compose. Each section includes: symptoms → root cause → fix → verification.
> The goal is to prevent the next person (and your future self within six months) from **spending 7 hours** on this.

> **[WARNING] Disclaimer**: This document reflects the author's experience with a specific IDE version (2026.2) and
> library
> versions as of July 2026. IntelliJ Platform, Jewel, Compose, and related libraries are actively maintained; APIs may
> change, bugs may be fixed, and new best practices may emerge. **Always check the official documentation and source
> code for the latest information.** If this document conflicts with the official docs, the official docs win.

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
| Gradle                          | 9.5.1                                     | -                                                         |
| JVM Target                      | 25                                        | Supported by Kotlin 2.3.0; not supported by detekt 1.23.7 |

**Project directory**: `/Users/xxx/Documents/Code_Project/Gradum`
**Plugin submodule**: `/Users/xxx/Documents/Code_Project/Gradum/plugin`
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
**one set** of Compose jars (the plugin's own 10); `./gradlew :plugin:test`; all 293 tests pass.

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

Plugin crashes on IDE startup, can't even reach the tool window.

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

> This is why the error here is "plugin compiles fine, but classes not found at runtime."

### Fix

Go back to the **original shadow approach**, manually copy IDE jars to `plugin/libs/`, use `implementation(files(...))`
to put these classes into plugin/lib so PluginClassLoader can access them directly:

```kotlin
// Copy these jars from /Users/xxx/Applications/IntelliJ IDEA.app/Contents/lib/
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
> **Do not** use `bundledModule` to replace `implementation(files(...))`; the former only works at compile time; IDE
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

(In fact, **any** `intellij.libraries.*` or `intellij.platform.*` added to `<depends>` will trigger this error; skiko was just the first one found. coroutines has the same issue.)

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

The `composedJar` task was misconfigured earlier, it **nested** 5 Jewel Markdown extension jars inside the main jar
(`plugin-0.9.2.jar`) instead of laying them flat:

```kotlin
tasks.named("composedJar", ComposedJarTask::class.java) {
  from(bundledJars)  // ← only nests, doesn't unpack
}
```

Result: `plugin-0.9.2.jar` contains `intellij.platform.jewel.markdown.extensions.autolink.jar` as an embedded file, but
**JVM classloader doesn't dig into nested jars**. The classes were there all along; the classloader just couldn't see
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

`unzip -l plugin-0.9.2.jar` no longer nests 5 extension jars;
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

- `./gradlew :plugin:buildPlugin -Pgradum.skipDetektGate=true`: skip the detekt gate (project's built-in escape hatch,
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
| `bundledModule("intellij.platform.jewel.markdown.core")` etc. | Gradle compile classpath gets descriptor; runtime IDE loads via plugin.xml `<depends>` | **Doesn't work**: `<depends>` rejects `jetbrains` namespace IDs                        |
| `plugin/libs/` + `implementation(files(...))`                 | Copy IDE same-name jars into plugin/lib                                                | **Works**: but must exclude all LaTeX Compose transitive deps, otherwise StackOverflow |

With the second path, **plugin/lib's Compose and IDE's own Compose are held by two different classloaders**, but because
`kotlinx.coroutines.CoroutineScope` uses `compileOnly` (not copied), Compose internals don't hold another copy of any
interface that would be compared across classloaders, so no loader constraint violation is triggered. Dual-loading of
Compose's many classes / objects is permitted at the JVM level.

### Copy-Paste Checklist

```bash
# One-time copy of IDE jars
cd /Users/xxx/Documents/Code_Project/Gradum/plugin/libs
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
  cp -v "/Users/xxx/Applications/IntelliJ IDEA.app/Contents/lib/${jar}.jar" .
done
```

Key snippet in `plugin/build.gradle.kts`:

```kotlin
dependencies {
  // 15 IDE jar copies → plugin/lib
  implementation(files("libs/intellij.libraries.compose.foundation.desktop.jar"))
  // ... 14 more implementation(files("libs/...")) lines

  // kotlinx via compileOnly — runtime delegates to IDE PathClassLoader
  compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // LaTeX: exclude all Compose / Skiko / kotlinx / kotlin-stdlib transitive deps
  implementation("io.github.huarangmeng:latex-base:1.4.7") {
    exclude(group = "org.jetbrains.compose.*")
    exclude(group = "org.jetbrains.skiko")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
  }
  // Same for latex-parser / latex-renderer
}
```

`plugin/src/main/resources/META-INF/plugin.xml` `<depends>` stays **minimal, just two**:

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.compose</depends>
```

> **Do not** add `<depends>` for any `intellij.platform.*` / `intellij.libraries.*`.
> **Do not** use `bundledModule` to replace `implementation(files(...))`.
> **Do not** use `bundledModule` for `kotlinx-serialization-json`; IDE's version is re-versioned by JetBrains (reports
> 2.3.0), which is incompatible with Kotlin 2.3.0 compiler plugin's strict version check → `PROVIDED_RUNTIME_TOO_LOW`.
> **Do not** use `implementation` for `kotlinx-coroutines-core`; extra CoroutineScope interface in plugin/lib triggers
> `LinkageError`.

---

## 8. One-Line Summary

- **Compile**: `bundledModule` works fine.
- **Runtime**: Use `plugin/libs/` + `implementation(files(...))` to copy IDE same-name jars.
- **kotlinx-\* ecosystem**: Use `compileOnly`, runtime delegates to IDE PathClassLoader.
- **Three LaTeX artifacts**: Exclude all Compose / Skiko / kotlinx / kotlin-stdlib transitive dependencies.
- **plugin.xml `<depends>`**: Keep only `com.intellij.modules.platform` + `com.intellij.modules.compose`.
- **Job.cancel ()**: Always pass an explicit `CancellationException("...")`, never `null` and never no-arg. Wrapped in
  try-catch. Defends against both `NoSuchMethodError: cancel$default` and arbitrary coroutines version drift. See § 10.
- **runIde caveat**: `./gradlew :plugin:runIde` does NOT hot-reload plugin classes. After code changes, stop and restart
  the task. See § 10.

---

## 9. Timeline (Single Day: 2026-07-20)

| Time      | Event                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
|-----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Morning   | Migrated from shadow to bundledModule approach; all 10 Jewel/Compose bundledModule descriptors resolved; all 293 tests passed                                                                                                                                                                                                                                                                                                                                                                    |
| Afternoon | User actually ran the plugin; IDE startup reported `loader constraint violation: CoroutineScope`                                                                                                                                                                                                                                                                                                                                                                                                 |
| 14:00     | Added `bundledModule("intellij.libraries.kotlinx.coroutines.core")` + LaTeX exclude kotlinx → error changed to `PROVIDED_RUNTIME_TOO_LOW` (IDE re-versioned kotlinx-serialization-core) → switched back to `implementation` for kotlinx-serialization                                                                                                                                                                                                                                            |
| 14:30     | CoroutineScope error gone; `NoClassDefFoundError: InlinesStyling` appeared                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 14:45     | Tried adding `<depends>intellij.platform.jewel.markdown.core</depends>` etc. to plugin.xml → compiled, IDE reported `requires plugin 'intellij.libraries.skiko' to be installed`                                                                                                                                                                                                                                                                                                                 |
| 15:00     | Removed all `intellij.libraries.*` `<depends>` for skiko / coroutines → reported `requires plugin 'intellij.platform.compose' to be installed`                                                                                                                                                                                                                                                                                                                                                   |
| 15:15     | User proposed switching paths. Decision: revert to shadow + forced excludes                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 15:30     | Copied 15 jars to plugin/libs/, updated build.gradle.kts, deleted composedJar custom block                                                                                                                                                                                                                                                                                                                                                                                                       |
| 15:45     | `InlinesStyling` error gone; `NoClassDefFoundError: AutolinkProcessorExtension` appeared                                                                                                                                                                                                                                                                                                                                                                                                         |
| 16:00     | Moved 5 markdown extensions to `implementation` (previously via `composedJar.from`, nested inside main jar, invisible to classloader)                                                                                                                                                                                                                                                                                                                                                            |
| 16:15     | Plugin finally loads! Chat / Markdown / LaTeX all work                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| 16:30     | detekt fails because 1.23.7 doesn't support jvm-target 25 → bypassed with `-Pgradum.skipDetektGate=true`; wrote this document                                                                                                                                                                                                                                                                                                                                                                    |
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

> **[WARNING] The first version of this section (v1) claimed that the IDE's coroutines rebuild
> is missing the `cancel$default` synthetic. That was wrong.** The actual situation is more mundane
> but more annoying. The IDE DOES ship `cancel$default`. The error in the user's session was caused
> by the runIde process running a **stale `GradumChatSession.class`** that was compiled before the fix
> was applied. The line number in the stack trace (`GradumChatSession.kt:369`) is the **old** line number;
> in the current source the cancel call lives at line 380 (after the explanatory comment block was added).

What is true:

- IDE 2026.2 ships `kotlinx-coroutines-core:1.10.2-intellij-1` (a JetBrains internal rebuild, confirmed by
  `unzip -p Contents/lib/intellij.libraries.kotlinx.coroutines.core.jar META-INF/kotlinx_coroutines_core.version`).
- The IDE's `Job$DefaultImpls` DOES contain `cancel$default(Job, CancellationException, int, Object)`, verified by
  `javap` on the actual JAR and by a Java reflection probe that ran successfully against it.
- When the plugin is compiled with the no-arg form `pollingJob?.cancel()`, Kotlin 2.3.0 emits a call to that
  `cancel$default` synthetic. Because the synthetic does exist in the IDE's classpath, the call resolves correctly, and
  in the latest build the bytecode does not even call `cancel$default` because we pass an explicit arg.

The real problem is what happens to the error report. `NoSuchMethodError` for a classloader-missed method is a **hard
error**, not a recoverable coroutine exception. When the user reports seeing this error, the most likely explanations
(in order of probability) are:

1. **Stale plugin class in the running dev IDE.** `./gradlew :plugin:runIde` starts a long-lived IDE process. It loads
   the plugin JAR that existed when runIde started. Rebuilding the plugin while runIde is running does **not** reload
   the plugin classes; only restarting the runIde process does. The first time the user hits the error is a giveaway:
   the stack trace points at a source line that no longer contains the cancel call.
2. Stale class from a previous installation in the same IDE home (less likely with runIde, but possible if you also
   install the plugin ZIP for testing).
3. Genuine classpath corruption (e.g. an old `kotlinx-coroutines-core-1.11.0.jar` left in `plugin/lib/` from an earlier
   attempt before we moved to `compileOnly`), verify with
   `unzip -l plugin/build/distributions/plugin-0.9.2.zip | grep -i coroutines`.

### Fix

**Primary fix: just relaunch the runIde process.** Stop the runIde task (close its window or Ctrl-C in the terminal) and
start it again. The new build will be picked up.

**Belt-and-braces hardening applied to the source** (see
`gradum.idea.chat.state.GradumChatSession.stopModelPolling` / `reset` / `stopSession`):

1. Pass an **explicit** `CancellationException("...")` instead of `null`. The Kotlin compiler emits
   `invokeinterface Job.cancel:(Ljava/util/concurrent/CancellationException;)V` directly. No `cancel$default`
   synthetic is referenced, so the call cannot fail with `NoSuchMethodError` for that synthetic, even if a future IDE
   version strips the synthetic from `Job$DefaultImpls`.
2. Wrap the cancel in a `try { ... } catch (t: Throwable) { log.warn(...) }` block. Cancel is a cleanup no-op; if it
   ever does throw, we just log and move on. The UI stays interactive.

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
`javap -p -c plugin-0.9.2.jar gradum.idea.chat.state.GradumChatSession`) should show:

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

- **`runIde` is not hot-reload.** If you change plugin code, stop and restart the runIde process to pick up the new
  classes. Gradle's incremental build only updates the JAR; the long-lived IDE process has already loaded the old class.
- **Be skeptical of stack-trace line numbers.** If a user reports a `NoSuchMethodError` at a line that in your current
  source is in a comment, the IDE was running an old class. Verify by `javap -p -c` on the built JAR; if the JAR's
  bytecode already uses the correct method, the issue is class loading state, not source code.
- **Prefer explicit args over default-parameter shorthand for cross-version-sensitive calls.** Passing
  `CancellationException("...")` instead of `null` makes the call immune to synthetic-availability differences between
  coroutines versions.
- **Wrap cancel in try-catch.** It is a no-op cleanup and should never be allowed to crash the UI.

---

## 11. Compose / Jewel Integration Traps

### 11.1 Inline Content Chip Registration — Silent Drop

`Text(annotated, inlineContent = map)` requires `StringAnnotation.item` == map key **AND** a fixed internal tag. Compose
looks up chips via
`getStringAnnotations("androidx.compose.foundation.text.inlineContent", ...).filter { it.item == mapKey }`. The tag is `INLINE_CONTENT_TAG`, an internal constant in `InlineContentUtils`, NOT a user-defined tag.

Using `pushStringAnnotation("INLINE_CODE", placeholder)` (or any custom tag) makes Compose **silently drop every chip**.
The official extension `AnnotatedString.Builder.appendInlineContent(id, alternateText)` is `internal` in Compose
foundation 1.7.3; inline its 4-line impl (pushStringAnnotation + pushStyle (SpanStyle ()) + append (alternateText) + 2×
pop). Hardcode the tag string `"androidx.compose.foundation.text.inlineContent"` since the constant is internal.

> **Lesson**: The GradumInlineMarkdown chip renderer (v1, then v1.5) twice got this wrong, first because
> `annotation.item` didn't match the map key, then because the tag was user-defined. Both versions produced no error, no
> log; every chip was silently dropped and the segment fell back to default `Markdown(...)`.

Always pair the `INLINE_CONTENT_TAG` annotation with an `INLINE_CODE_TEXT` annotation (or similar) for the raw code text; that one CAN have a user-defined tag and is needed for click-to-copy.

### 11.2 Badge Style Color Trap — Transparent Tint

`JewelTheme.badgeStyle.*.colors.background` is `SolidColor(Color.Transparent)` for the default outlined badge style.
Don't use it as a tint source; `tint.copy(alpha = X)` of transparent is still transparent.

Use `JewelTheme.linkStyle.colors.content` (a solid `Color`) for code-chip tints, or `JewelTheme.contentColor` as a
fallback.

> **Lesson**: The GradumInlineMarkdown v2 originally read the badge background, made every chip invisible, AND the unit
> test didn't catch it because tests pass `tint` explicitly instead of resolving it from the theme. Any `@Composable`
> theme resolver should have a non-zero-alpha guard AND log the resolved value to the IDE log for sanity check.

### 11.3 Unit Tests Miss Theme Bugs

Unit tests that skip `@Composable` theme resolution miss theme bugs. Pass a real (or fake) `JewelTheme.contentColor` /
`linkStyle` to the resolver under test, or add a Compose-rendered test that mounts a real `JewelTheme` and asserts the
chip is visible.

### 11.4 `Markdown(...)` Ignores `ProvideMarkdownStyling` Unless Explicitly Wired

`Markdown(...)` (Jewel) does NOT read `markdownStyling` / `blockRenderer` / `processor` from
`LocalMarkdownStyling` / `LocalMarkdownBlockRenderer` / `LocalMarkdownProcessor`. The parameter defaults are
`JewelTheme.markdownStyling` / `JewelTheme.markdownBlockRenderer` / `JewelTheme.markdownProcessor` (the Jewel theme's
own values).

If you want the chat panel's `ProvideMarkdownStyling` to apply, you must either:

- **(a)** NOT pass `markdownStyling` / `blockRenderer` / `processor` to `Markdown(...)` AND wrap the call site in
  `ProvideMarkdownStyling`, OR
- **(b)** Pass `LocalMarkdownStyling.current` / `LocalMarkdownBlockRenderer.current` explicitly.

> **Lesson**: Explicitly passing `blockRenderer = JewelTheme.markdownBlockRenderer` inside `ProvideMarkdownStyling`
> overrides the local with the Jewel-default renderer, making headings / lists / fenced code render with default colors.
> This cost 3 hours of debugging before the fix.

### 11.5 `AnnotatedString.Builder` Has No No-Arg Constructor

`AnnotatedString.Builder` in Compose 1.7.3 has no no-arg constructor. Use
`buildAnnotatedString { val builder = this; ... }` to grab the builder as the receiver.

### 11.6 CommonMark API Gotchas

- `Document` from `org.commonmark.node` has no `children()` method. Walk via `firstChild` + `next` directly.
- `withStyle` is an extension function on `AnnotatedString.Builder`: must
  `import androidx.compose.ui.text.withStyle`.
- `TextUnit` is a value class. Construct via `.sp` / `.em` extensions; do not write `TextUnit(value, type)`.

### 11.7 `LayoutCoordinates.getOffsetForPosition` Does NOT Exist

`LayoutCoordinates.getOffsetForPosition(offset)` does NOT exist on this Compose version (verified by `javap` on
`LayoutCoordinates.class` from `intellij.libraries.compose.foundation.desktop.jar`, the only public methods are
`windowToLocal`, `localToScreen`, `localToRoot`, `localPositionOf`, `localBoundingBoxOf`, `get(AlignmentLine)`).

For text hit-testing (mapping a tap position to a character offset) use `TextLayoutResult.getOffsetForPosition(Offset)`
instead, and capture the result via the `Text` composable's `onTextLayout: (TextLayoutResult) -> Unit` callback.

The correct path: hold a `MutableState<TextLayoutResult?>` filled by `onTextLayout`, read it inside
`detectTapGestures`, and call `.getOffsetForPosition(offset)` on the layout result.

### 11.8 `GlobalColors.editorBackground` Does NOT Exist

`GlobalColors.editorBackground` does NOT exist; use `GlobalColors.panelBackground` for the LaTeX chip background tint.

---

## 12. LaTeX Rendering Pitfalls (LANDED 2026-07-21, 7 Debug Rounds)

### 12.1 User's "Aha Moment": Block vs Inline LaTeX

The first 4 rounds were adjusting the block-level `$$…$$` `Box` container (48dp vertical padding + heightIn to lock the
first frame), but the user's test samples were all single-line `$…$`, **inline formulas, not block**. The block
modifications had zero effect on the inline path.

> **Lesson**: First lock down the code path using LaTeX syntax (inline `$…$` / block `$$…$$`), then choose the
> Box/Placeholder fix. `$x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}$` that appears on its own line is STILL inline; it goes
> through `RenderInlineLatex` → PUA Placeholder in the text line, NOT `RenderLatexBlock` → standalone Box.

### 12.2 Internal Padding from huarangmeng Library

Constants in `com.hrm.latex.renderer.utils.MathConstants`:

- `CANVAS_HORIZONTAL_PADDING = 0.15f`
- `CANVAS_VERTICAL_PADDING = 0.10f`

Applied at `LatexRenderer.kt:98-102` (`val horizontalPadding = fontSizePx * CANVAS_HORIZONTAL_PADDING`, then
`canvasWidth = layout.width + horizontalPadding * 2`), and the `Latex` composable hard-sizes itself via
`modifier.size(widthDp, heightDp)` using `renderResult.canvasWidth` / `canvasHeight` (which INCLUDE the padding).

For 18.sp block formula: ~1.8.dp top + 1.8.dp bottom = 3.6.dp vertical, ~2.7.dp left + 2.7.dp right = 5.4.dp horizontal.
NOT visible to parent layout (part of Canvas size, not separate Modifier padding). NOT configurable from
`LatexConfig`.

The user's-eye gap is exactly `BLOCK_LATEX_VERTICAL_PADDING_DP` (48.dp) between formula glyphs and surrounding text.

### 12.3 Async Parsing with Layout Shift

The `Latex` composable uses `LaunchedEffect(latex) + withContext(Dispatchers.Default)` to parse the formula. First frame
has `document = LatexNode.Document(emptyList())` → `LatexDocument.measure(empty)` returns tiny
`canvasWidth/canvasHeight`
→ Canvas is ~0×0. After parse completes, `document` updates → `LatexDocument.measure(parsed)` re-measures → Canvas snaps
to formula's real size.

**This causes a layout shift on first frame**, the wrapping Box starts at padding-only height and jumps to the real
height after parse. Text below visibly jumps.

**Mitigation**: wrap the Latex in a `Box` with
`Modifier.heightIn(min = BLOCK_LATEX_MIN_CONTENT_HEIGHT_DP.dp + BLOCK_LATEX_VERTICAL_PADDING_DP.dp * 2)` (currently
44.dp + 48.dp×2 = 140.dp outer) so the Box is a stable size on the first frame.

### 12.4 Inline LaTeX Placeholder Too Short → Formula Overflows Line

`RenderState.allocateLatex(formulaText)` was using the same `PLACEHOLDER_LINE_HEIGHT_MULTIPLIER = 1.0f` as the
chip/footnote, giving `placeholderHeight = fontSizeSp × 1.0 + 2sp`. For a single line of text that's correct, but for a
`\frac{a}{b}` the huarangmeng Canvas is 2× the font size tall, and a nested `\frac{...}{...}` or `\sqrt{...}` is 2.5–3×.
The Placeholder is what the surrounding `Text(annotated, inlineContent = ...)` uses for line height; if the Placeholder
is too short, the line box is too short, the formula Canvas overflows vertically, and visually covers the next line of
text.

**Fix**: dedicated `INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER = 2.2f` and `INLINE_LATEX_PLACEHOLDER_PADDING_SP`
(2sp) used only by `allocateLatex`. 2.2× is a safe default that fits single-line formulas with one small fraction.

### 12.5 Placeholder Vertical Alignment: Center vs TextCenter

| Mode                   | Behavior                                                           | Problem                                                                                              |
|------------------------|--------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `Center`               | Placeholder center = line-height center (baseline + 0.75×fontSize) | Formula visual center at x-height (baseline + 0.5×fontSize): offset 0.25×fontSize, looks "elevated" |
| `AboveBaseline`        | Placeholder bottom = baseline                                      | Formula visual center at baseline + 1.25×fontSize: still elevated                                   |
| `TextCenter` [CORRECT] | Placeholder center = x-height (baseline + 0.5×fontSize)            | Matches formula visual center: this is what LaTeX `\textstyle` does                                 |

### 12.6 Block-Level LaTeX Rendering Structure (Final)

```
RenderLatexBlock(formula)
  └── Box(modifier = fillMaxWidth() + heightIn(min=140dp) + padding(vertical=48dp),
            contentAlignment = TopCenter)
        └── Latex(latex=formula, config=18sp + Color.Transparent)  // no internal fillMaxWidth
              └── Canvas(Modifier.size(canvasWidth, canvasHeight))  // canvasWidth/Height includes library padding
                    └── formula glyphs
```

Parent layout: `AssistantChatBubble`'s `Column` has no `verticalArrangement` (inter-block spacing determined by
`padding(vertical=48dp)`), no horizontal padding (formula centered on the full chat panel content area).

### 12.7 Library Canvas Horizontal Padding Side Effect

`MathConstants.CANVAS_HORIZONTAL_PADDING = 0.15f` (proportional to fontSize) is folded into `renderResult.canvasWidth`,
so the Latex Canvas width = formula visible width + 2×0.15×fontSize (~5.4dp transparent padding at 18sp). The user
perceiving "large right margin" is actually a visual illusion from the panel content area being much wider than the formula; left and right are perfectly symmetric. Cannot be eliminated externally (hardcoded in library); the only way
to reduce perceived "right margin" is to narrow the chat panel content area (`maxContentWidth`).

### 12.8 PUA Placeholder Allocation Table

| Codepoint       | Usage                                                     |
|-----------------|-----------------------------------------------------------|
| `U+E000`        | Inline code chip                                          |
| `U+E001`        | Image alt text                                            |
| `U+E002`        | Footnote mark                                             |
| `U+E003`        | Inline LaTeX chip                                         |
| `U+E100–U+E1FF` | Paren-form LaTeX `\(…\)` preprocessor markers (256 slots) |
| `U+E200–U+E2FF` | Dollar-form LaTeX `$…$` preprocessor markers (256 slots)  |

Within one chip type, the key is `base.toString().repeat(counter + 1)`; counter resets per `parseInlineMarkdown`
call (each call creates a fresh `RenderState`).

### 12.9 LaTeX Rendering Debug Timeline

| Round | User Feedback                                                                   | Root Cause                                                                                                                                                                            | Fix                                                                                                                                 |
|-------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| 1     | Inline code truncated, block LaTeX overlapping, `\(\Delta > 0\)` shows raw text | chip `clip+background` clips children; block LaTeX no vertical padding; CommonMark backslash escaping eats `\(`                                                                       | chip uses `background(rounded)` without clipping; add 2dp vertical padding + 8dp rounded corners; preprocessor PUA-replaces `\(…\)` |
| 2     | Block LaTeX spacing abnormal                                                    | No vertical padding set                                                                                                                                                               | Add 24dp → 32dp                                                                                                                     |
| 3     | Library draws background as chip, block LaTeX not centered, large right margin  | `LatexConfig.backgroundColor = panelBackground` makes library draw background; internal `Latex` uses `fillMaxWidth`; library `CANVAS_HORIZONTAL_PADDING=0.15f` folds into canvasWidth | Change to `Color.Transparent`; remove internal `fillMaxWidth`; Box uses `fillMaxWidth + contentAlignment=TopCenter`                 |
| 4     | Block LaTeX still squeezes text below                                           | huarangmeng library **async parsing first frame 0×0** causes layout shift                                                                                                             | Box adds `heightIn(min=44+48×2=140dp)` to lock first frame; vertical padding → 48dp                                                 |
| 5     | Discovered it's inline formulas blocking                                        | 1.0× Placeholder too short for `\frac{}{}`                                                                                                                                            | `INLINE_LATEX_PLACEHOLDER_LINE_HEIGHT_MULTIPLIER=1.0→2.2`                                                                           |
| 6     | Increased default + wrote memory file + reported new bug                        | 2.2× still insufficient                                                                                                                                                               | → 2.5×                                                                                                                              |
| 7     | Inline formula baseline rises                                                   | `PlaceholderVerticalAlign.Center` centers by line-height, formula visual center is at x-height                                                                                        | → `PlaceholderVerticalAlign.TextCenter` (aligns by x-height)                                                                        |

### 12.10 Inline LaTeX Placeholder Width — From Estimation to Real Measurement

**Problem**: `allocateLatex()` used `estimateLatexWidth()`, a per-character heuristic (letters 0.45em, narrow symbols
0.25em, digits 0.45em, CJK 1.0em), to set the `Placeholder` width. This produced visible right-side whitespace on
formulas like
`2(3)^2 - 7(3) + 3 = 18 - 21 + 3 = 0` because the estimate couldn't account for the actual glyph widths rendered by the
huarangmeng library.

**Initial wrong approach**: Tried to improve the heuristic with tighter per-character ratios. This is fundamentally unreliable; no character-level estimate can match the library's internal layout engine which considers font metrics,
kerning, and math-mode spacing.

**Solution**: Use the library's own synchronous measurement API (`LatexMeasurerState.measure()`) to get the real
rendered dimensions. The huarangmeng library provides:

```kotlin
class LatexMeasurerState {
    fun measure(
        latex: String,
        config: LatexConfig = LatexConfig(),
        isDarkTheme: Boolean = false
    ): LatexDimensions?  // returns widthPx, heightPx, baselinePx, etc.
}
```

**Implementation**:

1. Create `LatexMeasurerState` via `rememberLatexMeasurer()` in composable scope (needs `Density` + `TextMeasurer`).
2. Pass it through `rememberInlineMarkdownRender()` → `parseInlineMarkdown()` → `buildInlineRender()` → `RenderState`.
3. In `allocateLatex()`, call `latexMeasurer.measure(formulaText, config = LatexConfig(fontSize = fontSizeSp.sp))` and
   use
   `dimensions.widthPx / density.density` as the placeholder width.
4. Cache results in `mutableMapOf<String, LatexDimensions?>()` to avoid re-measuring the same formula.
5. Fallback to `estimateLatexWidth()` if `measure()` returns `null` (parse failure).

**Key findings about the library's measurement**:

- `measure()` is **fully synchronous**: no async, no lazy init, no warmup. `TextMeasurer` is immediately ready on first
  call.
- It **cannot return 0×0 dimensions**: guard `layout.width <= 0f || layout.height <= 0f` returns `null` instead.
- No first-call vs subsequent-call difference: the method is stateless.
- The `LatexConfig` passed to `measure()` must match the one used for rendering (same `fontSize`), otherwise measured
  width won't match actual rendered width.

**Config mismatch pitfall**: Initially created `LatexMeasurerState` with default `LatexConfig()` (fontSize = 20.sp), but
rendering uses `fontSizeSp.sp` (~13.sp). This caused measured widths to be ~54% larger than actual rendered widths. Fix:
pass `LatexConfig(fontSize = fontSizeSp.sp)` to `measure()`.

**Backward compatibility**: All new parameters (`latexMeasurer`, `density`) are nullable with default `null`. Callers
that don't pass them (e.g., `parseInlineNodes()` for heading paths, `BlockRenderer.kt`'s `RenderInlineTextWithChips`)
fall back to the estimation heuristic, acceptable because heading text rarely contains inline LaTeX.

---

## 13. Inline Markdown Engineering (LANDED 2026-07-14, v2.1)

### 13.1 Inline Code Chip — Key Pitfalls

**Chip lineHeight clips glyphs**: the chip's internal `Text` must explicitly set `lineHeight = fontSize` (1.0x).
Inheriting the editor style's `lineHeight` (1.5x) clips the glyphs to ~0 visible pixels.

**Badge background is transparent**: `JewelTheme.badgeStyle.*.colors.background` is `SolidColor(Color.Transparent)`. Use
`JewelTheme.linkStyle.colors.content` for chip tints instead.

**CJK chip width too narrow**: `MONOSPACE_CHAR_WIDTH_RATIO (0.6f) × code.length` clips CJK characters (which are
full-width ~1.0). Use `cjkAwareWidthRatio(code)`, 1.0 for CJK, 0.6 for Latin, computed char-by-char.

**`node.unlink()` loses siblings**: `generateSequence { it.next }.forEach { appendChild(it) }` breaks because
`appendChild` → `unlink` → `next = null` mid-iteration. Always capture `next` BEFORE unlinking:

```kotlin
var current = first.next
while (current != null) {
  val next = current.next
  strippedParagraph.appendChild(current)
  current = next
}
```

**`serializeInto` must cover every block type**: the `else` branch falls through to `serializeChildrenInto` which
produces empty output for blocks with `formula: String` fields (no children).

**Kotlin string template gotcha**: `"$$x^2$$"` is a template reference. Use raw strings `"""$$x^2$$"""` for test strings
containing `$`. The test framework's `Edit` tool also has this issue; use Python scripts to batch-escape `$`.

**commonmark parser OrderedList trap**: text starting with "2." in headings, list items, blockquotes, and paragraphs is
treated as OrderedList start, causing bold syntax (`**text**`) to be dropped when reparsing via `parseInlineMarkdown`.
Use `parseInlineNodes` + `rememberInlineMarkdownRenderFromNode` to directly process AST inline children instead.

**End-to-end tests must check root node type**: `Parser.parse()` returns a `Document`, so appending it to another `Document` causes nested structure; use the parsed `Node` directly instead of wrapping it.

**GradumBlockRenderer compilation errors**: incorrect casting of `ListItem`, using non-existent `editorBackground` from
`GlobalColors`, using non-existent `HorizontalDivider` composable, and accessing non-existent `textStyle` on
`Paragraph`. Fixes: cast to `ListItem`, use `panelBackground` with fallback, replace divider with styled `Box`, access
`inlinesStyling.textStyle`. These errors caused IDE to use old renderer version with default styles.

### 13.2 Architecture Summary

The inline Markdown system uses a two-layer architecture:

- **Layer 1** (`BlockSplit.kt`): splits `Plain` segments on block boundaries via `splitPlainAtBlocks()`.
  `Paragraph` → `Plain`, everything else → `NonProseBlock`.
- **Layer 2** (`InlineMarkdown.kt`): handles `Plain` sub-segments with inline chips (code, image alt, footnote, LaTeX).
  `NonProseBlock` renders via Jewel's `Markdown(...)`.

Key design decisions (see `plugin/src/main/kotlin/gradum/idea/chat/ui/markdown/` for implementation):

- GFM Strikethrough: `combineDecoration` helper stacks LineThrough + Underline
- Clickable links: `TextLayoutResult.getOffsetForPosition(offset)` for hit-testing (NOT `LayoutCoordinates`)
- Nested lists: custom renderer with `indentDepth + 1` recursion (Jewel native drops code chips)
- Blockquote left bar: `Box.width(4.dp).clip(RoundedCornerShape(50%)).background()` (not `drawBehind`)
- Task lists: `CheckboxRow(checked, enabled = false)`: disabled, no interaction
- Link underline: `ShowAlways` (not `ShowOnHover`, unreliable in chat context)
- Inline image alt: italic placeholder with icon, NOT a link
- PUA allocation: code=`\uE000`, image=`\uE001`, footnote=`\uE002`, LaTeX=`\uE003`
- Four parsers need extension registration: `blockSplitParser` [OK], `blockReparseParser` [OK], `commonmarkParser` [NO],
  `GradumMarkdownProcessor` ✗ (intentional, no LaTeX in code blocks)

---

## 14. Core Principles

### 15.1 Read the Source Code — Don't Guess

When you encounter an unsolvable problem, **read the actual source code**. Do not guess based on error messages,
documentation, or blog posts. This document itself was born from guessing:

- We assumed `bundledModule()` would work at runtime: it only works at compile time. Reading the IntelliJ Platform
  Gradle Plugin source would have revealed this in minutes.
- We assumed `LayoutCoordinates.getOffsetForPosition(offset)` existed: a quick `javap` on the class would have shown it
  doesn't.
- We assumed `GlobalColors.editorBackground` existed: reading the Jewel source would have shown it's
  `panelBackground`.
- We assumed detekt 1.23.7 supported JVM 25: checking the compatibility table would have saved an hour.

**The source code is the single source of truth.** Documentation is often outdated, blog posts are often wrong, and AI
assistants (including this one) can hallucinate API names. When in doubt:

1. `javap -p -c` on the JAR to inspect bytecode
2. Read the actual `.class` or `.kt` source in the IDE
3. Check the library's `pom.xml` for transitive dependencies
4. Verify with `unzip -l` what's actually in the built artifact

### 15.2 Don't Blindly Trust Unit Tests

Unit tests can pass while the production code is completely broken. This document records multiple cases:

- **Badge tint trap (§11.2)**: Tests passed `tint` explicitly instead of resolving it from the theme. Every chip was
  invisible in production because `JewelTheme.badgeStyle.*.colors.background` is `SolidColor(Color.Transparent)`. The
  test didn't catch it because it never resolved the theme.
- **Inline content chip (§11.1)**: v1 and v2 both had silent chip drops. No test caught it because tests don't mount a
  real `JewelTheme`; they just call the function with synthetic inputs.
- **`Markdown(...)` defaults (§11.4)**: Tests passed `markdownStyling` explicitly, so they never discovered that the
  production code's `ProvideMarkdownStyling` was being overridden by Jewel's theme defaults.

**Rules for trustworthy tests:**

1. If a function uses `@Composable` theme resolution, the test must either use a real `JewelTheme` or a fake that
   exercises the same code path.
2. If a function produces visual output (chips, colors, layout), add a Compose-rendered test that asserts the output is
   visible (not transparent, not zero-size).
3. If a function depends on `LocalMarkdownStyling` / `LocalMarkdownBlockRenderer`, wrap the test call in
   `ProvideMarkdownStyling` with test values.
4. Always test the **integration path**: not just the unit in isolation.

### 15.3 Other Principles

- **Verify with `javap`, not with docs.** If you need to know whether a method exists on a class, inspect the bytecode.
  Documentation lies; bytecode doesn't.
- **Check transitive dependencies.** `./gradlew :dependencies` or `unzip -l` on the built JAR to see what actually ended
  up in `plugin/lib/`. Don't assume excludes worked; verify.
- **Restart after code changes.** `./gradlew :plugin:runIde` does NOT hot-reload. If you changed code and the old
  behavior persists, restart the process before debugging further.
- **Suspicious stack traces.** If a line number in a stack trace points to code that no longer exists in your source,
  the IDE is running a stale class. Rebuild and restart.
- **One classloader, one copy.** The JVM rule is simple: the same interface loaded by two different classloaders
  triggers `LinkageError`. Classes (non-interface) can be dual-loaded safely. Know the difference.

---

## 15. `applicationConfigurable` extension: `implementation` is silently ignored — use `instance`

> **LANDED 2026-08-15, debugging round 1. The whole Configurable was missing from the Settings tree.**

### Symptoms

- Settings → Tools does **not** show the "Gradum" entry at all.
- Or: clicking the parent's own node shows a *different* page, because the user thought they clicked "Gradum" but
  actually clicked the parent.
- No exception, no log entry. The Configurable just never registers.

### Root Cause

The `com.intellij.applicationConfigurable` extension point (and `projectConfigurable`) declares **only these
attributes**
in its EP descriptor
([Settings Guide → Settings Declaration Attributes](https://plugins.jetbrains.com/docs/intellij/settings-guide.html)):

| Attribute           | Required   | Purpose                                                |
|---------------------|------------|--------------------------------------------------------|
| `id`                | yes        | Stable ID, must be unique                              |
| `displayName`       | yes        | Tree label (i18n via `key`+`bundle` recommended)       |
| `instance`          | **one of** | FQN of a `Configurable` implementation                 |
| `provider`          | **one of** | FQN of a `ConfigurableProvider` implementation         |
| `parentId`          | no         | Existing Configurable ID this one nests under          |
| `nonDefaultProject` | no         | `projectConfigurable` only: hide in non-project scope |

There is **no `implementation` attribute**. The IDE parser ignores unknown attributes silently; the EP entry is
registered as a *valid Configurable* with an `instance` of `null` (or whatever default the parser uses), and the
Settings UI simply filters it out because it has nothing to instantiate.

We initially wrote:

```xml
<applicationConfigurable
    parentId="tools"
    displayName="Gradum"
    implementation="gradum.idea.settings.GradumConfigurable"
    id="gradum.settings"
/>
```

The IDE accepted this XML, accepted the `applicationConfigurable` tag, **accepted `implementation` as "unknown but
harmless"**, and never linked `GradumConfigurable` to the `gradum.settings` ID. Result: zero Configurables in the tree
under Tools, no log, no error, no warning.

### Fix

Rename the attribute from `implementation` to `instance`. That's it, one character sequence.

```xml
<applicationConfigurable
    parentId="tools"
    displayName="Gradum"
    instance="gradum.idea.settings.GradumConfigurable"
    id="gradum.settings"
/>
```

**Always prefer `instance` over `provider`** unless your Configurable needs lazy construction (e.g. accessing the
project when only the application is available). The provider variant requires a separate class implementing
`ConfigurableProvider.getConfigurable()`.

### Verification

1. `unzip -l` the IDE's `Contents/lib/platform-impl.jar` and grep for `applicationConfigurable`'s EP descriptor XML:
   ```bash
   unzip -p /Applications/IntelliJ\ IDEA.app/Contents/lib/platform-impl.jar \
     META-INF/ExtensionPoints.xml 2>/dev/null \
     | grep -A 30 "applicationConfigurable"
   ```
   The `<extensionPoint>` block lists every accepted attribute. If `implementation` is not there, **it's not
   supported**.

2. Or: search the JetBrains Settings Guide page source for the word `implementation`; the EP description will
   explicitly say "Either `instance` or `provider` must be specified." That phrase is the contract.

3. After the rename, restart the IDE (not hot-reload, `plugin.xml` changes need a fresh process) and re-open Settings →
   Tools. The "Gradum" node should appear.

### Lesson

> **EP attribute names are not forgiving.** Unknown attribute names don't throw; they get dropped. There is no IDE
> log line saying "ignored attribute `implementation`." The only signal is "the Configurable never shows up."
>
> If you find yourself saying "the Configurable isn't loading and there are no errors", first check the EP attribute
> table. Always.

---

## 16. ComposePanel under `JewelComposePanel` — `Modifier.fillMaxSize()` triggers a 32766×32766 Skia texture request

> **LANDED 2026-08-15. Three debugging rounds: (1) `IllegalArgumentException`, (2) plain `Configurable` workarounds
> via `preferredSize` / `.fillMaxSize` removal, (3) the real "back door", `Configurable.NoScroll`, discovered by
> reading JetBrains' own `ComposeSearchableConfigurable` source.**

### Symptoms

Two phases of failure, in order:

**Phase 1: perpetual spinner** (when using `androidx.compose.ui.awt.ComposePanel` directly):

The settings tree shows "Gradum", but the right pane never paints anything except the IDE's "Loading…" indicator. No
exception in the log, but a Compose snapshot inspector would show the composition never reaches `setContent`.

**Phase 2: `IllegalArgumentException` from Metal** (after switching to `JewelComposePanel`):

```
java.lang.IllegalArgumentException: Texture dimensions must be less than maximum allowed size: 16384, got 32766 x 32766
    at org.jetbrains.skiko.swing.MetalSwingRedrawer.onRender(MetalSwingRedrawer.kt:63)
    at org.jetbrains.skiko.swing.SkiaSwingLayer.paint(SkiaSwingLayer.kt:115)
    at androidx.compose.ui.scene.skia.SwingSkiaLayerComponent$hierarchyRoot$1.paint(SwingSkiaLayerComponent.desktop.kt:67)
    at java.desktop/javax.swing.JComponent.paintChildren(JComponent.java:964)
    ...
```

The crash happens on **first** paint, every time the user opens Settings → Gradum.

### Root Cause

`JewelComposePanelWrapper` ([
`intellij.platform.jewel.ideLafBridge` JAR](file:///private/tmp/wrapper_kt/org/jetbrains/jewel/bridge/JewelComposePanelWrapper.class))
extends `com.intellij.util.ui.components.BorderLayoutPanel`. Its CENTER child is an
`androidx.compose.ui.awt.ComposePanel`. The chain that produces the crash:

1. **First measure pass**: the Settings dialog drops the panel into a `BorderLayout.CENTER`. BorderLayout asks the
   CENTER child for its `preferredSize` to compute the initial allocation.
2. **`preferredSize` was `null`** in our code (we never set it). BorderLayout's default behavior is to hand the CENTER
   child a **`0×0` area** on first measure.
3. **Compose receives `Constraints(maxWidth=0, maxHeight=0)`**. Our root `Box(modifier = Modifier.fillMaxSize())`
   reacts: "I want to be as large as possible, but the max is 0, so... the only way to be 'filled' is to be
   Int.MAX_VALUE."
   Internally, Compose's `LayoutNode.measure` walks an "unspecified max" branch that clamps to `Int.MAX_VALUE` instead
   of 0.
4. **ComposePanel passes `Int.MAX_VALUE` to the SkiaLayer** as the surface size.
5. **SkiaLayer calls `SkSurface::MakeRenderTarget(Int.MAX_VALUE, Int.MAX_VALUE)`**. Skia's internal validation truncates
   `int` to a 16-bit signed value somewhere along the way → `0x7FFE = 32766`.
6. **Metal rejects**: 32766 > 16384 (the GPU texture maximum). `IllegalArgumentException`.

The magic number `32766` is the diagnostic fingerprint: `0x7FFE` is `Int.MAX_VALUE` (`0x7FFFFFFF`) truncated to a 16-bit
signed value. If you ever see this number, you have an Infinity-constraint leak into SkiaLayer.

### Fix

**Primary fix: implement `Configurable.NoScroll`** (this is what JetBrains' own
[`ComposeSearchableConfigurable`](https://github.com/JetBrains/intellij-community/blob/master/platform/compose/src/com/intellij/platform/compose/ComposeSearchableConfigurable.kt)
does, and what their
[showcase example](https://github.com/JetBrains/intellij-community/blob/master/plugins/devkit/intellij.devkit.compose/src/showcase/SettingsPageOnCompose.kt)
verifies works without any `preferredSize` shim):

```kotlin
class GradumConfigurable : Configurable, Configurable.NoScroll {
  override fun createComponent(): JComponent = JewelComposePanel {
    Settings()                                   // can use Column(fillMaxSize) + GroupHeader(fillMaxWidth) freely
  }
}
```

**Why this works** (the actual "back door"):

- A plain `Configurable` is wrapped in a **`JBScrollPane`** by the Settings dialog. `JBScrollPane`'s viewport gives the
  CENTER child a `0×0` first measure → triggers the cascade below.
- `Configurable.NoScroll` is a marker interface that tells the Settings dialog: **"skip the scroll pane, give this
  Configurable the full CENTER area directly."** The first measure then has a real width/height (the dialog's actual
  size), so `fillMaxWidth()` / `fillMaxSize()` get a finite max constraint and the `0×0 → Int.MAX_VALUE → 32766` cascade
  never starts.

Only one piece of the fix matters; the other two options below are obsolete once you add `NoScroll`.

---

**Workaround A: remove `Modifier.fillMaxSize()` from the root Box** (only needed if you cannot use `NoScroll`, e.g.
because your settings page really is long enough to need scrolling):

```kotlin
@Composable
private fun HelloPanel() {
  // Intentionally NOT using Modifier.fillMaxSize(): see §16.
  Box(
    modifier = Modifier.padding(16.dp),  // ← was: .fillMaxSize().padding(16.dp)
    contentAlignment = Alignment.Center
  ) {
    Text(...)
  }
}
```

`Modifier.padding(16.dp)` alone makes the Box wrap its content's intrinsic size. Whatever the SkiaLayer receives is the
actual text size, not Infinity.

**Workaround B: set JComponent `preferredSize`/`minimumSize` on the wrapper panel** (a belt-and-suspenders measure for
any future Box that *does* want to fill, but still wrapped in the JBScrollPane):

```kotlin
override fun createComponent(): JComponent = JewelComposePanel {
  HelloPanel()
}.apply {
  preferredSize = java.awt.Dimension(640, 400)
  minimumSize = java.awt.Dimension(400, 240)
}
```

- `preferredSize = 640×400`: tells BorderLayout to allocate at least that much on first measure, so even a
  `fillMaxSize()` child receives a real max constraint.
- `minimumSize = 400×240`: prevents the user from resizing the dialog below the texture's safe size, which would
  re-trigger the same 0×0 → Int.MAX_VALUE cascade.

### Verification

1. **Visual**: open Settings → Tools → Gradum. The "Hello, Gradum!" Text must render immediately, no spinner, no
   exception in `idea.log`.
2. **Log scan**: `grep -i "32766\|Texture dimensions" ~/Library/Logs/JetBrains/IntelliJIdea*/idea.log` must return zero
   matches.
3. **Resize**: drag the Settings dialog to half its width and then back. `Box.padding(16.dp)` should keep the Text
   centered. The `preferredSize` / `minimumSize` fallbacks are never engaged because the primary fix removes the
   trigger.
4. **ComposePanel version check**: confirm `createComponent` returns `JewelComposePanel` (from
   `intellij.platform.jewel.ideLafBridge`), **not** `androidx.compose.ui.awt.ComposePanel` directly. The latter never
   starts a Recomposer (phase 1 symptom) and never bridges SwingBridgeTheme (theme tokens would be `null`).

### Lesson

> **`fillMaxSize` is a trap under JewelComposePanel, but only when the Configurable is wrapped in a
> `JBScrollPane`.** Under normal Compose Multiplatform, a 0×0 first-measure constraint degrades to wrap-content. Under
> `JewelComposePanel` + `JBScrollPane`, it degrades to `Int.MAX_VALUE` because the `JBScrollPane` viewport has no
> preferred size and the chain
> `JBScrollPane → BorderLayout → ComposePanel → SkiaLayer` doesn't clamp.
>
> The fingerprint is the literal number `32766`. If you see it, the **first thing to try** is
> `class Foo : Configurable, Configurable.NoScroll`, one interface addition, no `preferredSize`, no `.fillMaxSize()`
> paranoia. Reserve the workarounds below for cases where you genuinely cannot use `NoScroll`.

---

## 17. Ktor `HttpClient.post()` Buffers the Entire Response Body — Streaming Broken

> **LANDED 2026-08-22. A "thousand-year-old bug", the Ollama streaming response (thinking tokens) appeared to be
> "sprayed out" all at once instead of token-by-token. Root cause: Ktor `post()` caches the response body in memory;
> `bodyAsChannel()` after `post()` reads from the buffer, not the network socket. The fix: switch to
> `preparePost().execute {}`.**

### Symptoms

- The Gradum plugin's thinking block shows all reasoning content at once, even though the server logs confirm
  `ReasoningContent` chunks are emitted individually.
- `curl -N http://localhost:11434/api/chat` (with `-N` to disable curl's buffering) shows real-time streaming; Ollama
  is working correctly.
- But the Ktor client receives everything in one batch after the model finishes generating.

### Root Cause

Two Ktor API traps conspired:

1. **`HttpClient.post()` returns `HttpResponse` only after the response is fully received.** The CIO engine buffers the
   entire body in memory before returning control to the caller. By the time `bodyAsChannel()` is called, the data is
   already in a memory buffer; no real streaming happens.

2. **`HttpResponse.body()` loads the full response into memory by default.** The Ktor documentation states this
   explicitly: *"For non-streaming requests, the response body is automatically loaded and cached in memory, allowing
   repeated access."* The streaming alternative, `HttpStatement.execute {}`, is buried in the streaming section.

The `bodyAsChannel()` function is **not** the streaming escape hatch it appears to be. When called after `post()`, it
returns a `ByteReadChannel` backed by the already-buffered body. The network socket closed long ago.

### Fix

Replace `httpClient.post(url) { }.bodyAsChannel()` with `httpClient.preparePost(url) { }.execute { response -> ... }`:

```kotlin
// Before: post() buffers the entire response body
val httpResponse: HttpResponse = httpClient.post(requestUrl) {
  contentType(ContentType.Application.Json)
  setBody(JsonUtil.encodeMap(requestPayload))
}
val responseChannel: ByteReadChannel = httpResponse.bodyAsChannel()
while (!responseChannel.isClosedForRead) {
  val rawLine: String = responseChannel.readUTF8Line() ?: break
  // parse and emit...
}

// After: preparePost().execute() keeps the connection open for streaming
val flowCollector = this  // capture outer FlowCollector for emit() inside execute block
httpClient.preparePost(requestUrl) {
  contentType(ContentType.Application.Json)
  setBody(JsonUtil.encodeMap(requestPayload))
}.execute { httpResponse ->
  val responseChannel: ByteReadChannel = httpResponse.body()
  while (!responseChannel.isClosedForRead) {
    val rawLine: String = responseChannel.readUTF8Line() ?: break
    // parse and emit via flowCollector.emit()...
  }
}
```

**Key details:**

- `preparePost()` returns `HttpStatement`, not `HttpResponse`. The `execute {}` block runs with the response stream
  still open, so `body()` returns a live `ByteReadChannel` that reads from the network.
- Inside `execute {}`, the `FlowCollector` receiver from the outer `flow { }` builder is **not** available (the
  receiver is `HttpResponse`). Capture it explicitly: `val flowCollector = this` before `execute {}`.
- The `io.ktor.client.statement.useEngineDispatcher` JVM system property is **not** required for this fix; the default dispatcher works fine.

### Files Changed

- `src/main/kotlin/gradum/client/LLMClient.kt`: Ollama `sendChat()` method, line 267→329

### Verification

1. Send a long-reasoning prompt (e.g., *"Design a distributed key-value store with consistent hashing, data
   partitioning, replication, and failure recovery"*).
2. Observe the plugin's thinking block: content should appear token-by-token, not all at once.
3. Confirm `curl -N http://localhost:11434/api/chat -d '{"model":"...","messages":[...],"stream":true,"think":true}'`
   also shows real-time output (positive control).
4. Check server logs: `Thinking chunk: len=X, preview=...` should appear at regular intervals during generation,
   not clustered at the end.

### Lesson

> **`HttpClient.post()` is a convenience function that always buffers, it is unsuitable for streaming responses.**
> For streaming, you must use `preparePost().execute {}` (or `prepareGet().execute {}` for GET). The `bodyAsChannel()`
> function is only truly streaming when called inside the `execute {}` scope. Outside it, `bodyAsChannel()` is a
> buffer reader.
>
> The fingerprint of this bug: `curl -N` works, but your code doesn't. The fix is always the same: `post()` → `execute {}`.
>
> This bug took half a day of debugging; Ollama logs showed token generation, `curl -N` confirmed Ollama was
> streaming correctly, but the Ktor client still received everything at once. The Ktor documentation's "Streaming data"
> section is the key reference, but it's easy to miss when you're focused on the `bodyAsChannel()` function name.

## 18. Inline Code Chip Height — Unreliable Dynamic Measurement vs Formula-Based Approach

> **Date**: 2026-08-24 (3:00 AM, 4 hours of debugging, 12+ iterations)
> **Files**: `BlockRenderer.kt`, `GradumMarkdown.kt`, `Styling.kt`
> **Key insight**: `TextLayoutResult.getLineTop()/getLineBottom()` return different heights for different lines
> within the same `Text` composable. A formula `fontSize * multiplier` bypasses the unreliable measurement entirely.

### Symptoms

- Long inline code chips that wrap to a second line show the second line's chip "stuck to the top", the background
  chip is positioned at the top of the line instead of vertically centered.
- Different lines within the same `Text` composable produce different chip heights, even though they use the same
  `TextStyle` and `fontSize`.
- The same formula applied in two different rendering paths (`BlockRenderer.kt` and `GradumMarkdown.kt`) produces
  different pixel heights (e.g., 37px vs 45px), because each path uses a different `TextStyle` with different
  `lineHeight` values.

### Root Cause

The root cause is a chain of compounding issues:

1. **`TextLayoutResult.getLineTop(line)` / `getLineBottom(line)` are not reliable for chip backgrounds.**
   These functions return the line's bounding box within the layout, which can vary between lines due to how Compose
   distributes leading (line spacing) across lines. The difference `lineBottom - lineTop` is not guaranteed to be
   consistent across lines.

2. **Each `Text` composable is independent.** A `FlowRow` containing multiple `Text` segments means each segment
   has its own `TextLayoutResult` and its own coordinate system. The chip heights can differ between segments
   if their `TextStyle` differs (even subtly).

3. **`TextStyle.lineHeight` varies between rendering paths.** `BlockRenderer.kt` generates its `TextStyle` from
   Jewel's Markdown styling pipeline, while `GradumMarkdown.kt` uses a separate resolved style. The same
   `BODY_LINE_HEIGHT_MULTIPLIER` can produce different actual heights because the base `fontSize` differs.

4. **Glyph-level bounding box (`getBoundingBox`) is even worse**, it measures per-character and is highly
   font-dependent, producing inconsistent results for CJK vs Latin characters, punctuation, and whitespace.

### Fix

Replace **dynamic measurement** with a **formula-based approach**:

```kotlin
// Styling.kt — named constants
internal const val INLINE_CODE_CHIP_HEIGHT_MULTIPLIER: Float = 1.2f
internal const val INLINE_CODE_CHIP_BASELINE_RATIO: Float = 0.75f

// In drawWithContent — chip height calculation
val chipHeightPx = with(density) { resolvedStyle.fontSize.toPx() * INLINE_CODE_CHIP_HEIGHT_MULTIPLIER }

// Position relative to baseline, not line center
val baseline = layoutResult.getLineBaseline(line)
val chipTop = baseline - chipHeightPx * INLINE_CODE_CHIP_BASELINE_RATIO
```

Key design decisions:

- **`fontSize` as the base**: Unlike `lineHeight`, `fontSize` is a stable, well-defined typographic unit. It does not
  vary between rendering paths or between lines within the same `Text`.
- **`INLINE_CODE_CHIP_HEIGHT_MULTIPLIER = 1.2f`**: The chip is 20% taller than the font size, providing enough
  padding around the text glyphs without being as tall as the full line height (which would be ~1.3x).
- **`INLINE_CODE_CHIP_BASELINE_RATIO = 0.75f`**: 75% of the chip height is above the baseline, 25% below. This
  matches the natural distribution of text glyphs (ascent ≈ 0.8x, descent ≈ 0.2x of font size).
- **Baseline-based positioning**: Using `getLineBaseline(line)` instead of `getLineTop(line)` / `getLineBottom(line)`
  ensures the chip is visually centered on the text, not on the line's bounding box (which includes leading).

### Verification

- All inline code chips now have the same height regardless of which line in the `Text` composable they appear on.
- `BlockRenderer.kt` and `GradumMarkdown.kt` produce identical chip heights for the same `fontSize`.
- The chip height scales correctly with the surrounding text's font size (no hard-coded pixel values).
- Long inline code that wraps to multiple lines shows consistent chip backgrounds on every line.
- No visual "stuck to top" or "stuck to baseline" artifacts.

### Lesson

> **Do not trust `TextLayoutResult.getLineTop()/getLineBottom()` for measuring chip/background heights.**
> These values are line-positioning coordinates within the text layout engine, not design-specified heights.
> They can vary between lines of the same `Text` composable due to leading distribution.
>
> When you need a consistent visual element that scales with text, use a formula based on `fontSize`:
> `chipHeight = fontSize * multiplier`. This is deterministic, controllable, and automatically adapts to
> font size changes.
>
> For vertical positioning, use `getLineBaseline(line)` as the reference point rather than line center.
> Text glyphs are not centered within the line, they sit on the baseline with asymmetric ascent/descent.
> A baseline-relative formula (`chipTop = baseline - chipHeight * ratio`) gives visually correct centering.
>
> This bug took 4 hours of debugging with 12+ iterations. The fingerprint: second-line chips are "stuck to the top"
> or have inconsistent heights. The fix is always the same: formula, not measurement.
