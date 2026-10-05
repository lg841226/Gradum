plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.0"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
  id("org.jetbrains.intellij.platform") version "2.16.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
  id("org.jetbrains.changelog") version "2.3.0"
}

group = "com.gradum.idea"
version = "1.0.3"

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
    composeUI()
  }

  // Jewel must be shipped as one consistent set, for two reasons. First, the
  // Compose resources runtime (`org.jetbrains.compose.resources`) lives inside
  // `intellij.platform.jewel.ui` and resolves resources through its own
  // classloader, so it must run on the plugin classloader to see resources
  // inside plugin jars (e.g. the LaTeX fonts bundled in latex-renderer).
  // Second, the Jewel theme is exposed through CompositionLocals, so the theme
  // provider (`SwingBridgeTheme`, in jewel.ideLafBridge) and the readers must
  // come from the same classloader; mixing plugin and platform copies leaves
  // the locals unset ("No LinkStyle provided").
  implementation(files("libs/intellij.platform.jewel.foundation.jar"))
  implementation(files("libs/intellij.platform.jewel.ui.jar"))
  implementation(files("libs/intellij.platform.jewel.ideLafBridge.jar"))

  // Jewel is compiled against the Compose runtime, so Compose has to sit on the
  // same classloader as Jewel. If it does not, a Jewel class calling into
  // Compose resolves `androidx.compose.*` through the platform loader while its
  // own signature types (e.g. `kotlin.jvm.internal.IntCompanionObject`) resolve
  // through the plugin loader, and the JVM refuses to link the two copies
  // ("loader constraint violation"). `composeUI()` still provides the
  // `com.intellij.modules.compose` dependency; these jars provide the classes.
  implementation(files("libs/intellij.libraries.compose.runtime.desktop.jar"))
  implementation(files("libs/intellij.libraries.compose.foundation.desktop.jar"))

  // Jewel's markdown modules are internal content modules of IDEA CORE: they
  // are not exposed through `com.intellij.modules.compose`, they can only be
  // declared as `<depends>` if registered as `<module value="...">` (which
  // they are not), and the platform ships no plugin gateway for them. So the
  // platform cannot provide them to a third-party plugin at runtime and they
  // must be shipped with the plugin.
  implementation(files("libs/intellij.platform.jewel.markdown.core.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.ideLafBridgeStyling.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.autolink.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmStrikethrough.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmAlerts.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.gfmTables.jar"))
  implementation(files("libs/intellij.platform.jewel.markdown.extensions.images.jar"))
  implementation(files("libs/intellij.platform.compose.markdown.jar"))

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
      <h3>1.0.3</h3>
      <ul>
        <li>Chat with Ollama and any OpenAI-compatible server.</li>
        <li>Agentic tool calling: file, search, and process tools.</li>
        <li>Project-aware context with per-session history.</li>
        <li>MCP server support.</li>
        <li>Git history analysis tool window.</li>
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
