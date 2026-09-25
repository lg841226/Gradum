# Gradum IntelliJ IDEA Plugin

A full-featured AI chat panel and Git analysis tool window for IntelliJ IDEA, built with Jetpack Compose
and Jewel UI. This plugin is the IDE frontend of the Gradum ecosystem — it communicates with the
Gradum server to provide AI-powered coding assistance within the IDE.

> [!WARNING]
> This plugin is built by manually vendoring Jewel/Compose/Skiko JARs from a specific IntelliJ IDEA
> 2026.2 installation into `plugin/libs/`. It is tied to a single IDE version, uses unsupported
> classloading tricks, and is not published on JetBrains Marketplace. **Not recommended for production
> use.** Use at your own risk.

## Overview

The plugin adds the following to IntelliJ IDEA:

- **AI Chat Panel** — A persistent chat interface powered by the Gradum server, supporting
  multi-turn conversations, tool calling, sub-agent delegation, and Markdown rendering with
  tables, LaTeX, and task lists.
- **Git Analysis Tool Window** — Automated commit-message and PR analysis powered by AI.
- **Multi-Provider Support** — Seamless switching between cloud-hosted and local LLM providers (OpenAI, Anthropic,
  DeepSeek, Zhipu AI, Ollama, LM Studio, and more).
- **Provider Discovery** — Automatic detection of running local providers via WebSocket probe.
- **Custom Skills** — Extensible tool system that allows the AI to read, search, edit, and run
  commands within the project.
- **Sub-Agent Delegation** — The main AI agent can spawn sub-agents for parallel, isolated tasks
  with independent conversation history.

## Prerequisites

| Requirement   | Version                        |
|---------------|--------------------------------|
| Gradum Server | 1.0.0-experimental (this repo) |
| IntelliJ IDEA | 2026.2 (IU-262.x)              |
| JDK           | 21+ (IDE runtime)              |
| Gradle        | 8.x (via wrapper)              |

> The plugin is a **frontend only** — it requires the Gradum server to be running. See the
> [main project README](../README.md#quick-start) for server setup instructions.

## Special Build Setup

The plugin depends on Jewel, Compose for Desktop, and Skiko — all of which are bundled with IntelliJ
IDEA 2026.2 but **not exposed to plugins** through the standard `bundledModule` mechanism. To work
around this:

### 1. Copy IDE JARs to `plugin/libs/`

The `plugin/libs/` directory is gitignored and **must be created manually**. Without it, the build
will fail with compilation errors. Copy the following JARs from your IntelliJ IDEA 2026.2 installation
(`/Applications/IntelliJ IDEA.app/Contents/lib/` on macOS):

```text
intellij.libraries.compose.foundation.desktop.jar
intellij.libraries.compose.runtime.desktop.jar
intellij.libraries.skiko.jar
intellij.platform.compose.jar
intellij.platform.compose.markdown.jar
intellij.platform.jewel.foundation.jar
intellij.platform.jewel.ui.jar
intellij.platform.jewel.ideLafBridge.jar
intellij.platform.jewel.markdown.core.jar
intellij.platform.jewel.markdown.ideLafBridgeStyling.jar
intellij.platform.jewel.markdown.extensions.autolink.jar
intellij.platform.jewel.markdown.extensions.gfmAlerts.jar
intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar
intellij.platform.jewel.markdown.extensions.gfmTables.jar
intellij.platform.jewel.markdown.extensions.images.jar
```

Run:

```bash
mkdir -p plugin/libs
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.libraries.compose.*.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.libraries.skiko.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.platform.compose.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.platform.compose.markdown.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.platform.jewel.*.jar plugin/libs/
```

### 2. The Version Trick

In `plugin/gradle.properties`, you will find:

```properties
pluginSinceBuild=251
pluginUntilBuild=251.*
```

These values declare IDE compatibility as IntelliJ IDEA 2025.1, **not** 2026.2. This is intentional:

- The plugin uses `<depends>com.intellij.modules.platform</depends>` in `plugin.xml`. JetBrains
  Marketplace and the IDE's plugin verifier only check the `pluginSinceBuild`/`pluginUntilBuild`
  range against this dependency — no actual API compatibility is verified.
- The plugin does **not** use any IntelliJ Platform APIs that changed between 251 and 262. All
  UI is rendered through Jetpack Compose/Jewel, which is loaded from the vendored JARs, not the
  IDE's bundled modules.
- Setting the range to 251.* allows the plugin to load on IDE versions that would otherwise reject
  it. **In practice, the plugin only works on 2026.2** because the vendored JARs are tied to that
  specific build.

### 3. Why Not `bundledModule`?

The IDE 2026.2 places all Jewel/Compose/Skiko modules under the `JetBrains` namespace. The plugin
`<depends>` element only accepts `com.intellij.modules.*` IDs — the IDE rejects any
`intellij.platform.jewel.*` entry with "requires plugin ... to be installed" at load time. Direct
`implementation(files(...))` is the only reliable way to put these classes on the plugin's classpath.

### 4. Why `compileOnly` for kotlinx Coroutines and Serialization?

`kotlinx-coroutines-core` and `kotlinx-serialization-json` are declared as `compileOnly` dependencies.
At runtime, the plugin's `PluginClassLoader` delegates to the IDE's `PathClassLoader`, which loads
the IDE's own copies. This avoids `LinkageError: loader constraint violation` on `CoroutineScope`
(an interface) and the analogous `KSerializer` interface issue.

## Build

```bash
# Build the plugin zip
./gradlew :plugin:buildPlugin

# Output: plugin/build/distributions/gradum-*.zip

# Run code quality checks
./gradlew :plugin:detekt

# Run plugin tests
./gradlew :plugin:test
```

## Install

1. Ensure the Gradum server is running (see [main README](../README.md#quick-start)).
2. Open IntelliJ IDEA.
3. `Settings > Plugins > Install Plugin from Disk...`.
4. Select `plugin/build/distributions/gradum-*.zip`.
5. Restart the IDE.

## Project Structure

```
plugin/
  build.gradle.kts          # Plugin build configuration
  gradle.properties         # Plugin-specific Gradle properties
  libs/                     # Vendored IDE JARs (gitignored, required)
  src/
    main/
      kotlin/gradum/idea/   # Plugin source code
      resources/            # plugin.xml, icons, i18n, legal notices
    test/
      kotlin/gradum/idea/   # Plugin tests
```

## Troubleshooting

### Missing `plugin/libs/` — Build fails with compilation errors

The vendored JARs are gitignored and not included in the repository. Run the copy commands from
[section 1](#1-copy-ide-jars-to-pluginlibs) above.

### LinkageError: loader constraint violation

The IDE is loading its own version of `kotlinx-coroutines-core` or `kotlinx-serialization-json`.
This usually means the `compileOnly` declaration in `build.gradle.kts` is missing or the dependency
is accidentally declared as `implementation`. Verify the dependency block.

### Plugin loads but UI is broken or missing

The vendored JARs may be from a different IDE build. Re-copy them from IntelliJ IDEA 2026.2
exactly, JARs from 2025.x or 2026.1 will not work.

### "requires plugin ... to be installed" error at startup

The plugin is trying to use `<depends>` for a Jewel module. This is not supported by the IDE.
Remove the `<depends>` entry and ensure the library is in `plugin/libs/` with a corresponding
`implementation(files(...))` entry in `build.gradle.kts`.

### Plugin loads but the chat panel is empty

The Gradum server is not running or the plugin cannot connect to it. Start the server first
(see [main README](../README.md#quick-start)) and check the server log for connection errors.

## License

MIT License, see the [LICENSE](../LICENSE) file at the main project root.
