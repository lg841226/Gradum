/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumGitAnalysisService.kt  2026-07-31 16:46:13 Changed by gwy
 */
package gradum.idea

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import gradum.idea.bundle.GradumBundle.message
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Runs the `scripts/git_stats_log/git_stats.py` analysis in the IDE
 * background, surfacing progress in the status bar via
 * [Task.Backgroundable]. The Python script streams pretty-printed JSON
 * records to stdout (each record spans several lines); while it is walking
 * the commit history it emits a `scanning commit` record carrying `hash`,
 * `current` and `total`, which drives the determinate progress indicator.
 *
 * Scan lifecycle state is held in Compose [mutableStateOf] properties so
 * the tool window's Compose UI can observe `IDLE → SCANNING → SUCCESS /
 * FAILED` without needing a local `remember` copy (the tool window content
 * is disposed when the panel is collapsed, but this object survives).
 */
object GradumGitAnalysisService {

  enum class ScanState { IDLE, SCANNING, SUCCESS, FAILED }

  private const val LEVEL_ERROR = "ERROR"
  private const val FIELD_MESSAGE = "message"
  private const val FIELD_LEVEL = "level"
  private const val FIELD_CURRENT = "current"
  private const val FIELD_TOTAL = "total"
  private const val FIELD_HASH = "hash"
  private const val FIELD_ERROR = "error"
  private const val ERROR_CODE_PREFIX = "gradum.gitstats.error."
  private const val SCANNING_MESSAGE = "scanning commit"
  private const val SCRIPT_RELATIVE_PATH = "scripts/git_stats_log/git_stats.py"
  private const val CONFIG_RELATIVE_PATH = "scripts/configs.jsonc"

  private val log: Logger = Logger.getInstance(GradumGitAnalysisService::class.java)

  var scanState by mutableStateOf(ScanState.IDLE)
    private set
  var currentHash by mutableStateOf("")
    private set
  var currentCommit by mutableStateOf(0)
    private set
  var totalCommits by mutableStateOf(0)
    private set
  var lastError by mutableStateOf<String?>(null)
    private set

  private var currentProcess: Process? = null

  @Volatile
  private var isScanCancelled: Boolean = false

  fun startScan(project: Project) {
    if (scanState == ScanState.SCANNING) return

    val analysisScript = resolveScript(project) ?: run {
      val errorMessage = "Gradum Git analysis script not found ($SCRIPT_RELATIVE_PATH)"
      log.error(errorMessage)
      lastError = errorMessage
      scanState = ScanState.FAILED
      return
    }

    val projectRootPath = project.basePath ?: run {
      lastError = "The project has no base path to scan."
      scanState = ScanState.FAILED
      return
    }

    if (!File(projectRootPath, ".git").exists()) {
      lastError = message("gradum.toolwindow.git.analysis.not.a.repo")
      scanState = ScanState.FAILED
      return
    }

    resetScanState()

    ProgressManager.getInstance()
      .run(object : Task.Backgroundable(project, message("gradum.toolwindow.git.analysis.scan.title")) {
        override fun run(progressIndicator: ProgressIndicator) {
          progressIndicator.isIndeterminate = false
          progressIndicator.fraction = 0.0

          val errorFile = File.createTempFile("gradum-gitstats", ".err")
          try {
            val processBuilder = ProcessBuilder(analysisScript.absolutePath, "--jsonl").apply {
              directory(File(projectRootPath))
              redirectError(ProcessBuilder.Redirect.to(errorFile))
            }

            val analysisProcess = processBuilder.start()
            currentProcess = analysisProcess

            BufferedReader(InputStreamReader(analysisProcess.inputStream, StandardCharsets.UTF_8)).use { reader ->
              consumeOutputLines(reader, progressIndicator)
            }

            if (!isScanCancelled) analysisProcess.waitFor()

            currentProcess = null
            updateScanStateOnCompletion(analysisProcess, progressIndicator, errorFile)

          } catch (exception: Exception) {
            log.warn("Gradum Git analysis failed", exception)
            lastError = exception.message
            scanState = ScanState.FAILED
          } finally {
            currentProcess = null
            errorFile.delete()
          }
        }
      })
  }

  fun cancelScan() {
    isScanCancelled = true
    currentProcess?.destroy()
    currentProcess = null
    scanState = ScanState.IDLE
  }

  private fun resetScanState() {
    currentHash = ""
    scanState = ScanState.SCANNING
    lastError = null
    isScanCancelled = false
    currentCommit = 0
    totalCommits = 0
  }

  private fun updateScanStateOnCompletion(process: Process, indicator: ProgressIndicator, errorFile: File) {
    scanState = when {
      isScanCancelled || indicator.isCanceled -> ScanState.IDLE
      process.exitValue() == 0 -> ScanState.SUCCESS
      else -> {
        if (lastError == null) {
          lastError = readErrorFile(errorFile) ?: "The analysis script exited with code ${process.exitValue()}."
        }
        ScanState.FAILED
      }
    }
  }

  /** Reads stdout line by line; the script runs in `--jsonl` mode so each
   * line is one complete JSON record. Non-JSON lines are skipped. */
  private fun consumeOutputLines(reader: BufferedReader, indicator: ProgressIndicator) {
    while (!isScanCancelled) {
      val outputLine = reader.readLine() ?: break
      if (outputLine.isBlank()) continue

      val jsonRecord = try {
        Json.parseToJsonElement(outputLine).jsonObject
      } catch (exception: Exception) {
        log.warn("Failed to parse JSON output line: $outputLine", exception)
        continue
      }

      handleJsonRecord(jsonRecord, indicator)
    }
  }

  private fun handleJsonRecord(record: JsonObject, indicator: ProgressIndicator) {
    val messageType = record[FIELD_MESSAGE]?.jsonPrimitive?.contentOrNull ?: return

    if (messageType.equals(SCANNING_MESSAGE, ignoreCase = true)) {
      updateProgressFromRecord(record, indicator)
      return
    }

    val errorCode = record[FIELD_ERROR]?.jsonPrimitive?.contentOrNull
    if (errorCode != null) {
      lastError = friendlyErrorMessage(errorCode, messageType)
    } else if (record[FIELD_LEVEL]?.jsonPrimitive?.contentOrNull?.equals(LEVEL_ERROR, ignoreCase = true) == true) {
      lastError = messageType
      log.warn("Gradum Git analysis script error: $record")
    }
  }

  /** Maps a script error code (e.g. `E_NOT_GIT_REPO`) to a friendly,
   *  localized message; falls back to the raw script message when the
   *  bundle has no entry for that code. */
  private fun friendlyErrorMessage(errorCode: String, fallback: String): String {
    val localized = message("$ERROR_CODE_PREFIX$errorCode")
    return if (localized.isNotBlank() && !localized.startsWith("???")) {
      localized
    } else {
      fallback
    }
  }

  private fun updateProgressFromRecord(record: JsonObject, indicator: ProgressIndicator) {
    val currentCommitIndex = record[FIELD_CURRENT]?.jsonPrimitive?.intOrNull ?: return
    val totalCommitCount = record[FIELD_TOTAL]?.jsonPrimitive?.intOrNull ?: return
    val commitHash = record[FIELD_HASH]?.jsonPrimitive?.contentOrNull.orEmpty()

    currentCommit = currentCommitIndex
    totalCommits = totalCommitCount
    currentHash = commitHash

    indicator.fraction = if (totalCommitCount > 0) currentCommitIndex.toDouble() / totalCommitCount else 0.0
    indicator.text = message("gradum.toolwindow.git.analysis.progress", currentCommitIndex, totalCommitCount)
  }

  private fun readErrorFile(errorFile: File): String? {
    if (!errorFile.exists() || errorFile.length() == 0L) return null
    return errorFile.readText().trim().ifBlank { null }
  }

  private fun resolveScript(project: Project): File? {
    val jarPath = PathManager.getJarPathForClass(GradumGitAnalysisService::class.java) ?: ""
    findScriptUpFrom(File(jarPath))
    return findScriptUpFrom(project.basePath?.let { File(it) })
      ?: findScriptUpFrom(File(jarPath))
      ?: extractBundledScript()
  }

  private fun findScriptUpFrom(startDirectory: File?): File? {
    var currentSearchDir: File? = startDirectory
    while (currentSearchDir != null) {
      val scriptFile = File(currentSearchDir, SCRIPT_RELATIVE_PATH)
      if (scriptFile.canExecute()) return scriptFile
      currentSearchDir = currentSearchDir.parentFile
    }
    return null
  }

  /** Falls back to the script + config bundled as plugin resources. */
  private fun extractBundledScript(): File? {
    return try {
      val tempRoot: Path = Paths.get(PathManager.getTempDir().toString(), "gradum", "gitstats")
      Files.createDirectories(tempRoot.resolve("scripts/git_stats_log"))

      val scriptPath: Path = tempRoot.resolve(SCRIPT_RELATIVE_PATH)
      val configPath: Path = tempRoot.resolve(CONFIG_RELATIVE_PATH)

      copyResource("/$SCRIPT_RELATIVE_PATH", scriptPath)
      copyResource("/$CONFIG_RELATIVE_PATH", configPath)

      scriptPath.toFile().apply { setExecutable(true) }
    } catch (exception: Exception) {
      log.warn("Failed to extract bundled Gradum Git analysis script", exception)
      null
    }
  }

  private fun copyResource(resourcePath: String, target: Path) {
    GradumGitAnalysisService::class.java.getResourceAsStream(resourcePath)?.use { inputStream ->
      Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
