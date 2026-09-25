/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RunCommandSkill.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.*
import gradum.utils.CommandVerdict
import gradum.utils.classifyCommand
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger("RunCommandSkill")

/** Default timeout in seconds — delegated to [GradumConfig]. */
private const val DEFAULT_TIMEOUT_SECONDS: Long = GradumConfig.COMMAND_DEFAULT_TIMEOUT_SECONDS

/** Maximum allowed timeout in seconds — delegated to [GradumConfig]. */
private const val MAX_TIMEOUT_SECONDS: Long = GradumConfig.COMMAND_MAX_TIMEOUT_SECONDS

/** Grace period after SIGTERM before SIGKILL — delegated to [GradumConfig]. */
private const val FORCE_KILL_DELAY_MS: Long = GradumConfig.COMMAND_FORCE_KILL_DELAY_MS

/** Cap on how much command output is read into the LLM context — delegated to [GradumConfig]. */
private const val MAX_OUTPUT_CHARS: Int = GradumConfig.COMMAND_MAX_OUTPUT_CHARS

/** Reader used for stdout/stderr draining that happens before waitFor. */
private val streamReaderPool: java.util.concurrent.ExecutorService =
  Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "gradum-run-cmd-stream").apply { isDaemon = true }
  }

/**
 * Resolves authorization for a command that received a
 * [CommandVerdict.NeedsApproval] verdict. Returns true when it may run:
 * the category was already approved this session, the user allowed a single
 * execution, or the user chose "always" (remembered by [category]). Returns
 * false when no ask capability is wired (tests / sub-agents) or the user
 * rejected/canceled.
 */
