/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SyntaxChecker.kt  2026-07-05 22:56:12 Changed by gwy
 */

package gradum.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger("SyntaxChecker")

data class SyntaxIssue(
  val message: String,
  val severity: String, val line: Int? = null,
  val column: Int? = null, val errorCode: String? = null
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "message" to message,
    "severity" to severity,
    "line" to line,
    "column" to column,
    "errorCode" to errorCode
  )
}


private data class CompileCommand(
  val args: List<String>,
  val env: Map<String, String> = emptyMap()
)


private data class LanguageSyntaxConfig(
  val commands: List<CompileCommand>,
  val outputParser: SyntaxParser
)


private fun interface SyntaxParser {
  fun parse(output: String, filePath: String): List<SyntaxIssue>
}

/**
 * Parses the common "file:line:col: severity: message" output (gcc, clang, javac, kotlinc, ghc, dart).
 * Aggregates multi-line compiler notes into the main error message.
 */
private val standardParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val issues: MutableList<SyntaxIssue> = mutableListOf()
  val pattern = Regex("""^(.+?):(\d+)(?::(\d+))?:\s+(error|warning|note):\s(.+)$""")
  var currentIssue: PendingIssue? = null
  var currentNotes: MutableList<String> = mutableListOf()

  for (line: String in output.lines()) {
    val trimmedLine: String = line.trimEnd()
    if (trimmedLine.isBlank()) continue
    val match: MatchResult? = pattern.matchEntire(trimmedLine)
    if (match != null) {
      currentIssue?.let { issue: PendingIssue ->
        issues.add(issue.toSyntaxIssue(currentNotes))
        currentNotes.clear()
      }
      val severity: String = match.groupValues[4]
      val message: String = match.groupValues[5]
      val matchedLine: Int? = match.groupValues[2].toIntOrNull()
      val matchedColumn: Int? = match.groupValues[3].toIntOrNull()
      val errorCode: String? = when {
        severity == "error" || severity == "warning" -> {
          Regex("""\[(.+?)]""").find(message)?.groupValues?.get(1)
        }

        else -> null
      }
      currentIssue = PendingIssue(severity, message, matchedLine, matchedColumn, errorCode)
    } else if (currentIssue != null) {
      val noteMatch: MatchResult? = Regex("""^\s+(note|warning|error):\s(.+)$""").matchEntire(trimmedLine)
      if (noteMatch != null) {
        currentNotes.add(noteMatch.groupValues[2])
      }
    }
  }
  currentIssue?.let { issue: PendingIssue ->
    issues.add(issue.toSyntaxIssue(currentNotes))
  }
  filterIssuesForFile(issues, filePath)
}


private data class PendingIssue(
  val severity: String, val message: String,
  val line: Int?, val column: Int?,
  val errorCode: String?,
) {
  fun toSyntaxIssue(notes: List<String>): SyntaxIssue {
    val combinedMessage: String = if (notes.isNotEmpty()) {
      "$message\n  note: ${notes.joinToString("\n  note: ")}"
    } else
      message

    return SyntaxIssue(
      message = combinedMessage, severity = severity,
      line = line, column = column, errorCode = errorCode,
    )
  }
}

/**
 * Rustc uses "error[E0425]: message" headers followed by an arrow " --> path:line:col" location line.
 * Extracts error code and coordinates from the structured two-line format.
 */
private val rustcParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val issues: MutableList<SyntaxIssue> = mutableListOf()
  val headerPattern = Regex("""^(error|warning|note)(?:\[([A-Z]\d+)])?:\s(.+)$""")
  val locationPattern = Regex("""^\s+-->\s+(.+?):(\d+):(\d+)$""")
  var currentHeader: SyntaxIssue? = null
  var locationFound = false

  for (line: String in output.lines()) {
    val trimmedLine: String = line.trimEnd()
    val headerMatch: MatchResult? = headerPattern.matchEntire(trimmedLine.trimStart())
    if (headerMatch != null) {
      currentHeader?.let { issues.add(it) }
      locationFound = false

      val severity: String = headerMatch.groupValues[1].lowercase()
      val errorCode: String? = headerMatch.groupValues[2].ifBlank { null }
      val message: String = headerMatch.groupValues[3]

      currentHeader = SyntaxIssue(
        message = message, severity = severity, errorCode = errorCode,
      )
    } else if (currentHeader != null && !locationFound) {
      val locationMatch: MatchResult? = locationPattern.matchEntire(trimmedLine)
      if (locationMatch != null) {
        locationFound = true

        val matchedLine: Int? = locationMatch.groupValues[2].toIntOrNull()
        val matchedColumn: Int? = locationMatch.groupValues[3].toIntOrNull()

        currentHeader = currentHeader.copy(line = matchedLine, column = matchedColumn)
      }
    }
  }
  currentHeader?.let { issues.add(it) }
  filterIssuesForFile(issues, filePath)
}

