/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumGitAnalysisService.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.idea

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import gradum.idea.GradumGitAnalysisService.handleFailure
import gradum.idea.GradumGitAnalysisService.isScanCompleted
import gradum.idea.GradumGitAnalysisService.restoreStateBeforeScan
import gradum.idea.utils.GradumBundle.message
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalDate.parse
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * A single audit finding (SXXXX code) reported by the analysis script.
 *
 * Every finding record carries the raw script fields verbatim; presentation
 * and localization are left to the UI layer. [params] holds the raw values
 * that were previously baked into a message string, so the UI can render a
 * localized message via [formatMessage].
 */
data class AuditFinding(
  val index: Int,
  val code: String,
  val type: String,
  val hash: String,
  val date: String,
  val days: String,
  val level: String,
  val body: String = "",
  val author: String = "",
  val subject: String = "",
  val params: Map<String, Any?>,
) {
  /**
   * Renders this finding in an MSVC-style diagnostic line, e.g.
   * `critical S1001: A single commit changed +500/-200 lines, exceeding the
   * threshold of 500`. The numeric params are formatted with the same
   * precision the script'store Python templates used.
   */
  fun formatMessage(): String = "$level $code: ${formatBody()}"

  /**
   * Renders only the localized body of this finding, e.g. `A single commit
   * changed +500/-200 lines, exceeding the threshold of 500`. The severity
   * and code are left out so a status icon can convey them instead. For the
   * codes in [AUDIT_HASH_WEAVED_CODES] the commit hash is appended as the
   * final message argument so it reads naturally inside the sentence.
   */
  fun formatBody(): String {
    val args = AUDIT_PARAM_ORDER[code].orEmpty().map { spec ->
      formatAuditParam(value = params[spec.name], spec.decimals)
    }.toMutableList()
    if (code in AUDIT_HASH_WEAVED_CODES) args.add(hash)
    return message(key = "$AUDIT_MESSAGE_PREFIX$code", *args.toTypedArray())
  }
}

/**
 * Codes whose bundle templates weave the commit hash into the sentence
 * (as the last placeholder). Only these findings carry a real per-commit
 * hash; aggregate/statistical findings keep their original wording.
 */
private val AUDIT_HASH_WEAVED_CODES = setOf(
  "S1001", "S1004", "S2003", "S2006", "S2012", "S3001", "S3003"
)

/**
 * True when this finding is tied to a specific commit and carries a usable
 * commit hash. Aggregate/statistical findings (`hash` is `-`, `Cluster #N`,
 * `Recent Spike`, …) return false. This is what gates the "show commit
 * details" action button in the tool window.
 */
internal fun AuditFinding.hasRealCommitHash(): Boolean =
  code in AUDIT_HASH_WEAVED_CODES && hash.isNotBlank() && hash != "-"

/** Stable string key for a finding, used for reviewed-set tracking. */
internal fun findingKey(f: AuditFinding) = "${f.code}|${f.hash}|${f.index}"

/**
 * Opens the given commit hash on GitHub. Resolves the `origin` remote of
 * the project'store git repository (`git remote get-url origin`) on a
 * background thread, converts the URL into a browsable web link, and
 * opens it with [BrowserUtil.browse]. A short hash works on GitHub, so
 * [hash] is used as-is. SSH forms (`git@github.com:o/r.git`)
 * and `https://github.com/o/r.git` both resolve.
 */
internal fun openCommitOnGitHub(project: Project, hash: String) {
  if (hash.isBlank()) return
  val basePath = project.basePath ?: return
  Thread {
    val remoteUrl = runCatching {
      ProcessBuilder("git", "remote", "get-url", "origin")
        .directory(File(basePath))
        .redirectErrorStream(true)
        .start()
        .let { process ->
          val output = process.inputStream.bufferedReader().readText().trim()
          process.waitFor()
          output
        }
    }.getOrNull().orEmpty()
    val webUrl = githubWebUrl(remoteUrl, hash)
    if (webUrl != null) {
      ApplicationManager.getApplication().invokeLater {
        BrowserUtil.browse(webUrl)
      }
    }
  }.apply {
    isDaemon = true
    name = "gradum-open-commit-on-github"
  }.start()
}

