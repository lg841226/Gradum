/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RunCommandSkill.kt  2026-07-06 01:40:47 Changed by gwy
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
private const val COMMAND_TIMEOUT_SECONDS: Long = 45

/**
 * Executes a shell command and captures its output.
 *
 * Every command is pre-classified via [classifyCommand] and rejected with
 * `COMMAND_BLOCKED` if it touches a critical path or unsafe executable.
 * All execution sites are annotated `@OptIn(DangerousOperation::class)`.
 */
class RunCommandSkill : Skill() {

  override val skillName: String = "run_cmd"
  override val alias: String = "Ran"
  override val description: String = "Execute a shell command. Use detached=true to run in the background."

  override val historyKeepCount: Int = 3
  override val historyVolatileKeys: List<String> = listOf("output")

  override fun getSchema(context: SkillContext?): Map<String, Any> {
    val useSimple = context != null && SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE
    return mapOf(
      "type" to "function",
      "function" to mapOf(
        "name" to skillName,
        "description" to if (useSimple) "Execute a shell command" else description,
        "parameters" to mapOf(
          "type" to "object",
          "properties" to if (useSimple) simpleProperties() else cloudProperties(),
          "required" to listOf("command"),
        ),
      ),
    )
  }

  private fun simpleProperties(): Map<String, Any> = mapOf(
    "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
    "reason" to mapOf("type" to "string", "description" to "Why this command is needed"),
  )

  private fun cloudProperties(): Map<String, Any> = mapOf(
    "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
    "reason" to mapOf("type" to "string", "description" to "Why this command is needed"),
    "detached" to mapOf("type" to "boolean", "description" to "Run in background mode"),
  )

  @OptIn(DangerousOperation::class)
  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
    val commandText: String = arguments["command"] as? String ?: ""
    val runDetached: Boolean = arguments["detached"] as? Boolean ?: false
    val projectRoot: String = context.projectRoot
    val useSimpleOutput = SchemaVariant.resolve(context.modelName) == SchemaVariant.SIMPLE

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

    return executeBlocking(commandText, projectRoot, useSimpleOutput)
  }

  private fun executeBlocking(
    commandText: String, projectRoot: String = "", useSimpleOutput: Boolean = false
  ): SkillResult {
    return try {
      val processBuilder = ProcessBuilder("sh", "-c", commandText)
      processBuilder.redirectErrorStream(false)
      if (projectRoot.isNotBlank()) {
        val workingDirectory = File(projectRoot)
        if (workingDirectory.isDirectory) processBuilder.directory(workingDirectory)
      }

      val commandProcess: Process = processBuilder.start()
      val processFinished: Boolean = commandProcess.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      if (!processFinished) {
        commandProcess.destroyForcibly()
        return makeFailure(
          ErrorCode.TIMEOUT,
          buildXmlError(
            code = "TIMEOUT",
            message = "Command timed out after $COMMAND_TIMEOUT_SECONDS seconds.",
            fixHint = "The command took too long. Try a simpler command or use detached=true for long-running commands."
          ),
          mapOf("command" to commandText),
        )
      }

      val exitCode: Int = commandProcess.exitValue()

      val stdoutText: String = readStreamOutput(commandProcess.inputStream, "stdout")
      val stderrText: String = readStreamOutput(commandProcess.errorStream, "stderr")
      // Prefer stdout when it actually has content; fall back to stderr
      // when stdout is empty. Both fall through with an explicit failure
      // marker if the read itself threw — never a blank string, because
      // the LLM needs to know the I/O failed and not assume the command
      // simply produced no output.
      val commandOutput: String = when {
        stdoutText.isNotBlank() -> stdoutText
        stderrText.isNotBlank() -> stderrText
        else -> "[no output — stdout and stderr were both empty]"
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
            "timedOut" to false,
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

      val processBuilder = ProcessBuilder("sh", "-c", commandText)
      processBuilder.redirectOutput(logFile)
      processBuilder.redirectErrorStream(true)

      val detachedProcess: Process = processBuilder.start()
      val processId: Long = detachedProcess.pid()

      logger.info("Detached command (PID $processId): $commandText to ${logFile.absolutePath}")

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

  private fun readStreamOutput(inputStream: java.io.InputStream, label: String): String {
    // The output of this function is rendered verbatim into the LLM's
    // tool-result message, so a swallowed exception here turns into
    // `output: ""` in the model prompt — the LLM then assumes the
    // command produced nothing and may retry, re-architect the plan,
    // or give up entirely (e.g. `ls -la .idea/` returning `""` to a
    // model that can't see the underlying EACCES). Always surface the
    // failure as a visible marker so the model can react.
    return try {
      BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { bufferedReader ->
        bufferedReader.readText()
      }
    } catch (streamReadException: Exception) {
      val reason: String = streamReadException.message
        ?: streamReadException::class.simpleName
        ?: "unknown I/O error"
      logger.warn("Failed to read $label stream: $reason", streamReadException)
      "[stream read failed for $label: $reason]"
    }
  }
}
