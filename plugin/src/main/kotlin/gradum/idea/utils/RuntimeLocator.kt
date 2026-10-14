package gradum.idea.utils

import java.io.File

fun findExecutable(executable: String): Boolean {
  val suffixes: List<String> =
    if (System.getProperty("os.name").lowercase().contains("windows")) {
      listOf("", ".exe", ".cmd", ".bat")
    } else {
      listOf("")
    }
  val pathDirectories: List<String> =
    System.getenv("PATH")?.split(File.pathSeparator) ?: return false
  for (directory: String in pathDirectories) {
    for (suffix: String in suffixes) {
      if (File(directory, executable + suffix).isFile) return true
    }
  }
  return false
}

fun isRuntimeInstalled(): Boolean =
  findExecutable(executable = "gradum") ||
    File(System.getProperty("user.home"), ".gradum").isDirectory
