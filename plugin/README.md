# Gradum IntelliJ IDEA Plugin

A full-featured chat panel and Git analysis tool window for IntelliJ IDEA, built with Jetpack Compose and Jewel UI.

> \[!WARNING]
> This plugin is built by manually vendoring Jewel/Compose/Skiko JARs from a specific IntelliJ IDEA 2026.2 installation into `plugin/libs/`. It is tied to a single IDE version, uses unsupported classloading tricks, and is not published on JetBrains Marketplace. **Not recommended for production use.** Use at your own risk.

## Prerequisites

| Requirement   | Version           |
| ------------- | ----------------- |
| IntelliJ IDEA | 2026.2 (IU-262.x) |
| JDK           | 25                |
| Gradle        | 8.x (via wrapper) |

## Special Build Setup

The plugin depends on Jewel, Compose for Desktop, and Skiko — all of which are bundled with IntelliJ IDEA 2026.2 but
**not exposed to plugins** through the standard `bundledModule` mechanism. To work around this:

### 1. Copy IDE JARs to `plugin/libs/`

The `plugin/libs/` directory is gitignored. You must manually copy the following JARs from your IntelliJ IDEA 2026.2
installation (`/Applications/IntelliJ IDEA.app/Contents/lib/` on macOS):

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

Create the directory and copy:

```bash
mkdir -p plugin/libs
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.libraries.compose.*.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.libraries.skiko.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.platform.compose.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.platform.compose.markdown.jar plugin/libs/
cp /Applications/IntelliJ\ IDEA.app/Contents/lib/intellij.platform.jewel.*.jar plugin/libs/
```

### 2. Why Not `bundledModule`?

The IDE 2026.2 places all Jewel/Compose/Skiko modules under the `JetBrains` namespace. The plugin `<depends>` element
only accepts `com.intellij.modules.*` IDs — the IDE rejects any `intellij.platform.jewel.*` entry with "requires
plugin ... to be installed" at load time. Direct `implementation(files(...))` is the only reliable way to put these
classes on the plugin's classpath.

### 3. Why `compileOnly` for kotlinx Coroutines and Serialization?

`kotlinx-coroutines-core` and `kotlinx-serialization-json` are declared as `compileOnly` dependencies. At runtime, the
plugin's `PluginClassLoader` delegates to the IDE's `PathClassLoader`, which loads the IDE's own copies. This avoids
`LinkageError: loader constraint violation` on `CoroutineScope` (an interface) and the analogous `KSerializer` interface
issue.

## Build

```bash
# Build the plugin zip
./gradlew :plugin:buildPlugin

# Output: plugin/build/distributions/gradum-*.zip

# Run detekt (code quality check)
./gradlew :plugin:detekt
```

## Install

1. Open IntelliJ IDEA
2. `Settings > Plugins > Install Plugin from Disk...`
3. Select `plugin/build/distributions/gradum-*.zip`
4. Restart the IDE

## Project Structure

```
plugin/
  build.gradle.kts          # Plugin build configuration
  gradle.properties         # Plugin-specific Gradle properties
  libs/                     # Vendored IDE JARs (gitignored)
  src/
    main/
      kotlin/gradum/idea/   # Plugin source code
      resources/            # plugin.xml, icons, i18n, legal notices
    test/
      kotlin/gradum/idea/   # Plugin tests
```

## License

MIT License, see the [LICENSE](../LICENSE) file at the main project root.
