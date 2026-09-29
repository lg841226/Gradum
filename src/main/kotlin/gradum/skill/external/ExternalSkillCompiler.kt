package gradum.skill.external

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/** The JVM bytecode target used for externally compiled `.kt` skills. */
private const val EXTERNAL_JVM_TARGET: String = "21"

/**
 * The outcome of compiling a set of external `.kt` skill sources.
 *
 * On success [outputDirectory] holds the `.class` files that
 * [ExternalSkillClassLoader] loads from. On failure [compileErrors] carries the
 * compiler diagnostics so the caller can log exactly why a skill was skipped.
 */
internal data class ExternalCompileResult(
  val compileErrors: List<String>,
  val outputDirectory: File,
  val compileWarnings: List<String>
) {
  val isSuccess: Boolean get() = compileErrors.isEmpty()
}

/**
 * Compiles external `.kt` skills into `.class` files with the embeddable
 * Kotlin compiler.
 *
 * To compile classpath is the server's own runtime classpath, so a dropped-in
 * skill can reference the in-process `gradum.*` API (`Skill`, `SkillContext`,
 * `SkillResult`, the schema DSL) without declaring any dependency. The standard
 * library is intentionally not re-injected ([K2JVMCompilerArguments.noStdlib])
 * because it is already present on that classpath.
 */
internal class ExternalSkillCompiler {

  /**
   * Compiles [sources] into [outputDirectory]. A failed skill never throws:
   * diagnostics are returned in [compileErrors] and the caller
   * decides how to proceed.
   */
  fun compile(sourceFiles: List<File>, outputDirectory: File, runtimeClasspath: List<String>): ExternalCompileResult {
    outputDirectory.mkdirs()

    val compilerArguments = K2JVMCompilerArguments()
    compilerArguments.freeArgs = sourceFiles.map { sourceFile -> sourceFile.absolutePath }
    compilerArguments.destination = outputDirectory.absolutePath
    compilerArguments.classpath = runtimeClasspath.joinToString(File.pathSeparator)
    compilerArguments.jvmTarget = EXTERNAL_JVM_TARGET
    compilerArguments.noStdlib = true
    compilerArguments.noReflect = true

    val messageCollector = CollectingMessageCollector()
    val exitCode = K2JVMCompiler().exec(messageCollector, Services.EMPTY, compilerArguments)

    val hasCompileErrors = messageCollector.hasErrors() || exitCode != ExitCode.OK
    return ExternalCompileResult(
      compileErrors = if (hasCompileErrors) messageCollector.errorMessages else emptyList(),
      outputDirectory = outputDirectory,
      compileWarnings = messageCollector.warningMessages,
    )
  }
}

/** A single diagnostic captured from the embedded compiler, with its severity. */
private data class CompilerDiagnostic(
  val diagnosticSeverity: CompilerMessageSeverity,
  val diagnosticMessage: String
)

/**
 * Buffers compiler diagnostics in memory instead of printing to a stream.
 * Diagnostics are kept structured (severity + message) rather than string-tagged,
 * so callers can filter on the severity enum instead of parsing message prefixes.
 */
private class CollectingMessageCollector : MessageCollector {

  private val capturedDiagnostics: MutableList<CompilerDiagnostic> = mutableListOf()

  override fun report(
    severity: CompilerMessageSeverity,
    message: String, location: CompilerMessageSourceLocation?
  ) {
    capturedDiagnostics += CompilerDiagnostic(severity, message)
  }

  override fun clear() {
    capturedDiagnostics.clear()
  }

  override fun hasErrors(): Boolean =
    capturedDiagnostics.any { capturedDiagnostic -> capturedDiagnostic.diagnosticSeverity.isError }

  val errorMessages: List<String>
    get() = capturedDiagnostics
      .filter { capturedDiagnostic -> capturedDiagnostic.diagnosticSeverity.isError }
      .map { capturedDiagnostic -> capturedDiagnostic.diagnosticMessage }

  val warningMessages: List<String>
    get() = capturedDiagnostics
      .filter { capturedDiagnostic -> capturedDiagnostic.diagnosticSeverity.isWarning }
      .map { capturedDiagnostic -> capturedDiagnostic.diagnosticMessage }
}
