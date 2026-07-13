/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * build.gradle.kts  2026-06-30 23:35:47 Changed by gwy
 */

import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask

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
  maven("https://packages.jetbrains.team/maven/p/kpm/public/")
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    create("IU", "2026.1.3")
    bundledPlugin("com.intellij.modules.platform")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }

  implementation(files("libs/intellij.libraries.compose.foundation.desktop.jar"))
  implementation(files("libs/intellij.libraries.compose.runtime.desktop.jar"))
  implementation(files("libs/intellij.libraries.skiko.jar"))
  implementation(files("libs/intellij.platform.compose.jar"))
  implementation(files("libs/intellij.platform.jewel.foundation.jar"))
  implementation(files("libs/intellij.platform.jewel.ui.jar"))
  implementation(files("libs/intellij.platform.jewel.ideLafBridge.jar"))

  implementation(files("libs/intellij.platform.jewel.markdown.core.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.ideLafBridgeStyling.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.autolink.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmAlerts.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmTables.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.images.jar"))
  implementation(files("libs/intellij.platform.compose.markdown.jar"))

  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  testImplementation("junit:junit:4.13.2")
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
    changeNotes = """N/A""".trimIndent()
  }
}

tasks {
  withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
  }
}

tasks.buildSearchableOptions {
  enabled = false
}

tasks.prepareJarSearchableOptions {
  enabled = false
}

// Some Jewel Markdown extensions (autolink, gfmStrikethrough, gfmAlerts,
// gfmTables, images) live in `plugin/libs/` because the IntelliJ Platform
// jars we compile against are not all on the platform classpath at
// plugin-load time. Bundling them into the produced `composedJar` keeps
// the plugin self-contained and avoids `NoClassDefFoundError` at runtime.
tasks.named("composedJar", ComposedJarTask::class.java) {
  val bundledJars: List<File> = listOf(
    "intellij.platform.jewel.markdown.core.jar",
    "intellij.platform.jewel.markdown.ideLafBridgeStyling.jar",
    "intellij.platform.jewel.markdown.extensions.autolink.jar",
    "intellij.platform.jewel.markdown.extensions.gfmAlerts.jar",
    "intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar",
    "intellij.platform.jewel.markdown.extensions.gfmTables.jar",
    "intellij.platform.jewel.markdown.extensions.images.jar",
    "intellij.platform.jewel.foundation.jar",
    "intellij.platform.jewel.ui.jar",
    "intellij.platform.jewel.ideLafBridge.jar",
    "intellij.platform.compose.markdown.jar",
  ).map { jarName: String -> file("libs/$jarName") }

  from(bundledJars)
}

// --- Lint configuration --------------------------------------------------
//
// detekt is applied here (in addition to the root `build.gradle.kts`)
// so the IntelliJ plugin module is linted as part of `gradlew detekt`.
// ktlint is applied via the root's `subprojects {}` block.
//
// Both linters share the same rule files at the repository root:
//   - `config/detekt/detekt.yml`
//   - `config/detekt/baseline.xml`
//   - `config/ktlint/baseline.xml`
//   - `.editorconfig` (ktlint reads this)

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
