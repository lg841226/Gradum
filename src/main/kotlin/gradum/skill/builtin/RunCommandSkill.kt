package gradum.skill.builtin

import gradum.*
import gradum.skill.*
import gradum.skill.dsl.*
import gradum.task.captureDescendants
import gradum.task.killProcessTree
import gradum.task.startStreamReader
import gradum.utils.CommandVerdict
import gradum.utils.classifyCommand
import gradum.utils.normalizeCommandOutput
import gradum.utils.trimmedOutputLines
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// Default timeout in seconds: delegated to [GradumConfig].
private const val DEFAULT_TIMEOUT_SECONDS: Long = GradumConfig.COMMAND_DEFAULT_TIMEOUT_SECONDS

// Maximum allowed timeout in seconds: delegated to [GradumConfig].
private const val MAX_TIMEOUT_SECONDS: Long = GradumConfig.COMMAND_MAX_TIMEOUT_SECONDS

// Grace period after SIGTERM before SIGKILL: delegated to [GradumConfig].
private const val FORCE_KILL_DELAY_MS: Long = GradumConfig.COMMAND_FORCE_KILL_DELAY_MS

// Cap on how much command output is read into the LLM context: delegated to [GradumConfig].
private const val MAX_OUTPUT_CHARS: Int = GradumConfig.COMMAND_MAX_OUTPUT_CHARS

// Resolves authorization for a command that received a
// [CommandVerdict.NeedsApproval] verdict. Returns true when it may run:
// the category was already approved this session, the user allowed a single
// execution, or the user chose "always" (remembered by [category]). Returns
// false when no ask capability is wired (tests / sub-agents) or the user
// rejected/canceled.
private fun authorizeCommand(commandText: String, verdict: CommandVerdict.NeedsApproval, context: SkillContext): Boolean {
  if (context.isYolo) return true
  if (verdict.category in context.authorizedCommandCategories) return true
  val askScope: AskScope = context.scope ?: return false
  val decision: AskResult = askScope.askInteraction {
    title = l10n.key("gradum.ask.run_cmd.title")
    details = l10n.raw("$commandText\n(${verdict.description})", source = Lang.EN)
    choices {
      item(Choice.Meaning.ALLOW_ONCE, labelKey = "gradum.ask.run_cmd.choice.once")
      item(Choice.Meaning.ALLOW_ALWAYS, labelKey = "gradum.ask.run_cmd.choice.always")
      item(Choice.Meaning.REJECT, labelKey = "gradum.ask.run_cmd.choice.reject")
    }
    default = Choice.Meaning.REJECT
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

class RunCommandSkill : Skill() {

  override val skillName: String = "run_cmd"
  override val alias: String = "Ran"
  override val description: String =
    "Execute a shell command. " +
      "Use background=true for background execution. " +
      "Timeout defaults to ${DEFAULT_TIMEOUT_SECONDS}s, max ${MAX_TIMEOUT_SECONDS}s."

  override val toolDisplay: ToolDisplay =
    ToolDisplay(label = "Run command", paramKey = "command", kind = ToolDisplayKind.EXECUTE)

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
        name = "background",
        description = "Run in background. Returns a taskId immediately; the command output " +
          "is appended to the conversation automatically when the command exits.",
      )
      integer(
        name = "timeout",
        description = "Timeout in seconds (default ${DEFAULT_TIMEOUT_SECONDS}, max ${MAX_TIMEOUT_SECONDS})",
      )
    }
  }

  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val commandText: String = arguments["command"] as? String ?: ""
    val runInBackground: Boolean = arguments["background"] as? Boolean ?: false
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
    if (commandVerdict is CommandVerdict.Blocked && !context.isYolo) {
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

    // Simple models always run blocking (no background mode)
    if (runInBackground && !useSimpleOutput)
      return executeBackground(commandText, context)

    return executeBlocking(commandText, projectRoot, useSimpleOutput, arguments)
  }

  private fun executeBackground(commandText: String, context: SkillContext): SkillResult {
    return try {
      val handle = context.background {
        this.command = commandText
      }

      makeSuccess {
        boolean("background", true)
        string("taskId", handle.taskId)
        string("command", commandText)
        string(
          "message",
          "Command is running in background (task ${handle.taskId}). " +
            "Its output will be appended to this conversation when it finishes. " +
            "Continue with other work or end your turn."
        )
      }
    } catch (submissionException: Exception) {
      makeFailure(
        code = ErrorCode.IO_ERROR,
        message = buildXmlError(
          code = "IO_ERROR",
          message = submissionException.message ?: "Failed to submit background command.",
          fixHint = "This is not your fault. Check the command syntax and try again."
        ),
        context = mapOf("command" to commandText)
      )
    }
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
        startStreamReader(commandProcess.inputStream, label = "stdout", maxChars = MAX_OUTPUT_CHARS)
      val stderrFuture: Future<String> =
        startStreamReader(inputStream = commandProcess.errorStream, label = "stderr", maxChars = MAX_OUTPUT_CHARS)

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
              "($MAX_TIMEOUT_SECONDS s max), a simpler command, or background=true for fire-and-forget tasks."
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

      val commandOutput: String = buildXml {
        element("Output") {
          element("ExitCode") { text(exitCode.toString()) }
          val stdoutLines: List<String> = trimmedOutputLines(stdoutText)
          element("Stdout") {
            if (stdoutLines.isEmpty()) text("(empty)")
            else stdoutLines.forEach { line -> element("Line") { text(line) } }
          }
          val stderrLines: List<String> = trimmedOutputLines(stderrText)
          element("Stderr") {
            if (stderrLines.isEmpty()) text("(empty)")
            else stderrLines.forEach { line -> element("Line") { text(line) } }
          }
        }
      }

      if (useSimpleOutput) {
        makeSuccess {
          integer("exitCode", exitCode)
          string("command", commandText)
          string("output", normalizeCommandOutput(commandOutput.take(n = 3000)))
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
}