/**
 * TypeScript emits "file.ts(line,col): error TS2322: message".
 * Error code is optional (TS1234 part may be absent).
 */
private val tscParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val pattern = Regex("""^(.+?)\((\d+)(?:,(\d+))?\):\s+(error|warning):\s(?:TS(\d+):\s)?(.+)$""")
  output.lines()
    .mapNotNull { line: String ->
      val trimmedLine: String = line.trimEnd()
      val match: MatchResult = pattern.matchEntire(trimmedLine) ?: return@mapNotNull null
      SyntaxIssue(
        message = match.groupValues[6],
        severity = match.groupValues[4],
        line = match.groupValues[2].toIntOrNull(),
        column = match.groupValues[3].toIntOrNull(),
        errorCode = match.groupValues[5].ifBlank { null },
      )
    }
    .let { issues: List<SyntaxIssue> -> filterIssuesForFile(issues, filePath) }
}

/**
 * Go outputs "file:line:col: message" without a severity keyword.
 * Since go build/vet exits non-zero only on errors, defaults severity to "error".
 */
private val goParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val pattern = Regex("""^(.+?):(\d+)(?::(\d+))?:\s(.+)$""")
  output.lines()
    .mapNotNull { line: String ->
      val trimmedLine: String = line.trimEnd()
      if (trimmedLine.isBlank()) return@mapNotNull null

      val match: MatchResult = pattern.matchEntire(trimmedLine) ?: return@mapNotNull null
      val message: String = match.groupValues[4]
      val severity: String = when {
        message.contains("error", ignoreCase = true) -> "error"
        message.contains("warning", ignoreCase = true) -> "warning"
        else -> "error"
      }
      SyntaxIssue(
        message = message, severity = severity,
        line = match.groupValues[2].toIntOrNull(),
        column = match.groupValues[3].toIntOrNull()
      )
    }
    .let { issues: List<SyntaxIssue> -> filterIssuesForFile(issues, filePath) }
}

/**
 * Python / Node / JS parser. Handles three formats from the same language:
 * 1. Traceback-style: "File \"path\", line N" + "ErrorType: message" (py_compile)
 * 2. Flat format: "file:line:col: message" without severity keyword (pyflakes, jshint)
 * 3. Code-prefixed: "file:line:col: CODE message" where CODE is alphanumeric (flake8, eslint)
 */
