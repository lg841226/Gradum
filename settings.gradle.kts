/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * settings.gradle.kts  2026-08-31 19:21:58 Changed by gwy
 */

pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

rootProject.name = "gradum"

// CI exercises only the server (root) project. The IntelliJ plugin module
// targets JDK 25 and pulls its SDK from an aliyun mirror, both of which are
// unreliable on GitHub's runners, so we allow it to be excluded from the
// build graph entirely instead of trying to exclude it at the task level.
// Pass `-Pgradum.skipPlugin` on the command line to drop `include("plugin")`.
val gradumSkipPlugin: Boolean =
  providers.gradleProperty("gradum.skipPlugin").isPresent

if (!gradumSkipPlugin) {
  include("plugin")
}
