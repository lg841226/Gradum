pluginManagement {
  repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
    gradlePluginPortal()
  }
}

rootProject.name = "gradum"

val gradumSkipPlugin: Boolean =
  providers.gradleProperty("gradum.skipPlugin").isPresent

if (!gradumSkipPlugin) {
  include("plugin")
}

// Native macOS shell (Kotlin FFM -> AppKit/WebKit). Needs a JDK 22+ toolchain;
// CI stays on 21 and passes -Pgradum.skipDesktop, same pattern as the plugin.
val gradumSkipDesktop: Boolean =
  providers.gradleProperty("gradum.skipDesktop").isPresent

if (!gradumSkipDesktop) {
  include("desktop")
}
