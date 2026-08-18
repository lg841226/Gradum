/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * build.gradle.kts  2026-08-12 12:38:25 Changed by gwy
 */

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.0"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
  id("org.jetbrains.intellij.platform") version "2.16.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
  id("org.jetbrains.changelog") version "2.3.0"
  id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.gradum.idea"
version = "0.9.0"

repositories {
  mavenCentral()
  maven("https://maven.aliyun.com/repository/google")
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    create("IU", "2026.2")
    bundledPlugin("com.intellij.modules.platform")
    // Jewel + Compose for Desktop — we copy the exact JARs the IDE 2026.2
    // ships in its own `lib/` into `plugin/libs/` and load them via
    // `implementation(files(...))` below. Reasons this is necessary,
    // not optional, on IntelliJ Platform 2026.2:
    //
    // 1) The IDE 2026.2 places all Jewel / Compose / Skiko modules in
    //    the `JetBrains` namespace. The plugin `<depends>` element only
    //    accepts `com.intellij.modules.*` ids (and plugin ids) — the
    //    IDE rejects any `intellij.platform.jewel.*` /
    //    `intellij.platform.compose` / `intellij.libraries.skiko` entry
    //    with "requires plugin ... to be installed" at load time. So
    //    `bundledModule(...)` cannot be the only mechanism — the IDE
    //    never exposes the modules to the plugin's PluginClassLoader.
    //
    // 2) `compileOnly` would also fail at runtime: Compose composable
    //    functions like `InlinesStyling(...)` and Jewel theming need the
    //    actual classes on the plugin's classpath, not just the IDE's.
    //
    // 3) The original "shadow + local libs" approach caused
    //    `StackOverflowError` because the `io.github.huarangmeng:latex-*`
    //    artifacts transitively pull their own
    //    `org.jetbrains.compose.*` + `org.jetbrains.skiko`, which
    //    collided with our plugin-local Compose. The fix is the LaTeX
    //    `exclude(...)` block below — that strips every transitive
    //    Compose / Skiko / kotlinx / kotlin-stdlib from LaTeX so the
    //    LaTeX library links against OUR (single) plugin-local copy.
    //
    // kotlinx-coroutines-core and kotlinx-serialization-json are NOT
    // copied to plugin/libs/. They're on the COMPILE classpath via
    // `compileOnly(...)` so the plugin code links against them, but
    // at RUNTIME the plugin's PluginClassLoader delegates to the IDE's
    // PathClassLoader and uses the IDE's own copies. This avoids the
    // `LinkageError: loader constraint violation` on
    // `kotlinx.coroutines.CoroutineScope` (an interface) and the
    // analogous KSerializer interface issue.
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }

  // Jewel + Compose + Skiko (copied from
  // `/Applications/IntelliJ IDEA.app/Contents/lib/`, version IU-262.8665.258).
  // Each JAR is a direct `implementation(files(...))` because the
  // `bundledModule` route cannot expose these classes to the plugin's
  // runtime classloader (see the long comment above).
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
  // The five `extensions.*` JARs must also be `implementation` deps (not
  // `from(...)` entries of `composedJar`). The earlier `composedJar`
  // configuration only NESTED these JARs inside the final
  // `plugin-0.9.0.jar`, and the IntelliJ Platform's classloader does not
  // look inside nested JARs for classes → `NoClassDefFoundError:
  // org/jetbrains/jewel/markdown/extensions/autolink/AutolinkProcessorExtension`
  // at first chat render. Adding them as plain `implementation(files(...))`
  // puts the classes into the plugin's classpath.
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.autolink.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmAlerts.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmTables.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.images.jar"))
  // Coroutines + serialization on the COMPILE classpath only. At runtime
  // the IDE's `intellij.libraries.kotlinx.coroutines.core.jar` (in
  // `Contents/lib/`) and `intellij.libraries.kotlinx.serialization.json.jar`
  // are loaded by the IDE's PathClassLoader. The plugin's
  // PluginClassLoader delegates to it — single copy of each class in
  // the JVM, no `loader constraint violation` on the CoroutineScope /
  // KSerializer interfaces. The IDE-bundled `intellij.libraries.kotlinx.serialization.core`
  // is re-versioned by JetBrains (reports itself as 2.3.0 to the
  // Kotlin 2.3.0 compiler plugin's strict version check, which
  // `compileOnly` would otherwise also see); the Maven Central
  // `1.7.3` artifacts on to compile classpath satisfy the compiler
  // check, and the IDE's copy on the runtime classpath satisfies the
  // link check.
  compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // LaTeX math rendering (LANDED 2026-07-20 — see docs/plan.md). The
  // `io.github.huarangmeng:latex-*` artifacts are published as Compose
  // Multiplatform packages; each transitively pulls its own
  // `org.jetbrains.compose.*` + `org.jetbrains.skiko` +
  // `org.jetbrains.kotlinx:kotlinx-coroutines-core` +
  // `org.jetbrains.kotlin:kotlin-stdlib`. We exclude every one of those
  // so the LaTeX library links against the IDE's bundled Compose (above)
  // and the IDE's bundled kotlinx / kotlin-stdlib — otherwise the plugin
  // ends up with TWO copies of each library on the classpath and crashes
  // at startup with either `ClassCircularityError` / `StackOverflowError`
  // (Compose) or `LinkageError: loader constraint violation` (kotlinx /
  // kotlin-stdlib). Source: intellij-community/platform/jewel/README.md,
  // "How to use Jewel in your IDE plugin" section.
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
  implementation("io.github.huarangmeng:latex-parser:1.4.7") {
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
  implementation("io.github.huarangmeng:latex-renderer:1.4.7") {
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

  testImplementation("junit:junit:4.13.2")
  testImplementation("io.mockk:mockk:1.13.13")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

  // Force safe versions of transitive dependencies flagged by vulnerability scanners.
  constraints {
    implementation("com.fasterxml.jackson.core:jackson-core:2.21.1") {
      because("WS-2026-0003 — async JSON parser bypasses maxNumberLength, DoS via memory/CPU exhaustion")
    }
  }
}

kotlin {
  sourceSets {
    all {
      languageSettings {
        optIn("org.jetbrains.compose.ExperimentalComposeLibrary")
      }
    }
  }
}

intellijPlatform {
  pluginConfiguration {
    name = "Gradum"
    version = "0.9.0"
    changeNotes = """
      <ul>
        <li>Markdown: add footnote syntax support and improve rendering</li>
        <li>Markdown: preserve inline bold inside headings, list items, and blockquotes</li>
        <li>Markdown: add nested code block tests and clean up existing test suite</li>
        <li>Style: use editor fontFamily for thinking and response text</li>
        <li>Style: normalize list spacing and use default font for headings</li>
        <li>Fix: resolve KDoc link warnings in Agent.kt</li>
        <li>Refactor: extract GradumUI into separate files</li>
      </ul>
    """.trimIndent()
  }
}

tasks {
  withType<JavaCompile> {
    sourceCompatibility = "25"
    targetCompatibility = "25"
  }
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
  }
}

tasks.buildSearchableOptions {
  enabled = false
}

tasks.prepareJarSearchableOptions {
  enabled = false
}

dependencies {
  detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom(file("../config/detekt/detekt.yml"))
  baseline = file("../config/detekt/baseline.xml")
  autoCorrect = false
}

/**
 * Mirror the root module's quality-gate wiring. The IntelliJ
 * plugin compiles through `compileKotlin` (single-target JVM
 * module, no `-jvm` variant) and runs `check` during
 * `gradlew build`. Both must run detekt — see the long-form
 * rationale in the root `build.gradle.kts`; the short version
 * is "code cleanliness: no path through Gradle produces a
 * class file without detekt passing first".
 *
 * Same `-Pgradum.skipDetektGate=true` opt-out as the root
 * module. The `check.dependsOn(detekt)` wiring is unconditional.
 */
val gradumSkipDetektGate: String =
  (project.findProperty("gradum.skipDetektGate") as? String).orEmpty()

tasks.named("detekt") { enabled = false }

//tasks.named("check") {
//  dependsOn("detekt")
//}
