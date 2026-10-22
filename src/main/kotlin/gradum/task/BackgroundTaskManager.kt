package gradum.task

import gradum.GradumConfig
import kotlinx.coroutines.channels.Channel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger("BackgroundTaskManager")

// Default timeout for background tasks (independent of COMMAND_DEFAULT_TIMEOUT_SECONDS)
private const val BACKGROUND_TASK_DEFAULT_TIMEOUT_SECONDS: Long = 600L

// Cap on combined stdout+stderr kept in truncated output field
private const val MAX_OUTPUT_CHARS: Int = GradumConfig.COMMAND_MAX_OUTPUT_CHARS

// Grace period after SIGTERM before SIGKILL (shared with RunCommandSkill)
private const val FORCE_KILL_DELAY_MS: Long = GradumConfig.COMMAND_FORCE_KILL_DELAY_MS

// Retention window for completed jobs per session before pruning
private const val COMPLETED_JOB_RETENTION_COUNT: Int = 20

/** Status of a [BackgroundJob]. State machine is one-way: RUNNING -> terminal. */
enum class JobStatus {
  RUNNING,
  COMPLETED,
  FAILED,
  TIMEOUT,
  CANCELLED,
}

/** Immutable snapshot of a background job's metadata. */
data class BackgroundJob(
  val taskId: String,
  val sessionId: String,
  val command: String,
  val startedAt: Long,
  val status: JobStatus,
)

/** Final outcome of a [BackgroundJob], delivered once when it reaches a terminal state. */
data class JobOutcome(
  val job: BackgroundJob,
  val exitCode: Int,
  val output: String,
  val durationMs: Long,
)

/**
 * Manages background command execution for all sessions.
 *
 * Each submitted job runs as a real child process with stdout/stderr drained
 * on dedicated threads before waitFor (to avoid pipe-buffer deadlock). On
 * timeout, the entire process tree is killed (SIGTERM -> grace -> SIGKILL).
 *
 * Terminal outcomes are delivered exactly once via the [outcomes] channel;
 * a consumer coroutine drains the channel and injects results into the owning
 * session's conversation history.
 *
 * Thread-safety: all public methods are safe to call from any thread. The
 * job table is a [ConcurrentHashMap] keyed by taskId, with per-session index
 * maintained in a [CopyOnWriteArrayList].
 */
