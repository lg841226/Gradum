import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
  kotlin("jvm") version "2.3.0"
  kotlin("plugin.serialization") version "2.3.0"
  id("io.ktor.plugin") version "3.0.3"
}

group = "com.gradum"
version = providers.gradleProperty("gradum.version").getOrElse("1.0.2")

val generateBuildConfig = tasks.register("generateBuildConfig") {
  description = "Generate a BuildConfig.kt that exposes the project version at compile time"
  val outputDir = layout.buildDirectory.dir("generated/source/buildConfig/main/kotlin")
  outputs.dir(outputDir)
  doLast {
    val file: File = outputDir.get().file("gradum/BuildConfig.kt").asFile
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

// The editor's resources folder also holds the IDE's own `.idea` directory,
// which is gitignored but would still be packaged into the jar. Keep it out.
tasks.named<ProcessResources>("processResources") {
  exclude("skills-editor/.idea/**")
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
 * is fine: the Gradum server still reads the env-var fallback
 * chain when the value is missing or empty. Hosted providers
 * themselves (currently just Zhipu) are first-class in
 * ModelIdentity and need no JSON config.
 */
val gradumOpenAiApiKey: String =
  (project.findProperty("gradum.openAiApiKey") as? String).orEmpty()

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
  isIgnoreExitValue = true

  jvmArgs(
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
  )
  if (gradumOpenAiApiKey.isNotBlank()) {
    environment("GRADUM_OPENAI_API_KEY", gradumOpenAiApiKey)
  }
}

/**
 * Swing tail over the debug NDJSON dump (`gradum.logging.EventStreamDump`).
 * Dev-only: it never runs as part of the server, it just reads the temp log
 * the server writes when `debug.eventDump` is on in `~/.gradum/settings.json`.
 */
tasks.register<JavaExec>("eventMonitor") {
  group = "gradum"
  description = "Open the Swing monitor that pretty-prints the debug NDJSON event dump"
  mainClass.set("gradum.runtime.EventStreamMonitorKt")
  classpath = sourceSets["main"].runtimeClasspath
  jvmArgs("-Dfile.encoding=UTF-8")
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("io.ktor:ktor-server-core:3.0.3")
  implementation("io.ktor:ktor-server-netty:3.0.3")
  implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
  implementation("io.ktor:ktor-client-core:3.0.3")
  implementation("io.ktor:ktor-client-cio:3.0.3")
  implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
  implementation("org.jline:jline:3.30.17")
  implementation("org.xerial:sqlite-jdbc:3.50.3.0")
  implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0")
  implementation("ch.qos.logback:logback-classic:1.6.3")
  implementation("com.formdev:flatlaf:3.7.2")
  testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.3")
  testImplementation("io.ktor:ktor-server-test-host:3.0.3")
  testImplementation("io.ktor:ktor-client-mock:3.0.3")
  testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
  testImplementation("io.mockk:mockk:1.13.13")

  constraints {
    implementation("io.netty:netty-codec-http2:4.2.15.Final") {
      because("CVE-2025-55163 / CVE-2025-58057 / CVE-2026-33871, HTTP/2 DoS and decompression OOM")
    }
  }
}

tasks.test {
  useJUnitPlatform()
}

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

private val serverMinimalRuntimeDir: File =
  layout.buildDirectory.dir("minimal-runtime").get().asFile

private fun selfContainedServerExecutable(appName: String, destDir: File): File {
  val osName: String = System.getProperty("os.name").lowercase()
  return when {
    osName.contains("mac") -> destDir.resolve("$appName.app/Contents/MacOS/$appName")
    osName.contains("win") -> destDir.resolve("$appName/$appName.exe")
    else -> destDir.resolve("bin/$appName")
  }
}

/**
 * On macOS the packaged app opens the Swing Runtime Window on start, which
 * shows the server logs — no Terminal wrapper is needed to keep logs visible.
 */

private val SQLITE_NATIVE_PREFIX = "org/sqlite/native/"

/**
 * sqlite-jdbc bundles a native library for every OS/arch it supports
 * (Linux, Linux-Musl, Linux-Android, FreeBSD, Windows, Mac). jpackage cannot
 * cross-compile, so each package targets one OS anyway: rewrite the staged
 * fat jar in place and drop the natives of every other platform (~10 MB).
 */
private fun sqliteNativePlatformsToKeep(): Set<String> {
  val osName: String = System.getProperty("os.name").lowercase()
  return when {
    osName.contains("mac") -> setOf("Mac")
    osName.contains("win") -> setOf("Windows")
    else -> setOf("Linux", "Linux-Musl")
  }
}

private fun stripForeignSqliteNatives(jarFile: File) {
  val keepPlatforms: Set<String> = sqliteNativePlatformsToKeep()
  val tempFile: File = File(jarFile.parentFile, "${jarFile.name}.stripped")
  ZipInputStream(jarFile.inputStream().buffered()).use { input ->
    ZipOutputStream(tempFile.outputStream().buffered()).use { output ->
      var entry: ZipEntry? = input.nextEntry
      while (entry != null) {
        val platformSegment: String = entry.name.removePrefix(SQLITE_NATIVE_PREFIX).substringBefore('/', missingDelimiterValue = "")
        val isForeignNative: Boolean =
          entry.name.startsWith(SQLITE_NATIVE_PREFIX) && platformSegment.isNotEmpty() && platformSegment !in keepPlatforms
        if (!isForeignNative) {
          output.putNextEntry(ZipEntry(entry))
          if (!entry.isDirectory) input.copyTo(output)
          output.closeEntry()
        }
        entry = input.nextEntry
      }
    }
  }
  jarFile.delete()
  tempFile.renameTo(jarFile)
}

private fun waitForServerHealth(baseUrl: String, timeoutSeconds: Long) {
  val deadline: Long = System.currentTimeMillis() + timeoutSeconds * 1000
  while (System.currentTimeMillis() < deadline) {
    try {
      val connection: HttpURLConnection =
        URI("$baseUrl/health").toURL().openConnection() as HttpURLConnection
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

private fun runAndCapture(command: List<String>): String {
  val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
  val output: String = process.inputStream.bufferedReader().readText().trim()
  val exitCode: Int = process.waitFor()
  if (exitCode != 0) {
    throw GradleException("Command failed ($exitCode): ${command.joinToString(" ")}\n$output")
  }
  return output
}

/**
 * Builds a stripped-down JVM runtime with `jlink`, keeping only the JDK
 * modules the fat jar actually needs (discovered via `jdeps`). Using it as
 * jpackage's `--runtime-image` shrinks the bundled runtime dramatically,
 * roughly 165 MB down to ~40 MB, without losing any functionality.
 */
tasks.register("buildMinimalRuntime") {
  group = "distribution"
  description = "Build a minimal JVM runtime (jlink) containing only the server's required modules"
  dependsOn("buildFatJar")
  outputs.upToDateWhen { false }
  outputs.dir(serverMinimalRuntimeDir)
  doLast {
    val javaHome: String = System.getProperty("java.home")
    val modules: String =
      runAndCapture(
        listOf("$javaHome/bin/jdeps", "--ignore-missing-deps", "--print-module-deps", serverFatJarFile.absolutePath)
      ).replace(Regex("\\s"), "")

    val requiredModules: String =
      if ("jdk.zipfs" in modules.split(",")) modules else "$modules,jdk.zipfs"
    serverMinimalRuntimeDir.deleteRecursively()
    runAndCapture(
      listOf(
        "$javaHome/bin/jlink",
        "--module-path", "$javaHome/jmods",
        "--add-modules", requiredModules,
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--compress", "zip-6",
        "--output", serverMinimalRuntimeDir.absolutePath
      )
    )
    serverMinimalRuntimeDir.resolve("lib/ct.sym").takeIf { ctSym -> ctSym.isFile }?.delete()
    logger.lifecycle("Built minimal runtime: $requiredModules -> ${serverMinimalRuntimeDir.absolutePath}")
  }
}

/**
 * Self-contained Gradum server executable via `jpackage` (bundled JRE).
 *
 * The output is an "app image": a folder with the launcher plus a private
 * JVM runtime, so end-user machines that do NOT have Java installed can
 * still run the server. Run per-target-OS: jpackage cannot cross-compile.
 *
 *   ./gradlew serverPackage
 *
 * macOS:  build/gradum-server/GradumServer.app/Contents/MacOS/GradumServer
 * Windows: build/gradum-server/GradumServer/GradumServer.exe
 * Linux:  build/gradum-server/bin/GradumServer
 *
 * Icons are optional and read from `packaging/<os>/`: `GradumServer.icns`
 * (macOS) or `GradumServer.ico` (Windows). On Windows the launcher is built
 * with `--win-console`, and stdio is left at the JVM default (the console's
 * native encoding), so the server logs stay visible and readable instead of
 * turning into mojibake on CP936 consoles.
 */
tasks.register("serverPackage", Exec::class.java) {
  group = "distribution"
  description = "Package the Gradum server as a self-contained executable (jpackage app-image, bundled JRE)"
  dependsOn("buildFatJar", "buildMinimalRuntime")

  val appName = "GradumServer"
  val jpackageBin: String = System.getProperty("java.home") + File.separator + "bin" + File.separator + "jpackage"
  val stagingDir: File = layout.buildDirectory.dir("server-package/input").get().asFile
  val destDir: File = layout.buildDirectory.dir("gradum-server").get().asFile
  val packagedFatJar: File = stagingDir.resolve("gradum-server.jar")

  doFirst {
    stagingDir.mkdirs()
    serverFatJarFile.copyTo(packagedFatJar, overwrite = true)
    stripForeignSqliteNatives(packagedFatJar)
    logger.lifecycle("Staged fat jar with target-OS sqlite natives only: ${packagedFatJar.length() / 1024 / 1024} MB")

    destDir.deleteRecursively()

    val osName: String = System.getProperty("os.name").lowercase()
    val jpackageVersion: String =
      project.version.toString().replaceFirst(Regex("[-].*$"), "")
    val jpackageArgs: MutableList<String> = mutableListOf(
      jpackageBin,
      "--type", "app-image",
      "--name", appName,
      "--app-version", jpackageVersion,
      "--vendor", "Gradum",
      "--copyright", "Copyright (c) 2026 Gradum Authors"
    )
    val platformIcon: File? =
      when {
        osName.contains("mac") -> rootDir.resolve("packaging/mac/GradumServer.icns")
        osName.contains("win") -> rootDir.resolve("packaging/win/GradumServer.ico")
        else -> null
      }
    if (platformIcon != null && platformIcon.isFile) {
      jpackageArgs += "--icon"
      jpackageArgs += platformIcon.absolutePath
    }
    if (osName.contains("win")) {
      jpackageArgs += "--win-console"
    }
    if (serverMinimalRuntimeDir.isDirectory) {
      jpackageArgs += "--runtime-image"
      jpackageArgs += serverMinimalRuntimeDir.absolutePath
    }
    jpackageArgs += listOf(
      "--input", stagingDir.absolutePath,
      "--main-jar", packagedFatJar.name,
      "--main-class", "gradum.server.MainKt",
      "--java-options", "-Xmx2048m",
      "--java-options", "-Xms512m",
      "--dest", destDir.absolutePath
    )
    commandLine(jpackageArgs)
  }

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
