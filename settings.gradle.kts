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

include("plugin")
