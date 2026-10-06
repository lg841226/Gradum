package gradum.skill.external

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

private const val EXTERNAL_JVM_TARGET: String = "21"

enum class ExternalDiagnosticSeverity { ERROR, WARNING, INFO }

/**
 * One compiler diagnostic with an optional 1-based source position.
 *
 * The position is what lets an editor underline the offending line; the
 * embeddable compiler hands it to us as a CompilerMessageSourceLocation, and
 * it is kept here instead of being folded into the message text.
 */
data class ExternalDiagnostic(
  val severity: ExternalDiagnosticSeverity,
  val message: String,
  val line: Int?,
  val column: Int?
)

internal data class ExternalCompileResult(
  val diagnostics: List<ExternalDiagnostic>,
  val outputDirectory: File
) {
  val isSuccess: Boolean
    get() = diagnostics.none { diagnostic -> diagnostic.severity == ExternalDiagnosticSeverity.ERROR }

  val compileErrors: List<String>
    get() = diagnostics
      .filter { diagnostic -> diagnostic.severity == ExternalDiagnosticSeverity.ERROR }
      .map { diagnostic -> diagnostic.message }

  val compileWarnings: List<String>
    get() = diagnostics
      .filter { diagnostic -> diagnostic.severity == ExternalDiagnosticSeverity.WARNING }
      .map { diagnostic -> diagnostic.message }
}

/**
 * Compiles external skill sources into .class files with the embeddable
 * Kotlin compiler, and reports the outcome as [ExternalCompileResult]. On
 * success the output directory holds the .class files that
 * [ExternalSkillClassLoader] loads from.
 *
 * compile() never throws: on failure the compiler diagnostics come back in
 * compileErrors so the caller can log exactly why a skill was skipped and keep
 * its last-good version. The additionalClasspath parameter carries previously
 * compiled output, so an incremental single-source compile can still resolve
 * skills that were not recompiled.
 *
 * To compile classpath is the server's own runtime classpath, so a dropped-in
 * skill can reference the in-process gradum.* API (Skill, SkillContext,
 * SkillResult, the schema DSL) without declaring any dependency. The standard
 * library is intentionally not re-injected (K2JVMCompilerArguments.noStdlib)
 * because it is already present on that classpath. Bytecode targets JVM 21
 * (EXTERNAL_JVM_TARGET).
 *
 * Diagnostics are buffered in memory instead of being printed to a stream, and
 * each one is kept structured as an [ExternalDiagnostic] (severity, message,
 * and the 1-based source line/column when the compiler supplies them) rather
 * than string-tagged, so callers can filter on the severity enum and an editor
 * can place the diagnostic on the right line instead of parsing message text.
 */
internal class ExternalSkillCompiler {

  fun compile(
    outputDirectory: File,
    sourceFiles: List<File>,
    runtimeClasspath: List<String>,
    additionalClasspath: List<File> = emptyList()
  ): ExternalCompileResult {
    outputDirectory.mkdirs()

    val compilerArguments = K2JVMCompilerArguments()
    compilerArguments.freeArgs = sourceFiles.map { sourceFile -> sourceFile.absolutePath }
    compilerArguments.destination = outputDirectory.absolutePath
    compilerArguments.classpath = (runtimeClasspath + additionalClasspath.map { entry -> entry.absolutePath })
      .joinToString(File.pathSeparator)
    compilerArguments.jvmTarget = EXTERNAL_JVM_TARGET
    compilerArguments.noStdlib = true
    compilerArguments.noReflect = true

    val messageCollector = CollectingMessageCollector()
    val exitCode = K2JVMCompiler().exec(messageCollector, Services.EMPTY, compilerArguments)

    val diagnostics = messageCollector.diagnostics
    val failedWithoutDiagnostic =
      exitCode != ExitCode.OK && diagnostics.none { it.severity == ExternalDiagnosticSeverity.ERROR }
    return ExternalCompileResult(
      diagnostics =
        if (failedWithoutDiagnostic) {
          diagnostics + ExternalDiagnostic(
            severity = ExternalDiagnosticSeverity.ERROR,
            message = "Compilation failed with exit code $exitCode",
            line = null,
            column = null,
          )
        } else diagnostics,
      outputDirectory = outputDirectory,
    )
  }
}

private class CollectingMessageCollector : MessageCollector {

  private val capturedDiagnostics: MutableList<ExternalDiagnostic> = mutableListOf()

  override fun report(
    severity: CompilerMessageSeverity,
    message: String, location: CompilerMessageSourceLocation?
  ) {
    capturedDiagnostics += ExternalDiagnostic(
      severity = severity.toExternalSeverity(),
      message = message,
      line = location?.line?.takeIf { lineNumber -> lineNumber > 0 },
      column = location?.column?.takeIf { columnNumber -> columnNumber > 0 },
    )
  }

  override fun clear() {
    capturedDiagnostics.clear()
  }

  override fun hasErrors(): Boolean =
    capturedDiagnostics.any { diagnostic -> diagnostic.severity == ExternalDiagnosticSeverity.ERROR }

  val diagnostics: List<ExternalDiagnostic>
    get() = capturedDiagnostics.toList()
}

private fun CompilerMessageSeverity.toExternalSeverity(): ExternalDiagnosticSeverity = when {
  isError -> ExternalDiagnosticSeverity.ERROR
  isWarning -> ExternalDiagnosticSeverity.WARNING
  else -> ExternalDiagnosticSeverity.INFO
}