private fun authorizeCommand(commandText: String, verdict: CommandVerdict.NeedsApproval, context: SkillContext): Boolean {
  if (verdict.category in context.authorizedCommandCategories) return true
  val askScope: AskScope = context.scope ?: return false
  val decision: AskResult = askScope.askInteraction {
    title = l10n.key("gradum.ask.run_cmd.title")
    details = l10n.raw("$commandText\n(${verdict.description})", source = Lang.EN)
    choices {
      item("once", Choice.Meaning.ALLOW_ONCE, labelKey = "gradum.ask.run_cmd.choice.once")
      item("always", Choice.Meaning.ALLOW_ALWAYS, labelKey = "gradum.ask.run_cmd.choice.always")
      item("no", Choice.Meaning.REJECT, labelKey = "gradum.ask.run_cmd.choice.reject")
    }
    default = "no"
  }
  return when (decision) {
    is AskResult.Case -> when (decision.meaning) {
      Choice.Meaning.ALLOW_ONCE -> true
      Choice.Meaning.ALLOW_ALWAYS -> true.also { context.authorizedCommandCategories.add(verdict.category) }
      Choice.Meaning.REJECT -> false
    }

    else -> false
  }
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
 * Note:️ The caller must pass pre-enumerated [descendants] (captured before
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

  override val simpleDescription: String = "Execute a shell command"

  override val schemaProperties: SchemaBuilder.() -> Unit = {
    string(
      name = "command",
      description = "Shell command to execute",
      required = true,
    )
    string(name = "reason", description = "Why this command is needed")
    cloudOnly {
      boolean(
        name = "detached",
        description = "Run in background mode (fire-and-forget)",
      )
      integer(
        name = "timeout",
        description = "Timeout in seconds (default ${DEFAULT_TIMEOUT_SECONDS}, max ${MAX_TIMEOUT_SECONDS})",
      )
    }
  }

  @OptIn(DangerousOperation::class)
  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val commandText: String = arguments["command"] as? String ?: ""
    val runDetached: Boolean = arguments["detached"] as? Boolean ?: false
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = context.isSimpleModel

    if (commandText.isBlank())
      return makeFailure(
        code = ErrorCode.INVALID_PARAMETER,
        message = buildXmlError(
          code = "INVALID_PARAMETER",
          message = "Missing 'command' parameter.",
          fixHint = "Provide a shell command string in the 'command' parameter."
        )
      )

    val commandVerdict: CommandVerdict = classifyCommand(commandText, projectRoot)
    if (commandVerdict is CommandVerdict.Blocked) {
      return makeFailure(
        code = ErrorCode.COMMAND_BLOCKED,
        message = buildXmlError(
          code = "COMMAND_BLOCKED",
          message = "Blocked by safety filter: ${commandVerdict.description}",
          fixHint = "This command is blocked by security policy. Choose a different command or ask the user for permission."
        ),
        context = mapOf("command" to commandText, "rule" to commandVerdict.ruleName)
      )
    }

    if (commandVerdict is CommandVerdict.NeedsApproval &&
      !authorizeCommand(commandText, commandVerdict, context)
    ) {
      return makeFailure(
        code = ErrorCode.PERMISSION_DENIED,
        message = buildXmlError(
          code = "PERMISSION_DENIED",
          message = "Command not approved: ${commandVerdict.description}",
          fixHint = "Ask the user for permission to run this command."
        ),
        context = mapOf("command" to commandText, "category" to commandVerdict.category)
      )
    }

    // Simple models always run blocking (no detached mode)
    if (runDetached && !useSimpleOutput)
      return executeDetached(commandText, skillContext = context)

    return executeBlocking(commandText, projectRoot, useSimpleOutput, arguments)
  }

  private fun executeBlocking(
    commandText: String,
    projectRoot: String = "",
    useSimpleOutput: Boolean = false,
    arguments: Map<String, Any> = emptyMap()
  ): SkillResult {
    val rawTimeout: Long = (arguments["timeout"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_SECONDS
    val timeoutSeconds: Long = rawTimeout.coerceIn(1, MAX_TIMEOUT_SECONDS)
    return try {
      val processBuilder = ProcessBuilder("sh", "-c", commandText)
      processBuilder.redirectErrorStream(false)
      if (projectRoot.isNotBlank()) {
        val workingDirectory = File(projectRoot)
        if (workingDirectory.isDirectory)
          processBuilder.directory(workingDirectory)
      }

      val commandProcess: Process = processBuilder.start()

      val stdoutFuture: Future<String> =
        startStreamReader(commandProcess.inputStream, label = "stdout")
      val stderrFuture: Future<String> =
        startStreamReader(inputStream = commandProcess.errorStream, label = "stderr")

      val processFinished: Boolean = commandProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)

      if (!processFinished) {
        val descendants = captureDescendants(commandProcess)

        commandProcess.destroy()
        commandProcess.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)

        killProcessTree(commandProcess, descendants)

        return makeFailure(
          code = ErrorCode.TIMEOUT,
          message = buildXmlError(
            code = "TIMEOUT",
            message = "Command timed out after $timeoutSeconds seconds.",
            fixHint = "The command took too long. Try a longer timeout " +
              "($MAX_TIMEOUT_SECONDS s max), a simpler command, or detached=true for fire-and-forget tasks."
          ),
          context = mapOf("command" to commandText, "timeout" to true),
        )
      }

      val exitCode: Int = commandProcess.exitValue()

      val stdoutText: String = stdoutFuture.get(5, TimeUnit.SECONDS)
      val stderrText: String = stderrFuture.get(5, TimeUnit.SECONDS)

      // Detect whether either stream was truncated by checking for the
      // truncation marker appended by [readStreamOutput].
      val truncatedMarker = "(output truncated at "
      val stdoutTruncated = truncatedMarker in stdoutText
      val stderrTruncated = truncatedMarker in stderrText
      val outputTruncated = stdoutTruncated || stderrTruncated

      val commandOutput: String = buildString {
        if (exitCode != 0) {
          if (stderrText.isNotBlank()) append(stderrText)
          if (stdoutText.isNotBlank()) {
            if (isNotEmpty()) append("\n(stderr)")
            append(stdoutText)
          }
        } else {
          if (stdoutText.isNotBlank()) append(stdoutText)
          if (stderrText.isNotBlank()) {
            if (isNotEmpty()) append("\n(stderr)")
            append(stderrText)
          }
        }
        if (isEmpty()) append("(no output)")
      }

      if (useSimpleOutput) {
        makeSuccess {
          integer("exitCode", exitCode)
          string("command", commandText)
          string("output", commandOutput.take(n = 3000))
        }
      } else {
        makeSuccess {
          boolean("timeout", false)
          integer("exitCode", exitCode)
          string("command", commandText)
          string("output", commandOutput)
          boolean("truncated", outputTruncated)
        }
      }
    } catch (executionException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = executionException.message ?: "Failed to execute command.",
          fixHint = "This is not your fault. Check the command syntax and try again."
        ),
        context = mapOf("command" to commandText)
      )
    }
  }

  @DangerousOperation
  private fun executeDetached(commandText: String, skillContext: SkillContext): SkillResult {
    return try {
      val logDirectory: Path = Path.of(skillContext.projectRoot, ".gradum", "run_cmd")
      logDirectory.toFile().mkdirs()
      val logFile = File(logDirectory.toFile(), "${System.currentTimeMillis()}.log")

      val processBuilder = ProcessBuilder(
        "sh", "-c", "nohup $commandText </dev/null >${logFile.absolutePath} 2>&1 &"
      )

      val detachedProcess: Process = processBuilder.start()
      val processId: Long = detachedProcess.pid()

      logger.info("Detached command (PID $processId): $commandText to ${logFile.absolutePath}")

      detachedProcess.waitFor(5, TimeUnit.SECONDS)
      detachedProcess.toHandle().onExit()

      makeSuccess {
        string("command", commandText)
        boolean("detached", true)
        integer("processId", processId.toInt())
        string("logPath", logFile.absolutePath)
        string("message", "Command started in background with PID $processId")
      }
    } catch (detachedStartException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = detachedStartException.message ?: "Failed to start detached command.",
          fixHint = "This is not your fault. Check the command syntax and system resources."
        ),
        context = mapOf("command" to commandText)
      )
    }
  }

  /**
   * Kicks off a background drain of [inputStream] and returns a Future that
   * resolves to the captured text once the stream is exhausted. Must be
   * called for BOTH stdout and stderr BEFORE `waitFor`, otherwise a
   * large-output child blocks on a full pipe and deadlocks the wait.
   */
  private fun startStreamReader(inputStream: InputStream, label: String): Future<String> {
    return streamReaderPool.submit<String> {
      readStreamOutput(inputStream, label)
    }
  }

  private fun readStreamOutput(inputStream: InputStream, label: String): String {
    return try {
      val outputBuilder = StringBuilder()
      BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { bufferedReader ->
        val charBuffer = CharArray(size = 4096)
        while (true) {
          val charsRead: Int = bufferedReader.read(charBuffer, 0, charBuffer.size)
          if (charsRead < 0) break
          outputBuilder.appendRange(value = charBuffer, startIndex = 0, endIndex = charsRead)
          if (outputBuilder.length >= MAX_OUTPUT_CHARS) {
            outputBuilder.append(
              "\n(output truncated at ${MAX_OUTPUT_CHARS / 1024} KiB, the full $label is not shown)"
            ).also {
              while (bufferedReader.read(charBuffer, 0, charBuffer.size) >= 0) {
                // Discard remaining output
              }
            }
            logger.debug("Discarded remaining output for $label after truncation")
            break
          }
        }
      }
      outputBuilder.toString()
    } catch (streamReadException: Exception) {
      val reason: String = streamReadException.message
        ?: streamReadException::class.simpleName ?: "unknown I/O error"
      logger.warn("Failed to read $label stream: $reason")
      "(stream read failed for $label: $reason)"
    }
  }
}