/** Converts a git remote URL + short hash into a GitHub commit URL, or null. */
internal fun githubWebUrl(remoteUrl: String, hash: String): String? {
  val trimmed = remoteUrl.trim().removeSuffix(".git")
  val hostPath =
    when {
      trimmed.startsWith(prefix = "git@") && trimmed.contains(char = ':') ->
        trimmed.removePrefix("git@").replaceFirst(':', '/')

      trimmed.startsWith(prefix = "https://github.com/") || trimmed.startsWith(prefix = "http://github.com/") ->
        trimmed.substringAfter(delimiter = "://")

      else -> return null
    }
  val (host, path) = hostPath.split('/', limit = 2).let {
    if (it.size < 2) return null
    else it[0] to it[1]
  }
  if (host != "github.com") return null
  return "https://github.com/$path/commit/$hash"
}

private const val AUDIT_MESSAGE_PREFIX = "gradum.audit."

/**
 * Ordered parameter spec for one SXXXX finding, mirroring the placeholder
 * order of its bundle template. `decimals` is null for string params.
 */
private data class AuditParamSpec(val name: String, val decimals: Int?)

/** String param, rendered verbatim. */
private fun str(name: String): AuditParamSpec = AuditParamSpec(name, null)

/** Numeric param rounded to [decimals] decimal places. */
private fun dec(name: String, decimals: Int): AuditParamSpec = AuditParamSpec(name, decimals)

/**
 * DSL builder for [AUDIT_PARAM_ORDER]. Each `S(code, ...)` line lists the
 * params in placeholder order; plain strings become int params (0 decimals).
 */
private class AuditParamOrderBuilder {
  private val entries = LinkedHashMap<String, List<AuditParamSpec>>()

  fun store(code: String, vararg params: Any) {
    entries[code] = params.map { param ->
      when (param) {
        is String -> AuditParamSpec(param, 0)
        is AuditParamSpec -> param
        else -> error("unsupported audit param spec: $param")
      }
    }
  }

  fun build(): Map<String, List<AuditParamSpec>> = entries
}

private fun auditParamOrder(block: AuditParamOrderBuilder.() -> Unit): Map<String, List<AuditParamSpec>> =
  AuditParamOrderBuilder().apply(block).build()

/**
 * code -> ordered params to substitute into `gradum.audit.<code>`. Decimals
 * match the Python format spec that used to produce the message (e.g. `:.1f`).
 */
private val AUDIT_PARAM_ORDER: Map<String, List<AuditParamSpec>> = auditParamOrder {
  store(code = "S1001", "add", "dels", "threshold")
  store(code = "S1002", "ratio", "dels", "add")
  store(code = "S1003", str("author"), "total")
  store(code = "S1004", "lines", "pct")
  store(code = "S2001", "count", "lines", "start_idx", "end_idx")
  store(code = "S2002", "total", "count", "days")
  store(code = "S2003", "lines")
  store(code = "S2004", dec("ratio", 1), "total")
  store(code = "S2005", "avg", "threshold")
  store(code = "S2006", "n", dec("ratio", 1))
  store(code = "S2007", dec("cv", 3), dec("threshold", 2))
  store(code = "S2008", dec("avg", 1), "target")
  store(code = "S2009", dec("density", 1), "days")
  store(code = "S2010", "pct", "count", "total", str("coauthors"))
  store(code = "S2011", "n", "pct", "total")
  store(code = "S2012", "days", "half")
  store(code = "S2013", "count", str("agents"))
  store(code = "S3001", "lines", "core", "files")
  store(code = "S3002", "dels", "add", "ratio")
  store(code = "S3003", str("author"), str("pattern"))
  store(code = "S3004", "pct", "count", "total")
  store(code = "S3005", "count")
  store(code = "S3006", "total")
  store(code = "S3007", "count", "total", "pct", "limit")
  store(code = "S3008", "count", "total", "pct")
  store(code = "S4001", dec("ratio", 1))
  store(code = "S4002", "lines", "files")
}

