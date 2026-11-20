plugins {
  id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

group = "com.gradum"
version = providers.gradleProperty("gradum.version").getOrElse("1.0.5")

repositories {
  mavenCentral()
  maven("https://maven.aliyun.com/repository/public")
}

dependencies {
  implementation(project(":"))
}

kotlin {
  jvmToolchain(25)
}

tasks.register<JavaExec>("desktopRun") {
  group = "gradum"
  description = "Run the Gradum desktop shell (macOS only)"
  mainClass.set("gradum.desktop.DesktopMainKt")
  classpath = sourceSets["main"].runtimeClasspath

  jvmArgs("-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED")
  onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
}
