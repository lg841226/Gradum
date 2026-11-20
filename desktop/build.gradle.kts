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

  // The engine exposes the UI route hook as Ktor types; the shell installs
  // its editor routes through them, so Ktor server is on this classpath too.
  implementation("io.ktor:ktor-server-core:3.0.3")

  testImplementation("io.ktor:ktor-server-test-host:3.0.3")
  testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
  useJUnitPlatform()
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