private fun formatAuditParam(value: Any?, decimals: Int?): String {
  if (value == null) return ""
  if (decimals == null) return value.toString()
  return String.format(
    Locale.ROOT,
    format = "%.${decimals}f",
    value.toString().toDoubleOrNull() ?: 0.0
  )
}

/**
 * The four fixed audit groups. Findings are bucketed into these instead of
 * their many SXXXX `type` labels so the report stays concise. Each group
 * name is localized via [label].
 */
internal enum class AuditGroup(val labelKey: String) {
  SUSPECTED_AI_CODE(labelKey = "gradum.audit.group.suspected_ai_code"),
  ENGINEERING_RISK_ISSUES(labelKey = "gradum.audit.group.engineering_risk_issues"),
  TEAM_PROCESS_WATCH(labelKey = "gradum.audit.group.team_process_watch"),
  OTHER(labelKey = "gradum.audit.group.other");

  fun label(): String = message(labelKey)
}

/** Buckets a finding into one of the four [AuditGroup]store by its SXXXX code. */
internal fun auditGroupOf(code: String): AuditGroup = when (code) {
  "S2005", "S2006", "S2007", "S2008", "S2009", "S2010", "S2013" -> AuditGroup.SUSPECTED_AI_CODE
  "S1001", "S1002", "S1004", "S2001", "S2002", "S2003", "S2004",
  "S3001", "S3002", "S3007", "S4001" -> AuditGroup.ENGINEERING_RISK_ISSUES

  "S1003", "S2011", "S2012", "S3003", "S3004", "S3005", "S3006",
  "S3008", "S4002" -> AuditGroup.TEAM_PROCESS_WATCH

  else -> AuditGroup.OTHER
}

/** Severity ordering used to sort findings most-severe-first. */
internal fun severityRank(level: String): Int = when (level) {
  "critical" -> 0
  "alert" -> 1
  "watch" -> 2
  "normal" -> 3
  else -> 4
}

/**
 * Drives the Git history audit end to end.
 *
 * The pipeline has two stages, both running off the same child process:
 *
 * 1. [ScanCommitsTask] walks every commit and forwards progress lines from
 *    the script to a [ProgressIndicator]. When the script emits the
 *    `scanned` marker it hands the remaining work to stage 2.
 * 2. [AnalyzeDataTask] keeps reading the script'store stdout, now collecting the
 *    SXXXX finding records and applying the project-wide audits, until the
 *    process exits.
 *
 * Findings are captured incrementally into [auditFindings] so the UI can
 * render them live; the final result only depends on the records that were
 * emitted before the process finished. Script stdout is one JSON object per
 * line (JSONL); stderr is captured to a temp file for error reporting.
 */
object GradumGitAnalysisService {

  /**
   * The lifecycle of a scan, surfaced to the UI via Compose state.
   *
   * - [IDLE]      the report is not open.
   * - [SCANNING]  the child process is running (progress is streamed).
   * - [SUCCESS]   the process exited cleanly and findings are available.
   * - [FAILED]    the process errored; [GradumGitAnalysisService.lastErrorMessage] explains why.
   */
  enum class ScanState { IDLE, SCANNING, SUCCESS, FAILED }

  /** Sentinel log level that marks a fatal script error in the output stream. */
  private const val LEVEL_ERROR = "ERROR"

  private const val FIELD_MESSAGE = "message"
  private const val FIELD_LEVEL = "level"
  private const val FIELD_CURRENT = "current"
  private const val FIELD_TOTAL = "total"
  private const val FIELD_HASH = "hash"
  private const val FIELD_CODE = "code"
  private const val FIELD_TYPE = "type"
  private const val FIELD_INDEX = "index"
  private const val FIELD_DATE = "date"
  private const val FIELD_DAYS = "days"
  private const val FIELD_SUBJECT = "subject"
  private const val FIELD_AUTHOR = "author"
  private const val FIELD_BODY = "body"
  private const val FIELD_PARAMS = "params"
  private const val FIELD_ERROR = "error"

