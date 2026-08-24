/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RunCommandSkill.kt  2026-08-22 22:15:59 Changed by gwy
 */

package gradum.skill

import gradum.*
import gradum.utils.CommandVerdict
import gradum.utils.classifyCommand
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger("RunCommandSkill")

/** Default timeout in seconds — delegated to [GradumConfig]. */
private val DEFAULT_TIMEOUT_SECONDS: Long = GradumConfig.COMMAND_DEFAULT_TIMEOUT_SECONDS

/** Maximum allowed timeout in seconds — delegated to [GradumConfig]. */
private val MAX_TIMEOUT_SECONDS: Long = GradumConfig.COMMAND_MAX_TIMEOUT_SECONDS

/** Grace period after SIGTERM before SIGKILL — delegated to [GradumConfig]. */
private val FORCE_KILL_DELAY_MS: Long = GradumConfig.COMMAND_FORCE_KILL_DELAY_MS

/** Cap on how much command output is read into the LLM context — delegated to [GradumConfig]. */
private val MAX_OUTPUT_CHARS: Int = GradumConfig.COMMAND_MAX_OUTPUT_CHARS

/** Reader used for stdout/stderr draining that happens before waitFor. */
private val streamReaderPool: java.util.concurrent.ExecutorService =
  java.util.concurrent.Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "gradum-run-cmd-stream").apply { isDaemon = true }
  }

/**
 * Enumerates all descendants of [process] and returns them. This must be
 * called BEFORE sending any signal to the parent, because once the parent
 * dies the children become orphans (adopted by init) and are no longer
 * findable via [ProcessHandle.descendants].
 */
private fun captureDescendants(process: Process): List<ProcessHandle> {
  return try {
    process.toHandle().descendants().toList()
  } catch (_: Exception) {
    // ProcessHandle may throw on unsupported platforms
    emptyList()
  }
}

/**
 * Kills the entire process tree rooted at [process], not just the immediate
 * child. Without this, `destroyForcibly()` on a `sh -c "command"` parent
 * only kills the shell — the actual command becomes an orphan and continues
 * running.
 *
 * This mirrors opencode's `killGroup` pattern which sends signals to the
 * entire process group via `process.kill(-pid, signal)`. Since Java's
 * ProcessBuilder does not expose `detached: true` (new process group), we
 * enumerate descendants via [ProcessHandle] and kill them individually.
 *
 * ⚠️ The caller must pass pre-enumerated [descendants] (captured before
 * any signal was sent) to avoid the orphan race. Example:
 * ```
 * val descendants = captureDescendants(process)  // enumerate first
 * process.destroy()                               // then signal
 * process.waitFor(FORCE_KILL_DELAY_MS, ...)
 * killProcessTree(process, descendants)           // kill everything
 * ```
 */
private fun killProcessTree(process: Process, descendants: List<ProcessHandle>) {
  for (descendant in descendants) {
    try {
      descendant.destroyForcibly()
    } catch (_: Exception) {
    }
  }
  process.destroyForcibly()
}

/**
 * Executes a shell command and captures its output.
 *
 * Every command is pre-classified via [classifyCommand] and rejected with
 * `COMMAND_BLOCKED` if it touches a critical path or unsafe executable.
 * All execution sites are annotated `@OptIn(DangerousOperation::class)`.
 *
 * Process isolation is handled by [killProcessTree] — on timeout the entire
 * process tree is killed (SIGTERM → grace period → SIGKILL), matching the
 * `forceKillAfter` pattern from opencode's bash tool. No orphan processes
 * are left behind.
 */
class RunCommandSkill : Skill() {

  override val skillName: String = "run_cmd"
  override val alias: String = "Ran"
  override val description: String =
    "Execute a shell command. " +
      "Use detached=true for background execution. " +
      "Timeout defaults to ${DEFAULT_TIMEOUT_SECONDS}s, max ${MAX_TIMEOUT_SECONDS}s."

