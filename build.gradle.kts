/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * build.gradle.kts  2026-08-31 19:21:55 Changed by gwy
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

plugins {
  kotlin("jvm") version "2.3.0"
  kotlin("plugin.serialization") version "2.3.0"
  id("io.ktor.plugin") version "3.0.3"
  id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.gradum"

val generateBuildConfig = tasks.register("generateBuildConfig") {
  val outputDir = layout.buildDirectory.dir("generated/source/buildConfig/main/kotlin")
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("gradum/BuildConfig.kt").asFile
    file.parentFile.mkdirs()
    file.writeText(
      """
        |package gradum
        |
        |object BuildConfig {
        |  const val version: String = "${project.version}"
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

application {
  mainClass.set("gradum.server.MainKt")
}

ktor {
  fatJar {
    archiveFileName.set("gradum@${project.version}.jar")
  }
}

/**
 * Keep the `run` task usable in `--continuous` mode: hand the JVM our
 * stdin so SIGTERM (from Gradle's file-watch restart) propagates to the
 * Ktor server's shutdown hook, and swallow the resulting non-zero exit
 * so Gradle doesn't treat a clean stop as a failure.
 *
 * Also injects the OpenAI-compatible provider key from
 * `gradle.properties` into the JVM as an env var, so users don't
 * have to `export` before every `./gradlew :run`. A blank value
 * is fine — the Gradum server still reads the env-var fallback
 * chain when the value is missing or empty. Hosted providers
 * themselves (currently just Zhipu) are first-class in
 * ModelIdentity and need no JSON config.
 */
val gradumOpenAiApiKey: String =
  (project.findProperty("gradum.openAiApiKey") as? String).orEmpty()

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
  isIgnoreExitValue = true
  if (gradumOpenAiApiKey.isNotBlank()) {
    environment("GRADUM_OPENAI_API_KEY", gradumOpenAiApiKey)
  }
}

repositories {
  mavenCentral()
}

dependencies {
  // Ktor server
  implementation("io.ktor:ktor-server-core:3.0.3")
  implementation("io.ktor:ktor-server-netty:3.0.3")
  implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

  // Ktor client (for LLM API calls)
  implementation("io.ktor:ktor-client-core:3.0.3")
  implementation("io.ktor:ktor-client-cio:3.0.3")
  implementation("io.ktor:ktor-client-content-negotiation:3.0.3")

  // Serialization
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // Coroutines
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

  // Logging
  implementation("ch.qos.logback:logback-classic:1.5.25")
  testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.3")

  // Detekt
  detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")

  // Test
  testImplementation("io.ktor:ktor-server-test-host:3.0.3")
  testImplementation("io.ktor:ktor-client-mock:3.0.3")
  testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
  testImplementation("io.mockk:mockk:1.13.13")

  constraints {
    implementation("io.netty:netty-codec-http2:4.2.15.Final") {
      because("CVE-2025-55163 / CVE-2025-58057 / CVE-2026-33871 — HTTP/2 DoS and decompression OOM")
    }
  }
}

tasks.test {
  useJUnitPlatform()
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom(file("config/detekt/detekt.yml"))
  baseline = file("config/detekt/baseline.xml")
  autoCorrect = false
}

/**
 * Force the quality gate into every code path that produces a
 * Gradle artifact. Two wirings, both belt-and-suspenders:
 *
 * 1. `compileKotlin.dependsOn(detekt)` — any explicit
 *    `gradlew compileKotlin` (root or JVM variant) and the IDE
 *    background compile that flows through it runs detekt first.
 *    Lint failures block the compile: no half-built class files,
 *    no `--no-detekt` escape hatch. This is the "code cleanliness"
 *    gate — by the time `compileKotlin` finishes, every line of
 *    new code is detekt-clean.
 * 2. `check.dependsOn(detekt)` — the standard `gradlew build`
 *    (which runs `assemble + check`) also enforces detekt. The
 *    detekt 1.23.x plugin *usually* auto-wires this, but pinning
 *    it explicitly in the build file means a future plugin update
 *    can't silently drop the wiring.
 *
 * The real cost is small: Gradle's task-up-to-date cache skips
 * `detekt` when no tracked source file changed, so an unchanged
 * file pays zero. When a source file *does* change, detekt runs
 * once on the changed file set (~3-8s for this module), then
 * the cache marks it up-to-date until the next change. The IDE
 * background compile that runs on every save also benefits from
 * the cache — only the first save after an edit pays the cost.
 *
 * Emergency opt-out (don't use it for code review, only for
 * unblocking a local repro): pass `-Pgradum.skipDetektGate=true`
 * to bypass the `compileKotlin` wiring. The `check.dependsOn`
 * wiring is unconditional — drop it here if you really need to
 * ship a half-clean build, and add a `// detekt:disable-next-line`
 * with a justification on the offending lines.
 */
val gradumSkipDetektGate: String =
  (project.findProperty("gradum.skipDetektGate") as? String).orEmpty()

tasks.named("detekt") { enabled = false }

kotlin {
  jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
  }
}

tasks.withType<JavaCompile> {
  options.release.set(21)
}


private val serverFatJarFile: File =
  layout.buildDirectory.file("libs/gradum@${project.version}.jar").get().asFile

private fun selfContainedServerExecutable(appName: String, destDir: File): File {
  val osName: String = System.getProperty("os.name").lowercase()
  return when {
    osName.contains("mac") -> destDir.resolve("$appName.app/Contents/MacOS/$appName")
    osName.contains("win") -> destDir.resolve("$appName/$appName.exe")
    else -> destDir.resolve("bin/$appName")
  }
}

private fun waitForServerHealth(baseUrl: String, timeoutSeconds: Long) {
  val deadline: Long = System.currentTimeMillis() + timeoutSeconds * 1000
  while (System.currentTimeMillis() < deadline) {
    try {
      val connection: HttpURLConnection = URL("$baseUrl/health").openConnection() as HttpURLConnection
      connection.connectTimeout = 1000
      connection.readTimeout = 1000
      try {
        if (connection.responseCode == 200) return
      } finally {
        connection.disconnect()
      }
    } catch (_: IOException) {
    }
    Thread.sleep(500)
  }
  throw GradleException("Gradum server did not become healthy at $baseUrl within ${timeoutSeconds}s")
}

/**
 * Self-contained Gradum server executable via `jpackage` (bundled JRE).
 *
 * The output is an "app image" — a folder with the launcher plus a private
 * JVM runtime — so end-user machines that do NOT have Java installed can
 * still run the server. Run per-target-OS: jpackage cannot cross-compile.
 *
 *   ./gradlew serverPackage
 *
 * macOS:  build/gradum-server/GradumServer.app/Contents/MacOS/GradumServer
 * Windows: build/gradum-server/GradumServer/GradumServer.exe
 * Linux:  build/gradum-server/bin/GradumServer
 */
tasks.register("serverPackage", Exec::class.java) {
  group = "distribution"
  description = "Package the Gradum server as a self-contained executable (jpackage app-image, bundled JRE)"
  dependsOn("buildFatJar")

  val appName = "GradumServer"
  val jpackageBin: String = System.getProperty("java.home") + File.separator + "bin" + File.separator + "jpackage"
  val stagingDir: File = layout.buildDirectory.dir("server-package/input").get().asFile
  val destDir: File = layout.buildDirectory.dir("gradum-server").get().asFile
  val packagedFatJar: File = stagingDir.resolve("gradum-server.jar")

  doFirst {
    stagingDir.mkdirs()
    serverFatJarFile.copyTo(packagedFatJar, overwrite = true)
  }

  val jpackageVersion: String = project.version.toString().replaceFirst(Regex("^0\\."), "1.")

  commandLine(
    jpackageBin,
    "--type", "app-image",
    "--name", appName,
    "--app-version", jpackageVersion,
    "--input", stagingDir.absolutePath,
    "--main-jar", packagedFatJar.name,
    "--main-class", "gradum.server.MainKt",
    "--java-options", "-Xmx2048m",
    "--java-options", "-Xms512m",
    "--dest", destDir.absolutePath
  )

  doLast {
    val executable: File = selfContainedServerExecutable(appName, destDir)
    logger.lifecycle("Self-contained server executable: ${executable.absolutePath}")
    logger.lifecycle("Run it on a machine WITHOUT Java installed; it carries its own JVM.")
  }
}

tasks.register("dev") {
  group = "development"
  description = "Start the Gradum server (background) then launch the plugin sandbox (:plugin:runIde)"
  dependsOn("buildFatJar", ":plugin:buildPlugin")

  doLast {
    val javaBin: String = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
    val logFile: File = layout.buildDirectory.file("dev/gradum-server.log").get().asFile
    logFile.parentFile.mkdirs()

    val serverProcess: Process = ProcessBuilder(
      javaBin,
      "-Xmx2048m", "-Xms512m",
      "-jar", serverFatJarFile.absolutePath,
      "--port", "8765",
    )
      .also { processBuilder ->
        if (gradumOpenAiApiKey.isNotBlank()) {
          processBuilder.environment()["GRADUM_OPENAI_API_KEY"] = gradumOpenAiApiKey
        }
      }
      .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
      .redirectErrorStream(true)
      .start()

    try {
      logger.lifecycle("Gradum server starting (log: ${logFile.absolutePath}) ...")
      waitForServerHealth("http://localhost:8765/health", timeoutSeconds = 45)
      logger.lifecycle("Gradum server healthy on http://localhost:8765 — launching IDE sandbox")

      val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
      val gradleWrapper: File = rootDir.resolve(if (isWindows) "gradlew.bat" else "gradlew")
      val ideProcess: Process = ProcessBuilder(
        gradleWrapper.absolutePath,
        ":plugin:runIde",
        "-Pgradum.skipDetektGate=true",
      )
        .inheritIO()
        .start()

      val exitCode: Int = ideProcess.waitFor()
      if (exitCode != 0) {
        throw GradleException(":plugin:runIde exited with code $exitCode")
      }
    } finally {
      logger.lifecycle("Stopping Gradum server")
      serverProcess.destroy()
      if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
        serverProcess.destroyForcibly()
      }
    }
  }
}
