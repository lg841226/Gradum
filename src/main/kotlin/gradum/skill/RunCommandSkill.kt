package gradum.skill

import gradum.DangerousOperation
import gradum.SkillResult
import gradum.makeFailure
import gradum.makeSuccess
import gradum.util.classifyCommand
import gradum.util.CommandVerdict
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("RunCommandSkill")
private const val COMMAND_TIMEOUT_SECONDS: Long = 45
private const val OUTPUT_DIRECTORY_NAME: String = "output"

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
    override val description: String = "Execute a shell command (blocking or detached)"

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
        val reason: String = arguments["reason"] as? String ?: ""
        val runDetached: Boolean = arguments["detached"] as? Boolean ?: false

        if (commandText.isBlank()) {
            return makeFailure("INVALID_PARAMETER", "Missing 'command' parameter")
        }

        val classification: CommandVerdict = classifyCommand(commandText)
        if (classification is CommandVerdict.Blocked) {
            return makeFailure(
                "COMMAND_BLOCKED",
                "Blocked by safety filter: ${classification.description}",
                mapOf("command" to commandText, "rule" to classification.ruleName),
            )
        }

        if (runDetached) {
            return executeDetached(commandText)
        }

        return executeBlocking(commandText)
    }

    private fun executeBlocking(commandText: String): SkillResult {
        return try {
            val processBuilder: ProcessBuilder = ProcessBuilder("sh", "-c", commandText)
            processBuilder.redirectErrorStream(false)

            val process: Process = processBuilder.start()
            val finished: Boolean = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return makeFailure(
                    "TIMEOUT",
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
                    "standardOutput" to stdoutText,
                    "standardError" to stderrText,
                    "timedOut" to false,
                ),
            )
        } catch (e: Exception) {
            makeFailure("IO_ERROR", e.message ?: "Failed to execute command", mapOf("command" to commandText))
        }
    }

    @DangerousOperation
    private fun executeDetached(commandText: String): SkillResult {
        return try {
            val logDirectory: Path = Path.of(OUTPUT_DIRECTORY_NAME, "run_cmd")
            logDirectory.toFile().mkdirs()
            val logFile: File = File(logDirectory.toFile(), "${System.currentTimeMillis()}.log")

            val processBuilder: ProcessBuilder = ProcessBuilder("sh", "-c", commandText)
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
                    "message" to "Command started in background with PID $processId",
                ),
            )
        } catch (e: Exception) {
            makeFailure("IO_ERROR", e.message ?: "Failed to start detached command", mapOf("command" to commandText))
        }
    }

    private fun readStreamOutput(inputStream: java.io.InputStream): String {
        return try {
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } catch (e: Exception) {
            ""
        }
    }
}