  override val historyKeepCount: Int = 3
  override val historyVolatileKeys: List<String> = listOf("output")

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimple = context?.isSimpleModel == true
    return buildFunctionSchema(
      description = if (useSimple) "Execute a shell command" else description,
      properties = if (useSimple) simpleProperties() else cloudProperties(),
      required = listOf("command"),
    )
  }

  private fun simpleProperties(): Map<String, Any> = mapOf(
    "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
    "reason" to mapOf("type" to "string", "description" to "Why this command is needed"),
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
    "reason" to mapOf("type" to "string", "description" to "Why this command is needed"),
    "detached" to mapOf(
      "type" to "boolean",
      "description" to "Run in background mode (fire-and-forget)",
    ),
    "timeout" to mapOf(
      "type" to "integer",
      "description" to "Timeout in seconds (default ${DEFAULT_TIMEOUT_SECONDS}, max ${MAX_TIMEOUT_SECONDS})",
    ),
  )

  @OptIn(DangerousOperation::class)
  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val commandText: String = arguments["command"] as? String ?: ""
    val runDetached: Boolean = arguments["detached"] as? Boolean ?: false
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = context.isSimpleModel

    if (commandText.isBlank())
      return makeFailure(
        ErrorCode.INVALID_PARAMETER, buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'command' parameter.",
          fixHint = "Provide a shell command string in the 'command' parameter."
        )
      )

    val commandVerdict: CommandVerdict = classifyCommand(commandText)
    if (commandVerdict is CommandVerdict.Blocked) {
      return makeFailure(
        ErrorCode.COMMAND_BLOCKED,
        buildXmlError(
          code = "COMMAND_BLOCKED",
          message = "Blocked by safety filter: ${commandVerdict.description}",
          fixHint = "This command is blocked by security policy. Choose a different command or ask the user for permission."
        ),
        mapOf("command" to commandText, "rule" to commandVerdict.ruleName)
      )
    }

    // Simple models always run blocking (no detached mode)
    if (runDetached && !useSimpleOutput) return executeDetached(commandText, context)

    return executeBlocking(commandText, projectRoot, useSimpleOutput, arguments)
  }

  private fun executeBlocking(
    commandText: String,
    projectRoot: String = "",
    useSimpleOutput: Boolean = false,
    arguments: Map<String, Any> = emptyMap(),
  ): SkillResult {
    // Parse and clamp timeout
    val rawTimeout: Long = (arguments["timeout"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_SECONDS
    val timeoutSeconds: Long = rawTimeout.coerceIn(1, MAX_TIMEOUT_SECONDS)
    return try {
      val processBuilder = ProcessBuilder("sh", "-c", commandText)
      processBuilder.redirectErrorStream(false)
      if (projectRoot.isNotBlank()) {
        val workingDirectory = File(projectRoot)
        if (workingDirectory.isDirectory) processBuilder.directory(workingDirectory)
      }

      val commandProcess: Process = processBuilder.start()

      val stdoutFuture: java.util.concurrent.Future<String> = startStreamReader(commandProcess.inputStream, "stdout")
      val stderrFuture: java.util.concurrent.Future<String> = startStreamReader(commandProcess.errorStream, "stderr")

      val processFinished: Boolean = commandProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)

      if (!processFinished) {
        val descendants = captureDescendants(commandProcess)

        commandProcess.destroy()
        commandProcess.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)

        killProcessTree(commandProcess, descendants)

        return makeFailure(
          ErrorCode.TIMEOUT,
          buildXmlError(
            code = "TIMEOUT",
            message = "Command timed out after $timeoutSeconds seconds.",
            fixHint = "The command took too long. Try a longer timeout ($MAX_TIMEOUT_SECONDS s max), a simpler command, or detached=true for fire-and-forget tasks."
          ),
          mapOf("command" to commandText, "timeout" to true),
        )
      }

      val exitCode: Int = commandProcess.exitValue()

      val stdoutText: String = stdoutFuture.get(5, TimeUnit.SECONDS)
      val stderrText: String = stderrFuture.get(5, TimeUnit.SECONDS)

      // Detect whether either stream was truncated by checking for the
      // truncation marker appended by [readStreamOutput].
      val truncatedMarker = "[output truncated at "
      val stdoutTruncated = truncatedMarker in stdoutText
      val stderrTruncated = truncatedMarker in stderrText
      val outputTruncated = stdoutTruncated || stderrTruncated


      val commandOutput: String = buildString {
        if (exitCode != 0) {
          if (stderrText.isNotBlank()) append(stderrText)
          if (stdoutText.isNotBlank()) {
            if (isNotEmpty()) append("\n--- stdout ---")
            append(stdoutText)
          }
        } else {
          if (stdoutText.isNotBlank()) append(stdoutText)
          if (stderrText.isNotBlank()) {
            if (isNotEmpty()) append("\n--- stderr ---")
            append(stderrText)
          }
        }
        if (isEmpty()) append("[no output — stdout and stderr were both empty]")
      }

      if (useSimpleOutput) {
        makeSuccess(
          mapOf(
            "command" to commandText,
            "exitCode" to exitCode,
            "output" to commandOutput.take(2000),
          )
        )
      } else {
        makeSuccess(
          mapOf(
            "command" to commandText,
            "exitCode" to exitCode,
            "output" to commandOutput,
            "timeout" to false,
            "truncated" to outputTruncated,
          )
        )
      }
    } catch (executionException: Exception) {
      makeFailure(
        ErrorCode.IO_ERROR,
        buildXmlError(
          code = "IO_ERROR",
          message = executionException.message ?: "Failed to execute command.",
          fixHint = "This is not your fault. Check the command syntax and try again."
        ),
        mapOf("command" to commandText)
      )
    }
  }

  @DangerousOperation
  private fun executeDetached(commandText: String, skillContext: SkillContext): SkillResult {
    return try {
      val logDirectory: Path = Path.of(skillContext.projectRoot, ".gradum", "run_cmd")
      logDirectory.toFile().mkdirs()
      val logFile = File(logDirectory.toFile(), "${System.currentTimeMillis()}.log")

      // Truly detach the child process so it survives the Java process
      // and doesn't inherit stdin (which could block GUI apps waiting
      // for input on the pipe).  nohup + /dev/null stdin + & is the
      // standard Unix pattern for a fire-and-forget background process.
      val processBuilder = ProcessBuilder(
        "sh", "-c",
        "nohup $commandText </dev/null >${logFile.absolutePath} 2>&1 &"
      )

      val detachedProcess: Process = processBuilder.start()
      val processId: Long = detachedProcess.pid()

      logger.info("Detached command (PID $processId): $commandText to ${logFile.absolutePath}")

      detachedProcess.waitFor(5, TimeUnit.SECONDS)
      detachedProcess.toHandle().onExit()

      makeSuccess(
        mapOf(
          "command" to commandText,
          "detached" to true,
          "processId" to processId,
          "logPath" to logFile.absolutePath,
          "message" to "Command started in background with PID $processId"
        ),
      )
    } catch (detachedStartException: Exception) {
      makeFailure(
        ErrorCode.IO_ERROR,
        buildXmlError(
          code = "IO_ERROR",
          message = detachedStartException.message ?: "Failed to start detached command.",
          fixHint = "This is not your fault. Check the command syntax and system resources."
        ),
        mapOf("command" to commandText)
      )
    }
  }

  /**
   * Kicks off a background drain of [inputStream] and returns a Future that
   * resolves to the captured text once the stream is exhausted. Must be
   * called for BOTH stdout and stderr BEFORE `waitFor`, otherwise a
   * large-output child blocks on a full pipe and deadlocks the wait.
   */
  private fun startStreamReader(inputStream: java.io.InputStream, label: String): java.util.concurrent.Future<String> {
    return streamReaderPool.submit<String> {
      readStreamOutput(inputStream, label)
    }
  }

  private fun readStreamOutput(inputStream: java.io.InputStream, label: String): String {
    return try {
      val builder = StringBuilder()
      BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { bufferedReader ->
        val buffer = CharArray(4096)
        while (true) {
          val read: Int = bufferedReader.read(buffer, 0, buffer.size)
          if (read < 0) break
          builder.appendRange(buffer, 0, read)
          if (builder.length >= MAX_OUTPUT_CHARS) {
            builder.append(
              "\n[output truncated at ${MAX_OUTPUT_CHARS / 1024} KiB — the full $label is not shown]"
            )

            while (bufferedReader.read(buffer, 0, buffer.size) >= 0) {
              /* discard */
            }
            break
          }
        }
      }
      builder.toString()
    } catch (streamReadException: Exception) {
      val reason: String = streamReadException.message
        ?: streamReadException::class.simpleName
        ?: "unknown I/O error"
      logger.warn("Failed to read $label stream: $reason", streamReadException)
      "[stream read failed for $label: $reason]"
    }
  }
}
