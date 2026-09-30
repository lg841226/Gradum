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

internal data class ExternalCompileResult(
  val compileErrors: List<String>,
  val outputDirectory: File,
  val compileWarnings: List<String>
) {
  val isSuccess: Boolean get() = compileErrors.isEmpty()
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
 * each one is kept structured as a CompilerDiagnostic (severity + message)
 * rather than string-tagged, so callers can filter on the severity enum
 * instead of parsing message prefixes.
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

    val hasCompileErrors = messageCollector.hasErrors() || exitCode != ExitCode.OK
    return ExternalCompileResult(
      compileErrors =
        if (hasCompileErrors) messageCollector.errorMessages
        else emptyList(),
      outputDirectory = outputDirectory,
      compileWarnings = messageCollector.warningMessages
    )
  }
}

private data class CompilerDiagnostic(
  val diagnosticSeverity: CompilerMessageSeverity,
  val diagnosticMessage: String
)

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