  private const val FINDING_CODE_PREFIX = "S"
  private const val SCANNING_MESSAGE = "scanning commit"
  private const val SCANNED_MESSAGE = "scanned"
  private const val ERROR_CODE_PREFIX = "gradum.gitstats.error."
  private const val SCRIPT_RELATIVE_PATH = "scripts/git_stats_log/git_stats.py"
  private const val CONFIG_RELATIVE_PATH = "scripts/configs.jsonc"

  private val log: Logger = Logger.getInstance(GradumGitAnalysisService::class.java)


  var scanState by mutableStateOf(value = ScanState.IDLE)
    private set

  var currentHash by mutableStateOf(value = "")
    private set

  var currentCommit by mutableStateOf(value = 0)
    private set

  var totalCommits by mutableStateOf(value = 0)
    private set

  var lastErrorMessage by mutableStateOf<String?>(value = null)
    private set

  var auditFindings by mutableStateOf<List<AuditFinding>>(value = emptyList())
    private set

  var overallLevel by mutableStateOf<String?>(value = null)
    private set

  var qualityBand by mutableStateOf<String?>(value = null)
    private set

  var currentBranch by mutableStateOf<String?>(value = null)
    private set

  var scanCompletedAt by mutableStateOf(value = 0L)
    private set

  var bannerDismissed by mutableStateOf(value = false)

  private var currentProcess: Process? = null
  private var analysisReader: BufferedReader? = null
  private var currentErrorFile: File? = null

  /** Set to request an orderly cancellation of the current scan. */
  @Volatile
  private var isScanCancelled: Boolean = false

  /** Set once the script reports the `scanned` marker; triggers the hand-off to [AnalyzeDataTask]. */
  @Volatile
  private var isScanCompleted: Boolean = false

  private var totalCommitsBeforeScan = 0
  private var findingsBeforeScan: List<AuditFinding> = emptyList()
  private var stateBeforeScan = ScanState.IDLE
  private var lastErrorBeforeScan: String? = null

  /**
   * Starts a full analysis of the given project.
   *
   * The script is resolved (project copy first, then jar-adjacent copy, then
   * the bundled resource), the project is checked for a `.git` directory,
   * the previous state is snapshotted, and the two-phase scan is launched via
   * [ProgressManager]. A scan that is already running is ignored.
   */
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
    findingsBeforeScan = auditFindings
    lastErrorBeforeScan = lastErrorMessage
    resetScanState()