private val pythonLikeParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val issues: MutableList<SyntaxIssue> = mutableListOf()
  val fileLinePattern = Regex("""^\s*File\s+"([^"]+)",\s+line\s+(\d+)(?:,\s+column\s+(\d+))?""")
  val errorPattern = Regex("""^(\w+(?:Error|Warning)):\s(.+)$""")
  val flatPattern = Regex("""^(.+?):(\d+)(?::(\d+))?:\s(.+)$""")
  var lastFileLine: Int? = null
  var lastFileColumn: Int? = null

  for (line: String in output.lines()) {
    val trimmedLine: String = line.trimEnd()
    val fileMatch: MatchResult? = fileLinePattern.matchEntire(trimmedLine)
    if (fileMatch != null) {
      lastFileLine = fileMatch.groupValues[2].toIntOrNull()
      lastFileColumn = fileMatch.groupValues[3].toIntOrNull()
      continue
    }
    val errorMatch: MatchResult? = errorPattern.matchEntire(trimmedLine.trimStart())
    if (errorMatch != null) {
      issues.add(
        SyntaxIssue(
          message = "${errorMatch.groupValues[1]}: ${errorMatch.groupValues[2]}",
          severity = if (errorMatch.groupValues[1].contains("Warning")) "warning" else "error",
          line = lastFileLine, column = lastFileColumn,
        )
      )
      lastFileLine = null
      lastFileColumn = null
      continue
    }
    val flatMatch: MatchResult? = flatPattern.matchEntire(trimmedLine)
    if (flatMatch != null) {
      val rawMessage: String = flatMatch.groupValues[4]
      val codePrefix: MatchResult? = Regex("""^[A-Z]\d+\s""").find(rawMessage)
      val cleanMessage: String = codePrefix?.let { rawMessage.removePrefix(it.value) } ?: rawMessage
      issues.add(
        SyntaxIssue(
          message = cleanMessage.trimStart(),
          severity = if ("warning" in rawMessage.lowercase()) "warning" else "error",
          line = flatMatch.groupValues[2].toIntOrNull(),
          column = flatMatch.groupValues[3].toIntOrNull(),
          errorCode = codePrefix?.value?.trim(),
        )
      )
    }
  }
  // Grab any line containing "Error" when no structured format matched at all.
  if (issues.isEmpty()) {
    for (line: String in output.lines()) {
      val trimmedLine: String = line.trimEnd()
      if (trimmedLine.contains("Error", ignoreCase = true)) {
        val severity: String = if (trimmedLine.contains("Warning", ignoreCase = true)) "warning" else "error"
        issues.add(SyntaxIssue(message = trimmedLine.trimStart(), severity = severity))
      }
    }
  }
  filterIssuesForFile(issues, filePath)
}

/** Ruby -c and Perl -c output "file:line: message". Determines severity from message text. */
private val rubyPerlParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val pattern = Regex("""^(.+?):(\d+):\s(.+)$""")
  output.lines()
    .mapNotNull { line: String ->
      val trimmedLine: String = line.trimEnd()
      val match: MatchResult = pattern.matchEntire(trimmedLine) ?: return@mapNotNull null
      val message: String = match.groupValues[3]
      val severity: String = when {
        "warning" in message.lowercase() -> "warning"
        message.lowercase().startsWith("syntax error") -> "error"
        "error" in message.lowercase() -> "error"
        else -> "error"
      }
      SyntaxIssue(
        message = message, severity = severity,
        line = match.groupValues[2].toIntOrNull(),
      )
    }
    .let { issues: List<SyntaxIssue> -> filterIssuesForFile(issues, filePath) }
}

/** PHP -l prefix, then extracts location from " in /path/file on line N" suffix. */
private val phpParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val locationPattern = Regex("""^.+? in (.+?) on line (\d+)$""")
  val headerPattern = Regex("""^(PHP\s+)?(Parse\s+)?(error|warning|notice):\s(.+)""")
  output.lines()
    .mapNotNull { line: String ->
      val trimmedLine: String = line.trimEnd()
      val locationMatch: MatchResult? = locationPattern.matchEntire(trimmedLine)
      if (locationMatch != null) {
        val messageLine: String = trimmedLine.substringBefore(" in ")
        val headerMatch: MatchResult? = headerPattern.matchEntire(messageLine.trim())
        val message: String = headerMatch?.groupValues?.get(4) ?: messageLine
        val severity: String = when {
          "error" in messageLine.lowercase() -> "error"
          "warning" in messageLine.lowercase() -> "warning"
          else -> "error"
        }
        SyntaxIssue(
          message = message, severity = severity,
          line = locationMatch.groupValues[2].toIntOrNull()
        )
      } else {
        val headerMatch: MatchResult? = headerPattern.matchEntire(trimmedLine)
        if (headerMatch != null) {
          val message: String = headerMatch.groupValues[4]
          val severity: String = when {
            "error" in message.lowercase() -> "error"
            "warning" in message.lowercase() -> "warning"
            else -> "error"
          }
          SyntaxIssue(message = message, severity = severity)
        } else null
      }
    }
    .let { issues: List<SyntaxIssue> -> filterIssuesForFile(issues, filePath) }
}

