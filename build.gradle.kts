/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * build.gradle.kts  2026-07-05 16:42:55 Changed by gwy
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm") version "2.3.0"
  kotlin("plugin.serialization") version "2.3.0"
  id("io.ktor.plugin") version "3.0.3"
  id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.gradum"
version = "0.9.0"

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
 */
tasks.named<JavaExec>("run") {
  standardInput = System.`in`
  isIgnoreExitValue = true
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
  testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
  testImplementation("io.mockk:mockk:1.13.13")
}

tasks.test {
  useJUnitPlatform()
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom(file("config/detekt/detekt.yml"))
  // Baseline tracks the pre-existing violations so the build doesn't
  // fail on day 1. New code (post-baseline) must produce zero issues.
  baseline = file("config/detekt/baseline.xml")
  // Auto-correction is **off** during the regular `detekt` run —
  // untracked source changes are too easy to lose in a review.
  // Run `gradlew detektFormat` explicitly when you want detekt to
  // rewrite the codebase to match the formatting rules.
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

if (gradumSkipDetektGate != "true") {
  tasks.matching {
    it.name == "compileKotlin" || it.name == "compileKotlinJvm"
  }.configureEach {
    dependsOn("detekt")
  }
}

tasks.named("check") {
  dependsOn("detekt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
  }
}

tasks.withType<JavaCompile> {
  options.release.set(21)
}