class BackgroundTaskManager(
  private val workers: ExecutorService,
  private val outcomes: Channel<JobOutcome>,
) {

  companion object {
    @Volatile
    private var instance: BackgroundTaskManager? = null

    // Returns the global singleton instance, or null if not initialized
    fun getInstance(): BackgroundTaskManager? = instance

    // Initializes the global singleton. Called once during server startup.
    fun initialize(workers: ExecutorService, outcomes: Channel<JobOutcome>): BackgroundTaskManager {
      val manager = BackgroundTaskManager(workers, outcomes)
      instance = manager
      return manager
    }
  }

  // Live handle to a running process, used for cancellation
  private class JobHandle(
    val process: Process,
    val descendants: List<ProcessHandle>,
  )

  // Mutable job state guarded by the entry's lock
  private class JobEntry(
    val job: BackgroundJob,
    val handle: JobHandle?,
    val logFile: File?,
  ) {
    @Volatile
    var status: JobStatus = job.status

    @Volatile
    var exitCode: Int = -1

    @Volatile
    var output: String = ""

    @Volatile
    var durationMs: Long = 0L

    @Volatile
    var outcomeDelivered: Boolean = false

    fun toSnapshot(): BackgroundJob = job.copy(status = status)
  }

  private val jobsByTaskId: ConcurrentHashMap<String, JobEntry> = ConcurrentHashMap()
  private val taskIdsBySession: ConcurrentHashMap<String, CopyOnWriteArrayList<String>> = ConcurrentHashMap()

  /**
   * Submits a background command for [sessionId]. Returns immediately with
   * a [BackgroundJob] whose taskId is valid for cancellation and polling.
   *
   * The command runs via `sh -c` with working directory [projectRoot]. Full
   * output is written to `.gradum/run_cmd/{taskId}.log` under [projectRoot];
   * the in-memory [JobOutcome.output] is truncated to [MAX_OUTPUT_CHARS].
   */
  fun submit(
    sessionId: String,
    command: String,
    projectRoot: String,
    timeoutSeconds: Long = BACKGROUND_TASK_DEFAULT_TIMEOUT_SECONDS,
  ): BackgroundJob {
    val taskId: String = UUID.randomUUID().toString()
    val startedAt: Long = System.currentTimeMillis()
    val initialJob = BackgroundJob(
      taskId = taskId,
      sessionId = sessionId,
      command = command,
      startedAt = startedAt,
      status = JobStatus.RUNNING,
    )

    val logDirectory: Path = Path.of(projectRoot, ".gradum", "run_cmd")
    logDirectory.toFile().mkdirs()
    val logFile = File(logDirectory.toFile(), "$taskId.log")

    val entry = JobEntry(job = initialJob, handle = null, logFile = logFile)
    jobsByTaskId[taskId] = entry
    taskIdsBySession.computeIfAbsent(sessionId) { CopyOnWriteArrayList() }.add(taskId)

    workers.submit {
      executeJob(entry, projectRoot, timeoutSeconds)
    }

    logger.info("Submitted background task {} for session {}: {}", taskId, sessionId, command)
    return initialJob
  }

  // Executes the job in a worker thread: drains streams, waits, handles timeout/kill
  private fun executeJob(entry: JobEntry, projectRoot: String, timeoutSeconds: Long) {
    val taskId: String = entry.job.taskId
    val commandText: String = entry.job.command

    try {
      val processBuilder = ProcessBuilder("sh", "-c", commandText)
      processBuilder.redirectErrorStream(false)

      if (projectRoot.isNotBlank()) {
        val workingDirectory = File(projectRoot)
        if (workingDirectory.isDirectory) processBuilder.directory(workingDirectory)
      }

      val logFileWriter = entry.logFile?.bufferedWriter(Charsets.UTF_8)
      val process: Process = processBuilder.start()

      val stdoutFuture = startStreamReader(process.inputStream, "stdout", MAX_OUTPUT_CHARS)
      val stderrFuture = startStreamReader(process.errorStream, "stderr", MAX_OUTPUT_CHARS)

      val processFinished: Boolean = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

      if (!processFinished) {
        val descendants = captureDescendants(process)
        process.destroy()
        process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)
        killProcessTree(process, descendants)

        val stdoutText: String = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        val stderrText: String = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        writeLogFile(logFileWriter, stdoutText, stderrText)

        completeJob(entry, JobStatus.TIMEOUT, exitCode = -1, stdoutText, stderrText)
        return
      }

      val exitCode: Int = process.exitValue()
      val stdoutText: String = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
      val stderrText: String = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")

      writeLogFile(logFileWriter, stdoutText, stderrText)

      val mergedOutput: String = mergeOutput(exitCode, stdoutText, stderrText)
      val status = if (exitCode == 0) JobStatus.COMPLETED else JobStatus.FAILED
      entry.output = mergedOutput
      completeJob(entry, status, exitCode, stdoutText, stderrText)
    } catch (executionException: Exception) {
      logger.error("Background task {} failed to execute", taskId, executionException)
      completeJob(entry, JobStatus.FAILED, exitCode = -1, stdoutText = "", stderrText = executionException.message ?: "execution failed")
    }
  }

  private fun writeLogFile(writer: java.io.BufferedWriter?, stdoutText: String, stderrText: String) {
    try {
      writer?.use {
        it.write(stdoutText)
        if (stderrText.isNotBlank()) {
          it.write("\n(stderr)\n")
          it.write(stderrText)
        }
      }
    } catch (ioException: Exception) {
      logger.warn("Failed to write log file: {}", ioException.message)
    }
  }

  private fun mergeOutput(exitCode: Int, stdoutText: String, stderrText: String): String {
    return buildString {
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
  }

  private fun completeJob(
    entry: JobEntry,
    status: JobStatus,
    exitCode: Int,
    stdoutText: String,
    stderrText: String,
  ) {
    val taskId: String = entry.job.taskId

    synchronized(entry) {
      if (entry.status != JobStatus.RUNNING) {
        logger.debug("Task {} already terminal ({}), skipping completion", taskId, entry.status)
        return
      }

      entry.status = status
      entry.exitCode = exitCode
      if (entry.output.isEmpty()) {
        entry.output = mergeOutput(exitCode, stdoutText, stderrText)
      }
      entry.durationMs = System.currentTimeMillis() - entry.job.startedAt

      if (!entry.outcomeDelivered) {
        entry.outcomeDelivered = true
        val outcome = JobOutcome(
          job = entry.toSnapshot(),
          exitCode = entry.exitCode,
          output = entry.output,
          durationMs = entry.durationMs,
        )
        outcomes.trySend(outcome)
      }
    }

    logger.info(
      "Background task {} completed with status {} (exit {}, {}ms)",
      taskId, status, exitCode, entry.durationMs,
    )
  }

  /** Cancels a running job by [taskId]. Returns false if already terminal or unknown. */
  fun cancel(taskId: String): Boolean {
    val entry: JobEntry = jobsByTaskId[taskId] ?: return false

    synchronized(entry) {
      if (entry.status != JobStatus.RUNNING) return false

      val handle: JobHandle? = entry.handle
      if (handle != null) {
        val liveDescendants = captureDescendants(handle.process)
        handle.process.destroy()
        handle.process.waitFor(FORCE_KILL_DELAY_MS, TimeUnit.MILLISECONDS)
        killProcessTree(handle.process, liveDescendants)
      }

      entry.status = JobStatus.CANCELLED
      entry.exitCode = -1
      entry.durationMs = System.currentTimeMillis() - entry.job.startedAt

      if (!entry.outcomeDelivered) {
        entry.outcomeDelivered = true
        val outcome = JobOutcome(
          job = entry.toSnapshot(),
          exitCode = -1,
          output = "(cancelled)",
          durationMs = entry.durationMs,
        )
        outcomes.trySend(outcome)
      }
    }

    logger.info("Cancelled background task {}", taskId)
    return true
  }

  /** Cancels all running jobs belonging to [sessionId]. Returns the number cancelled. */
  fun cancelSession(sessionId: String): Int {
    val taskIds: List<String> = taskIdsBySession[sessionId]?.toList() ?: return 0
    var cancelledCount = 0
    for (taskId in taskIds) {
      if (cancel(taskId)) cancelledCount++
    }
    return cancelledCount
  }

  /** Returns a snapshot of the job with [taskId], or null if unknown. */
  fun get(taskId: String): BackgroundJob? =
    jobsByTaskId[taskId]?.toSnapshot()

  /** Returns the full outcome (with output) for a terminal task, or null if unknown or still running. */
  fun getOutcome(taskId: String): JobOutcome? {
    val entry: JobEntry = jobsByTaskId[taskId] ?: return null
    if (entry.status == JobStatus.RUNNING) return null
    return JobOutcome(
      job = entry.toSnapshot(),
      exitCode = entry.exitCode,
      output = entry.output,
      durationMs = entry.durationMs,
    )
  }

  // Returns all non-cancelled jobs for [sessionId]: running + recent terminal
  fun listForSession(sessionId: String): List<BackgroundJob> {
    val taskIds: List<String> = taskIdsBySession[sessionId]?.toList() ?: return emptyList()
    return taskIds.mapNotNull { jobsByTaskId[it]?.toSnapshot() }
      .filter { it.status != JobStatus.CANCELLED }
      .sortedByDescending { it.startedAt }
  }

  // Returns lightweight summaries of running jobs for [sessionId]: used by GET /session/state
  fun pendingForSession(sessionId: String): List<Map<String, Any>> {
    val taskIds: List<String> = taskIdsBySession[sessionId]?.toList() ?: return emptyList()
    return taskIds.mapNotNull { jobsByTaskId[it] }
      .filter { it.status == JobStatus.RUNNING }
      .map { entry ->
        mapOf(
          "taskId" to entry.job.taskId,
          "command" to entry.job.command,
          "startedAt" to entry.job.startedAt,
        )
      }
  }

  /**
   * Drains and returns all completed outcomes that have not been delivered yet.
   * The Agent calls this before each model turn to inject background results
   * into the conversation history.
   */
  fun drainCompletedOutcomes(): List<JobOutcome> {
    val drainedOutcomes = mutableListOf<JobOutcome>()
    while (true) {
      val outcome = outcomes.tryReceive().getOrNull() ?: break
      drainedOutcomes.add(outcome)
    }
    return drainedOutcomes
  }

  // Removes terminal jobs beyond COMPLETED_JOB_RETENTION_COUNT for [sessionId]
  fun pruneSession(sessionId: String) {
    val taskIds: MutableList<String> = taskIdsBySession[sessionId] ?: return
    val terminalTaskIds: List<String> = taskIds.filter { taskId ->
      jobsByTaskId[taskId]?.let { it.status != JobStatus.RUNNING } == true
    }
    if (terminalTaskIds.size <= COMPLETED_JOB_RETENTION_COUNT) return

    val toRemove: List<String> = terminalTaskIds
      .sortedBy { jobsByTaskId[it]?.job?.startedAt ?: 0L }
      .take(terminalTaskIds.size - COMPLETED_JOB_RETENTION_COUNT)

    for (taskId in toRemove) {
      jobsByTaskId.remove(taskId)
      taskIds.remove(taskId)
    }
  }
}