/** Bash -n uses "file: line N: error message" format. */
private val bashParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  val pattern = Regex("""^(.+?):\s+line\s+(\d+):\s(.+)$""")
  output.lines()
    .mapNotNull { line: String ->
      val trimmedLine: String = line.trimEnd()
      val match: MatchResult = pattern.matchEntire(trimmedLine) ?: return@mapNotNull null
      SyntaxIssue(
        message = match.groupValues[3], severity = "error",
        line = match.groupValues[2].toIntOrNull()
      )
    }
    .let { issues: List<SyntaxIssue> -> filterIssuesForFile(issues, filePath) }
}

/**
 * Last-resort parser for tools with unknown output formats.
 * Scans lines for keywords like "error", "warning", "not found", "undefined", "unexpected".
 */
private val fallbackParser: SyntaxParser = SyntaxParser { output: String, filePath: String ->
  output.lines()
    .mapNotNull { line: String ->
      val trimmedLine: String = line.trimEnd()
      if (trimmedLine.isBlank()) return@mapNotNull null
      val lineMatch: MatchResult? = Regex("""(?:line\s+)?(\d+)""").find(trimmedLine)
      val severity: String = when {
        "error" in trimmedLine.lowercase() -> "error"
        "warning" in trimmedLine.lowercase() -> "warning"
        trimmedLine.contains("not found", ignoreCase = true) -> "error"
        trimmedLine.contains("undefined", ignoreCase = true) -> "error"
        trimmedLine.contains("unexpected", ignoreCase = true) -> "error"
        else -> "error"
      }
      SyntaxIssue(
        message = trimmedLine.trimStart(), severity = severity,
        line = lineMatch?.groupValues?.get(1)?.toIntOrNull(),
      )
    }
    .let { issues: List<SyntaxIssue> -> filterIssuesForFile(issues, filePath) }
}


/** Filters cross-file noise when multi-file compilation produces errors for other files. */
private fun filterIssuesForFile(issues: List<SyntaxIssue>, filePath: String): List<SyntaxIssue> {
  val absolutePath: Path = Path.of(filePath).toAbsolutePath().normalize()
  val fileName: String = absolutePath.fileName.toString()
  return issues.filter { issue: SyntaxIssue ->
    if (issue.line != null) return@filter true
    val message: String = issue.message
    message.contains(absolutePath.toString()) || message.contains(fileName) || (
      !Regex("""[\w/]+\.\w+""").containsMatchIn(message)
      )
  }
}

private val cCommands: List<CompileCommand> = listOf(
  CompileCommand(listOf("clang", "-fsyntax-only", "-Wall", "-Wextra", "-Wpedantic", "-fdiagnostics-show-option")),
  CompileCommand(listOf("gcc", "-fsyntax-only", "-Wall", "-Wextra", "-Wpedantic", "-fdiagnostics-show-option")),
)

private val cppCommands: List<CompileCommand> = listOf(
  CompileCommand(listOf("clang++", "-fsyntax-only", "-Wall", "-Wextra", "-Wpedantic", "-fdiagnostics-show-option")),
  CompileCommand(listOf("g++", "-fsyntax-only", "-Wall", "-Wextra", "-Wpedantic", "-fdiagnostics-show-option")),
)

private val cHeaderCommands: List<CompileCommand> = listOf(
  CompileCommand(listOf("clang", "-fsyntax-only", "-Wall", "-Wextra", "-Wpedantic")),
  CompileCommand(listOf("gcc", "-fsyntax-only", "-Wall", "-Wextra", "-Wpedantic")),
)

private val tscCommands: List<CompileCommand> = listOf(
  CompileCommand(listOf("tsc", "--noEmit", "--strict")),
)

private val jsCommands: List<CompileCommand> = listOf(
  CompileCommand(listOf("eslint")),
  CompileCommand(listOf("jshint")),
  CompileCommand(listOf("node", "--check")),
)

private val pyCommands: List<CompileCommand> = listOf(
  CompileCommand(listOf("pyflakes")),
  CompileCommand(listOf("flake8")),
  CompileCommand(listOf("python3", "-m", "py_compile"), env = mapOf("PYTHONDONTWRITEBYTECODE" to "1")),
  CompileCommand(listOf("python", "-m", "py_compile"), env = mapOf("PYTHONDONTWRITEBYTECODE" to "1")),
)

