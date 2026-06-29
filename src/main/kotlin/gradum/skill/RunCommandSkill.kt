/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * RunCommandSkill.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.skill

import gradum.DangerousOperation
import gradum.ErrorCode
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import gradum.utils.classifyCommand
import gradum.utils.CommandVerdict
import gradum.ProjectPaths
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

    override val historyKeepCount: Int = 2
    override val historyVolatileKeys: List<String> = listOf("output")

    override fun getSchema(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to skillName,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
                        "reason" to mapOf("type" to "string", "description" to "Why this command is needed"),
                        "detached" to mapOf("type" to "boolean", "description" to "Run in background mode"),
                    ),
                    "required" to listOf("command"),
                ),
            ),
        )
    }

    @OptIn(DangerousOperation::class)
    override fun execute(arguments: Map<String, Any>): SkillResult {
        val commandText: String = arguments["command"] as? String ?: ""
        val runDetached: Boolean = arguments["detached"] as? Boolean ?: false
        val projectRoot: String = arguments["projectRoot"] as? String ?: ""

        if (commandText.isBlank())
            return makeFailure(ErrorCode.INVALID_PARAMETER, "Missing 'command' parameter")

        val classification: CommandVerdict = classifyCommand(commandText)
        if (classification is CommandVerdict.Blocked) {
            return makeFailure(
                ErrorCode.COMMAND_BLOCKED,
                "Blocked by safety filter: ${classification.description}",
                mapOf("command" to commandText, "rule" to classification.ruleName)
            )
        }

        if (runDetached) return executeDetached(commandText)

        return executeBlocking(commandText, projectRoot)
    }

    private fun executeBlocking(commandText: String, projectRoot: String = ""): SkillResult {
        return try {
            val processBuilder = ProcessBuilder("sh", "-c", commandText)
            processBuilder.redirectErrorStream(false)
            if (projectRoot.isNotBlank()) {
                val dir = java.io.File(projectRoot)
                if (dir.isDirectory) processBuilder.directory(dir)
            }

            val process: Process = processBuilder.start()
            val finished: Boolean = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return makeFailure(
                    ErrorCode.TIMEOUT,
                    "Command timed out after $COMMAND_TIMEOUT_SECONDS seconds",
                    mapOf("command" to commandText),
                )
            }

            val exitCode: Int = process.exitValue()

            val stdoutText: String = readStreamOutput(process.inputStream)
            val stderrText: String = readStreamOutput(process.errorStream)

            makeSuccess(
                mapOf(
                    "command" to commandText,
                    "exitCode" to exitCode,
                    "output" to stdoutText.ifBlank { stderrText },
                    "timedOut" to false,
                ),
            )
        } catch (exception: Exception) {
            makeFailure(ErrorCode.IO_ERROR, exception.message ?: "Failed to execute command", mapOf("command" to commandText))
        }
    }

    @DangerousOperation
    private fun executeDetached(commandText: String): SkillResult {
        return try {
            val logDirectory: Path = ProjectPaths.OUTPUT_DIRECTORY.resolve("run_cmd")
            logDirectory.toFile().mkdirs()
            val logFile = File(logDirectory.toFile(), "${System.currentTimeMillis()}.log")

            val processBuilder = ProcessBuilder("sh", "-c", commandText)
            processBuilder.redirectOutput(logFile)
            processBuilder.redirectErrorStream(true)

            val process: Process = processBuilder.start()
            val processId: Long = process.pid()

            logger.info("Detached command (PID $processId): $commandText -> ${logFile.absolutePath}")

            makeSuccess(
                mapOf(
                    "command" to commandText,
                    "detached" to true,
                    "processId" to processId,
                    "logPath" to logFile.absolutePath,
                    "message" to "Command started in background with PID $processId"
                ),
            )
        } catch (exception: Exception) {
            makeFailure(ErrorCode.IO_ERROR, exception.message ?: "Failed to start detached command", mapOf("command" to commandText))
        }
    }

    private fun readStreamOutput(inputStream: java.io.InputStream): String {
        return try {
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } catch (exception: Exception) {
            logger.warn("Failed to read stream output: {}", exception.message)
            ""
        }
    }
}