    ProgressManager.getInstance().run(
      ScanCommitsTask(project, analysisScript, projectRootPath)
    )
  }

  /**
   * Cancels the running scan and restores the state that existed before
   * [startScan] was called. The process is destroyed and the snapshot from
   * [restoreStateBeforeScan] is reapplied.
   */
  fun cancelScan() {
    isScanCancelled = true
    currentProcess?.destroy()
    currentProcess = null
    restoreStateBeforeScan()
  }

  /**
   * Closes the report and returns the tool window to its idle home screen.
   * Unlike [cancelScan], the pre-scan state is deliberately discarded.
   */
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
      auditFindings = findingsBeforeScan
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
    auditFindings = emptyList()
    overallLevel = null
    qualityBand = null
    currentBranch = null
  }

  /**
   * Stage one: walks every commit in the repository.
   *
   * The script is launched with `--jsonl`; its stdout is consumed line by
   * line. Per-commit `scanning commit` records update the progress indicator,
   * and any premature SXXXX findings are collected too. When the script emits
   * the `scanned` marker, [isScanCompleted] is set and control is handed to
   * [AnalyzeDataTask] via [onSuccess]; the process handle is left running and
   * resource cleanup is deferred so stage two can keep reading stdout.
   */
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
            currentBranch = jsonRecord["branch"]?.jsonPrimitive?.contentOrNull
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
          scanCompletedAt = System.currentTimeMillis()
          scanState = ScanState.SUCCESS
        } else handleFailure(exitCode, errorFile)

      } catch (exception: Exception) {
        log.warn("Gradum Git scan failed: ", exception)
        lastErrorMessage = exception.message
        scanState = ScanState.FAILED
      } finally {
        if (!hasTransferredControl) cleanupScanResources()
      }
    }

    override fun onSuccess() {
      if (isScanCompleted) {
        isScanCompleted = false
        ProgressManager.getInstance().run(AnalyzeDataTask(project))
      }
    }

    override fun onThrowable(error: Throwable) {
      log.warn("Scan task failed: ", error)
      lastErrorMessage = error.message
      scanState = ScanState.FAILED
      cleanupScanResources()
    }
  }

  /**
   * Stage two: consumes the project-wide analysis output.
   *
   * Runs after [ScanCommitsTask] reports the `scanned` marker, continuing to
   * read the same process stdout until EOF. This pass collects the findings
   * that live on the `scanned`/`quality`/period records the script emits after
   * the per-commit walk. On a clean exit the state is set to
   * [ScanState.SUCCESS]; otherwise [handleFailure] surfaces the error.
   */
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
        if (exitCode == 0) {
          scanCompletedAt = System.currentTimeMillis()
          scanState = ScanState.SUCCESS
        } else handleFailure(exitCode, errorFile = errorLogFile)
      } catch (exception: Exception) {
        log.warn("Gradum Git analysis failed: ", exception)
        lastErrorMessage = exception.message
        scanState = ScanState.FAILED
      } finally {
        cleanupScanResources()
      }
    }

    override fun onThrowable(error: Throwable) {
      log.warn("Analysis task failed: ", error)
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
    Json.parseToJsonElement(string = line).jsonObject
  } catch (exception: Exception) {
    log.warn("Failed to parse JSON output line: $line", exception)
    null
  }

  private fun isScannedRecord(record: JsonObject): Boolean =
    record[FIELD_MESSAGE]
      ?.jsonPrimitive
      ?.contentOrNull
      ?.equals(other = SCANNED_MESSAGE, ignoreCase = true) == true

  /** Routes one JSONL record to its handler: findings, progress, quality, or error. */
  private fun handleJsonRecord(record: JsonObject, indicator: ProgressIndicator) {
    val auditFinding = parseAuditFinding(jsonRecord = record)
    if (auditFinding != null) {
      auditFindings = auditFindings + auditFinding
      return
    }

    val messageType = record[FIELD_MESSAGE]?.jsonPrimitive?.contentOrNull ?: return

    if (messageType.equals(other = SCANNING_MESSAGE, ignoreCase = true)) {
      updateScanningProgress(record, indicator)
      return
    }

    if (messageType.equals(other = "analyzed", ignoreCase = true))
      overallLevel = record["overall_level"]?.jsonPrimitive?.contentOrNull

    if (messageType.equals(other = "quality", ignoreCase = true))
      qualityBand = record["band"]?.jsonPrimitive?.contentOrNull

    val errorCode = record[FIELD_ERROR]?.jsonPrimitive?.contentOrNull
    if (errorCode != null) {
      lastErrorMessage = friendlyErrorMessage(errorCode, fallback = messageType)
    } else if (record[FIELD_LEVEL]?.jsonPrimitive?.contentOrNull
        ?.equals(other = LEVEL_ERROR, ignoreCase = true) == true
    ) {
      lastErrorMessage = messageType
      log.warn("Gradum Git analysis script error: $record")
    }
  }

  private fun parseAuditFinding(jsonRecord: JsonObject): AuditFinding? {
    val findingCode = jsonRecord[FIELD_CODE]?.jsonPrimitive?.contentOrNull ?: return null
    if (!findingCode.startsWith(FINDING_CODE_PREFIX)) return null
    return AuditFinding(
      code = findingCode,
      params = parseAuditParams(jsonRecord),
      index = jsonRecord[FIELD_INDEX]?.jsonPrimitive?.intOrNull ?: -1,
      type = jsonRecord[FIELD_TYPE]?.jsonPrimitive?.contentOrNull.orEmpty(),
      hash = jsonRecord[FIELD_HASH]?.jsonPrimitive?.contentOrNull.orEmpty(),
      date = jsonRecord[FIELD_DATE]?.jsonPrimitive?.contentOrNull.orEmpty(),
      days = jsonRecord[FIELD_DAYS]?.jsonPrimitive?.contentOrNull.orEmpty(),
      body = jsonRecord[FIELD_BODY]?.jsonPrimitive?.contentOrNull.orEmpty(),
      level = jsonRecord[FIELD_LEVEL]?.jsonPrimitive?.contentOrNull.orEmpty(),
      author = jsonRecord[FIELD_AUTHOR]?.jsonPrimitive?.contentOrNull.orEmpty(),
      subject = jsonRecord[FIELD_SUBJECT]?.jsonPrimitive?.contentOrNull.orEmpty()
    )
  }

  private fun parseAuditParams(jsonRecord: JsonObject): Map<String, Any?> {
    val paramsElement = jsonRecord[FIELD_PARAMS] as?
      JsonObject ?: return emptyMap()
    return paramsElement.mapValues { (_, value) ->
      parseParamValue(element = value)
    }
  }

  private fun parseParamValue(element: JsonElement): Any? = when (element) {
    is JsonPrimitive -> when {
      element.isString -> element.content
      element.booleanOrNull != null -> element.boolean
      element.longOrNull != null -> element.long
      else -> element.doubleOrNull
    }

    else -> element.toString()
  }

  /** Localizes a script error code, falling back to the raw script message. */
  private fun friendlyErrorMessage(errorCode: String, fallback: String): String {
    val localized = message(key = "$ERROR_CODE_PREFIX$errorCode")
    return if (localized.isNotBlank() && !localized.startsWith(prefix = "???"))
      localized else fallback
  }

  private fun updateScanningProgress(record: JsonObject, indicator: ProgressIndicator) {
    val currentCommitIndex = record[FIELD_CURRENT]?.jsonPrimitive?.intOrNull ?: return
    val totalCommitCount = record[FIELD_TOTAL]?.jsonPrimitive?.intOrNull ?: return
    val commitHash = record[FIELD_HASH]?.jsonPrimitive?.contentOrNull.orEmpty()

    currentCommit = currentCommitIndex
    totalCommits = totalCommitCount
    currentHash = commitHash

    indicator.fraction =
      if (totalCommitCount > 0) currentCommitIndex.toDouble() / totalCommitCount
      else 0.0
    indicator.text = message("gradum.toolwindow.git.analysis.progress", currentCommitIndex, totalCommitCount)
  }

  private fun handleFailure(exitCode: Int, errorFile: File?) {
    if (lastErrorMessage == null) {
      lastErrorMessage = readErrorFile(errorFile)
        ?: "The analysis script exited with code $exitCode."
    }
    scanState = ScanState.FAILED
  }

  private fun readErrorFile(errorFile: File?): String? {
    if (errorFile == null || !errorFile.exists() || errorFile.length() == 0L) return null
    return errorFile.readText().trim().ifBlank { null }
  }

  /** Resolves the script: project copy, then jar-adjacent copy, then the bundled resource. */
  private fun resolveScript(project: Project): File? {
    val jarPath = PathManager.getJarPathForClass(GradumGitAnalysisService::class.java) ?: ""
    findScriptUpFrom(startDirectory = File(jarPath))
    return findScriptUpFrom(startDirectory = project.basePath?.let { File(it) })
      ?: findScriptUpFrom(startDirectory = File(jarPath)) ?: extractBundledScript()
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

  /** Copies the script + config out of the plugin jar and marks the script executable. */
  private fun extractBundledScript(): File? {
    return try {
      val tempRoot: Path = Paths.get(PathManager.getTempDir().toString(), "gradum", "gitstats")
      Files.createDirectories(tempRoot.resolve("scripts/git_stats_log"))

      val scriptPath: Path = tempRoot.resolve(SCRIPT_RELATIVE_PATH)
      val configPath: Path = tempRoot.resolve(CONFIG_RELATIVE_PATH)

      copyResource(resourcePath = "/$SCRIPT_RELATIVE_PATH", target = scriptPath)
      copyResource(resourcePath = "/$CONFIG_RELATIVE_PATH", target = configPath)

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

/** Human-readable severity label, localized via the bundle. */
internal fun severityLabel(level: String): String = when (level) {
  "critical" -> message("gradum.toolwindow.git.analysis.severity.very.high")
  "alert" -> message("gradum.toolwindow.git.analysis.severity.high")
  "watch" -> message("gradum.toolwindow.git.analysis.severity.watch")
  else -> message("gradum.toolwindow.git.analysis.severity.information")
}

/** Formats a date string as relative time (e.g. "3 days ago" or "just now"). */
internal fun relativeTime(dateString: String): String {
  if (dateString.isBlank() || dateString == "-") return ""
  return try {
    val date = parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE)
    val days = ChronoUnit.DAYS.between(date, LocalDate.now())
    when {
      days <= 0 -> message("gradum.toolwindow.git.analysis.time.just.now")
      days == 1L -> message("gradum.toolwindow.git.analysis.time.day.ago")
      else -> message("gradum.toolwindow.git.analysis.time.days.ago", days)
    }
  } catch (_: Exception) {
    ""
  }
}

/** Formats an epoch-millis timestamp as relative time with minute/hour precision. */
internal fun scanCompletedAgo(millis: Long): String {
  if (millis <= 0L) return ""
  val elapsed = System.currentTimeMillis() - millis
  if (elapsed < 0L) return ""
  val seconds = elapsed / 1000
  return when {
    seconds < 60 -> message("gradum.toolwindow.git.analysis.time.just.now")
    seconds < 3600 -> {
      val minutes = (seconds / 60).toInt()
      message("gradum.toolwindow.git.analysis.time.minutes.ago", minutes)
    }

    seconds < 86400 -> {
      val hours = (seconds / 3600).toInt()
      message("gradum.toolwindow.git.analysis.time.hours.ago", hours)
    }

    else -> {
      when (val days = (seconds / 86400).toInt()) {
        1 -> message("gradum.toolwindow.git.analysis.time.day.ago")
        else -> message("gradum.toolwindow.git.analysis.time.days.ago", days)
      }
    }
  }
}

/** Serializes a list of [AuditFinding]store into a JSON document. */
internal fun auditFindingsToJson(findings: List<AuditFinding>): String {
  val records = findings.map { finding ->
    buildJsonObject {
      put("code", finding.code)
      put("level", finding.level)
      put("type", finding.type)
      put("hash", finding.hash)
      put("index", finding.index)
      put("date", finding.date)
      put("days", finding.days)
      put("subject", finding.subject)
      put("author", finding.author)
      put("body", finding.body)
      putJsonObject(key = "params") {
        finding.params.forEach { (key, value) ->
          put(key, element = value.toJsonElement())
        }
      }
      put("message", finding.formatMessage())
    }
  }
  return buildJsonObject {
    put("totalCommits", GradumGitAnalysisService.totalCommits)
    putJsonArray(key = "findings") { records.forEach { add(it) } }
  }.toString()
}

/** Converts an [AuditFinding] param value into a JSON element. */
private fun Any?.toJsonElement(): JsonElement =
  when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(value = this)
    is Boolean -> JsonPrimitive(value = this)
    is Int -> JsonPrimitive(value = this)
    is Long -> JsonPrimitive(value = this)
    is Double -> JsonPrimitive(value = this)
    else -> JsonPrimitive(value = toString())
  }