private val languageConfigs: Map<String, LanguageSyntaxConfig> = mapOf(
  ".c" to LanguageSyntaxConfig(commands = cCommands, outputParser = standardParser),
  ".cpp" to LanguageSyntaxConfig(commands = cppCommands, outputParser = standardParser),
  ".cc" to LanguageSyntaxConfig(commands = cppCommands, outputParser = standardParser),
  ".h" to LanguageSyntaxConfig(commands = cHeaderCommands, outputParser = standardParser),
  ".rs" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("rustc", "--edition", "2021", "--deny", "warnings"))),
    outputParser = rustcParser,
  ),
  ".go" to LanguageSyntaxConfig(
    commands = listOf(
      CompileCommand(listOf("go", "build", "-o", "/dev/null")),
      CompileCommand(listOf("go", "vet")),
    ),
    outputParser = goParser,
  ),
  ".py" to LanguageSyntaxConfig(commands = pyCommands, outputParser = pythonLikeParser),
  ".ts" to LanguageSyntaxConfig(commands = tscCommands, outputParser = tscParser),
  ".tsx" to LanguageSyntaxConfig(commands = tscCommands, outputParser = tscParser),
  ".js" to LanguageSyntaxConfig(commands = jsCommands, outputParser = pythonLikeParser),
  ".jsx" to LanguageSyntaxConfig(commands = jsCommands, outputParser = pythonLikeParser),
  ".mjs" to LanguageSyntaxConfig(commands = jsCommands, outputParser = pythonLikeParser),
  ".java" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("javac", "-J-Duser.language=en", "-Xlint:all"))),
    outputParser = standardParser,
  ),
  ".kt" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("kotlinc", "-J-Duser.language=en", "-nowarn", "-Werror"))),
    outputParser = standardParser,
  ),
  ".kts" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("kotlinc", "-J-Duser.language=en", "-nowarn", "-script"))),
    outputParser = standardParser,
  ),
  ".rb" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("ruby", "-c", "-W2"))),
    outputParser = rubyPerlParser,
  ),
  ".swift" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("swift", "-typecheck"))),
    outputParser = standardParser,
  ),
  ".php" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("php", "-l"))),
    outputParser = phpParser,
  ),
  ".pl" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("perl", "-c"))),
    outputParser = rubyPerlParser,
  ),
  ".sh" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("bash", "-n"))),
    outputParser = bashParser,
  ),
  ".dart" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("dart", "analyze"))),
    outputParser = standardParser,
  ),
  ".hs" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("ghc", "-fno-code", "-Wall"))),
    outputParser = standardParser,
  ),
  ".lua" to LanguageSyntaxConfig(
    commands = listOf(CompileCommand(listOf("luac", "-p"))),
    outputParser = fallbackParser,
  )
)

object SyntaxChecker {

  fun checkSyntax(resolvedPath: Path): List<Map<String, Any?>> {
    val fileName: String = resolvedPath.fileName.toString()
    val fileExtension = ".${fileName.substringAfterLast('.', "")}"
    val configuration: LanguageSyntaxConfig = languageConfigs[fileExtension] ?: return emptyList()
    val resolvedFilePath: String = resolvedPath.toString()

    for (command: CompileCommand in configuration.commands) {
      val executableName: String = command.args.first()
      val commandFoundOnPath: Boolean =
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.any { directory: String ->
          File(directory, executableName).canExecute()
        } ?: false
      if (!commandFoundOnPath) continue

      return try {
        val fullCommand: List<String> = command.args + resolvedFilePath
        val processBuilder: ProcessBuilder = ProcessBuilder(fullCommand).redirectErrorStream(true)

        processBuilder.environment()["LC_ALL"] = "C"
        for ((key: String, value: String) in command.env) {
          processBuilder.environment()[key] = value
        }
        val process: Process = processBuilder.start()

        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          break
        }

        val compilerOutput: String =
          process.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

        if (process.exitValue() == 0) return emptyList()

        configuration.outputParser.parse(compilerOutput, resolvedFilePath)
          .map { issue: SyntaxIssue -> issue.toMap() }
      } catch (exception: Exception) {
        logger.warn("Syntax check failed for $executableName: ${exception.message}")
        continue
      }
    }
    return emptyList()
  }
}
