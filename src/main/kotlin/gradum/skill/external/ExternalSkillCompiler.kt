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
 * [ExternalSkillClassLoader] loads from. On failure [errors] carries the
 * compiler diagnostics so the caller can log exactly why a skill was skipped.
 */
internal data class ExternalCompileResult(
  val errors: List<String>,
  val outputDirectory: File,
  val warnings: List<String>
) {
  val isSuccess: Boolean get() = errors.isEmpty()
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
   * diagnostics are returned in [errors] and the caller
   * decides how to proceed.
   */
  fun compile(sources: List<File>, outputDirectory: File, runtimeClasspath: List<String>): ExternalCompileResult {
    outputDirectory.mkdirs()

    val arguments = K2JVMCompilerArguments()
    arguments.freeArgs = sources.map { source -> source.absolutePath }
    arguments.destination = outputDirectory.absolutePath
    arguments.classpath = runtimeClasspath.joinToString(File.pathSeparator)
    arguments.jvmTarget = EXTERNAL_JVM_TARGET
    arguments.noStdlib = true
    arguments.noReflect = true

    val collector = CollectingMessageCollector()
    val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

    val hadErrors = collector.hasErrors() || exitCode != ExitCode.OK
    return ExternalCompileResult(
      errors = if (hadErrors) collector.errorMessages else emptyList(),
      outputDirectory = outputDirectory,
      warnings = collector.warningMessages,
    )
  }
}

/** A single diagnostic captured from the embedded compiler, with its severity. */
private data class CompilerDiagnostic(
  val severity: CompilerMessageSeverity,
  val message: String
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
    capturedDiagnostics.any { capturedDiagnostic -> capturedDiagnostic.severity.isError }

  val errorMessages: List<String>
    get() = capturedDiagnostics
      .filter { capturedDiagnostic -> capturedDiagnostic.severity.isError }
      .map { capturedDiagnostic -> capturedDiagnostic.message }

  val warningMessages: List<String>
    get() = capturedDiagnostics
      .filter { capturedDiagnostic -> capturedDiagnostic.severity.isWarning }
      .map { capturedDiagnostic -> capturedDiagnostic.message }
}
