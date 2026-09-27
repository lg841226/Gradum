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
version = "1.0.2-experimental"

val gitStatsVersion: String = "1.1.0"

repositories {
  mavenCentral()
  maven("https://maven.aliyun.com/repository/public")
  maven("https://maven.aliyun.com/repository/google")
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    create("IU", "2026.2")
    bundledPlugin("com.intellij.modules.platform")
  }

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
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.autolink.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmAlerts.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmTables.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.images.jar"))
  compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

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
      because("WS-2026-0003 async JSON parser bypasses maxNumberLength, DoS via memory/CPU exhaustion")
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
    version = "${project.version}"
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
 * `gradlew build`. Both must run detekt: see the long-form
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

val generateBuildConfig = tasks.register("generateBuildConfig") {
  val outputDir = layout.buildDirectory.dir("generated/source/buildConfig/main/kotlin")
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("gradum/idea/BuildConfig.kt").asFile
    file.parentFile.mkdirs()
    file.writeText(
      """
      |package gradum.idea
      |
      |object BuildConfig {
      |  const val version: String = "${project.version}"
      |  const val gitStatsVersion: String = "$gitStatsVersion"
      |  const val buildTimeMillis: Long = ${System.currentTimeMillis()}
      |}
      """.trimMargin()
    )
  }
}

kotlin.sourceSets.main {
  kotlin.srcDir(generateBuildConfig.map { it.outputs.files.single() })
}

tasks.named("compileKotlin") {
  dependsOn(generateBuildConfig)
}
