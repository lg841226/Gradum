/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumGitAnalysisService.kt  2026-07-31 20:53:01 Changed by gwy
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
  private const val SCANNED_MESSAGE = "scanned"
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
  var lastErrorMessage by mutableStateOf<String?>(null)
    private set

  private var currentProcess: Process? = null
  private var analysisReader: BufferedReader? = null
  private var currentErrorFile: File? = null

  @Volatile
  private var isScanCancelled: Boolean = false

  @Volatile
  private var isScanCompleted: Boolean = false

  private var totalCommitsBeforeScan = 0
  private var stateBeforeScan = ScanState.IDLE
  private var lastErrorBeforeScan: String? = null

  fun startScan(project: Project) {
    if (scanState == ScanState.SCANNING) return

    val analysisScript = resolveScript(project) ?: run {
      val errorMessage = "Gradum Git analysis script not found ($SCRIPT_RELATIVE_PATH)"
      log.error(errorMessage)
      lastErrorMessage = errorMessage
      scanState = ScanState.FAILED
      return
    }

    val projectRootPath = project.basePath ?: run {
      lastErrorMessage = "The project has no base path to scan."
      scanState = ScanState.FAILED
      return
    }

    if (!File(projectRootPath, ".git").exists()) {
      lastErrorMessage = message("gradum.toolwindow.git.analysis.not.a.repo")
      scanState = ScanState.FAILED
      return
    }

    stateBeforeScan = scanState
    totalCommitsBeforeScan = totalCommits
    lastErrorBeforeScan = lastErrorMessage
    resetScanState()

    ProgressManager.getInstance().run(
      ScanCommitsTask(project, analysisScript, projectRootPath)
    )
  }

  fun cancelScan() {
    isScanCancelled = true
    currentProcess?.destroy()
    currentProcess = null
    restoreStateBeforeScan()
  }

  fun goHome() {
    isScanCancelled = true
    currentProcess?.destroy()
    currentProcess = null
    stateBeforeScan = ScanState.IDLE
    scanState = ScanState.IDLE
  }

  private fun restoreStateBeforeScan() {
    scanState = stateBeforeScan
    if (scanState == ScanState.SUCCESS) {
      totalCommits = totalCommitsBeforeScan
    } else if (scanState == ScanState.FAILED) {
      lastErrorMessage = lastErrorBeforeScan
    }
  }

  private fun resetScanState() {
    currentHash = ""
    scanState = ScanState.SCANNING
    lastErrorMessage = null
    isScanCancelled = false
    isScanCompleted = false
    currentCommit = 0
    totalCommits = 0
  }

  private class ScanCommitsTask(
    project: Project,
    private val analysisScript: File,
    private val projectRootPath: String
  ) : Task.Backgroundable(project, message("gradum.toolwindow.git.analysis.scan.title")) {

    override fun run(indicator: ProgressIndicator) {
      indicator.isIndeterminate = false
      indicator.fraction = 0.0

      var hasTransferredControl = false
      val errorFile = File.createTempFile("gradum-gitstats", ".err")
      currentErrorFile = errorFile
      try {
        val processBuilder = ProcessBuilder(analysisScript.absolutePath, "--jsonl").apply {
          directory(File(projectRootPath))
          redirectError(ProcessBuilder.Redirect.to(errorFile))
        }
        val analysisProcess = processBuilder.start()
        currentProcess = analysisProcess
        val outputReader = BufferedReader(InputStreamReader(analysisProcess.inputStream, StandardCharsets.UTF_8))
        analysisReader = outputReader

        while (!isScanCancelled) {
          val outputLine = outputReader.readLine() ?: break
          if (outputLine.isBlank()) continue
          val jsonRecord = parseRecord(outputLine) ?: continue
          if (isScannedRecord(jsonRecord)) {
            isScanCompleted = true
            hasTransferredControl = true
            return
          }
          handleJsonRecord(jsonRecord, indicator)
        }

        if (isScanCancelled || indicator.isCanceled) {
          restoreStateBeforeScan()
          return
        }
        analysisProcess.waitFor()
        val exitCode = analysisProcess.exitValue()
        if (exitCode == 0) {
          scanState = ScanState.SUCCESS
        } else {
          handleFailure(exitCode, errorFile)
        }
      } catch (exception: Exception) {
        log.warn("Gradum Git scan failed", exception)
        lastErrorMessage = exception.message
        scanState = ScanState.FAILED
      } finally {
        if (!hasTransferredControl) cleanupScanResources()
      }
    }

    override fun onSuccess() {
      if (isScanCompleted) {
        isScanCompleted = false
        ProgressManager.getInstance().run(
          AnalyzeDataTask(project)
        )
      }
    }

    override fun onThrowable(error: Throwable) {
      log.warn("Scan task failed", error)
      lastErrorMessage = error.message
      scanState = ScanState.FAILED
      cleanupScanResources()
    }
  }

  private class AnalyzeDataTask(project: Project) :
    Task.Backgroundable(project, message("gradum.toolwindow.git.analysis.scan.title")) {

    override fun run(indicator: ProgressIndicator) {
      indicator.isIndeterminate = true
      indicator.text = message("gradum.toolwindow.git.analysis.analyzing")

      val analysisProcess = currentProcess
      val outputReader = analysisReader
      val errorLogFile = currentErrorFile
      try {
        if (analysisProcess == null || outputReader == null) {
          if (!isScanCancelled) scanState = ScanState.FAILED
          return
        }
        while (!isScanCancelled) {
          val outputLine = outputReader.readLine() ?: break
          if (outputLine.isBlank()) continue
          val jsonRecord = parseRecord(outputLine) ?: continue
          handleJsonRecord(jsonRecord, indicator)
        }

        if (isScanCancelled || indicator.isCanceled) {
          restoreStateBeforeScan()
          return
        }
        analysisProcess.waitFor()
        val exitCode = analysisProcess.exitValue()
        if (exitCode == 0) scanState = ScanState.SUCCESS
        else handleFailure(exitCode, errorLogFile)
      } catch (exception: Exception) {
        log.warn("Gradum Git analysis failed", exception)
        lastErrorMessage = exception.message
        scanState = ScanState.FAILED
      } finally {
        cleanupScanResources()
      }
    }

    override fun onThrowable(error: Throwable) {
      log.warn("Analysis task failed", error)
      lastErrorMessage = error.message
      scanState = ScanState.FAILED
      cleanupScanResources()
    }
  }

  private fun cleanupScanResources() {
    currentProcess = null
    analysisReader = null
    currentErrorFile?.delete()
    currentErrorFile = null
  }

  private fun parseRecord(line: String): JsonObject? = try {
    Json.parseToJsonElement(line).jsonObject
  } catch (exception: Exception) {
    log.warn("Failed to parse JSON output line: $line", exception)
    null
  }

  private fun isScannedRecord(record: JsonObject): Boolean =
    record[FIELD_MESSAGE]?.jsonPrimitive?.contentOrNull?.equals(SCANNED_MESSAGE, ignoreCase = true) == true

  private fun handleJsonRecord(record: JsonObject, indicator: ProgressIndicator) {
    val messageType = record[FIELD_MESSAGE]?.jsonPrimitive?.contentOrNull ?: return

    if (messageType.equals(SCANNING_MESSAGE, ignoreCase = true)) {
      updateScanningProgress(record, indicator)
      return
    }

    val errorCode = record[FIELD_ERROR]?.jsonPrimitive?.contentOrNull
    if (errorCode != null) {
      lastErrorMessage = friendlyErrorMessage(errorCode, messageType)
    } else if (record[FIELD_LEVEL]?.jsonPrimitive?.contentOrNull?.equals(LEVEL_ERROR, ignoreCase = true) == true) {
      lastErrorMessage = messageType
      log.warn("Gradum Git analysis script error: $record")
    }
  }

  private fun friendlyErrorMessage(errorCode: String, fallback: String): String {
    val localized = message("$ERROR_CODE_PREFIX$errorCode")
    return if (localized.isNotBlank() && !localized.startsWith("???")) {
      localized
    } else {
      fallback
    }
  }

  private fun updateScanningProgress(record: JsonObject, indicator: ProgressIndicator) {
    val currentCommitIndex = record[FIELD_CURRENT]?.jsonPrimitive?.intOrNull ?: return
    val totalCommitCount = record[FIELD_TOTAL]?.jsonPrimitive?.intOrNull ?: return
    val commitHash = record[FIELD_HASH]?.jsonPrimitive?.contentOrNull.orEmpty()

    currentCommit = currentCommitIndex
    totalCommits = totalCommitCount
    currentHash = commitHash

    indicator.fraction = if (totalCommitCount > 0) currentCommitIndex.toDouble() / totalCommitCount else 0.0
    indicator.text = message("gradum.toolwindow.git.analysis.progress", currentCommitIndex, totalCommitCount)
  }

  private fun handleFailure(exitCode: Int, errorFile: File?) {
    if (lastErrorMessage == null) {
      lastErrorMessage = readErrorFile(errorFile) ?: "The analysis script exited with code $exitCode."
    }
    scanState = ScanState.FAILED
  }

  private fun readErrorFile(errorFile: File?): String? {
    if (errorFile == null || !errorFile.exists() || errorFile.length() == 0L) return null
    return errorFile.readText().trim().ifBlank { null }
  }

  private fun resolveScript(project: Project): File? {
    val jarPath = PathManager.getJarPathForClass(GradumGitAnalysisService::class.java) ?: ""
    findScriptUpFrom(File(jarPath))
    return findScriptUpFrom(project.basePath?.let { File(it) })
      ?: findScriptUpFrom(File(jarPath)) ?: extractBundledScript()
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
