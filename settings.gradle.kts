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
