package gradum.task

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Unit tests for [BackgroundTaskManager].
 *
 * Verifies normal completion, timeout with process-tree kill, session
 * cancellation, output truncation, and terminal-state idempotency.
 */
class BackgroundTaskManagerTest {

  private lateinit var projectRoot: File
  private lateinit var workers: ExecutorService
  private lateinit var outcomes: Channel<JobOutcome>
  private lateinit var manager: BackgroundTaskManager

  @BeforeEach
  fun setUp() {
    projectRoot = File("/tmp", "gradum-bg-task-test-${System.nanoTime()}")
    projectRoot.mkdirs()
    workers = Executors.newFixedThreadPool(2) { runnable ->
      Thread(runnable, "test-bg-worker").apply { isDaemon = true }
    }
    outcomes = Channel(capacity = 64)
    manager = BackgroundTaskManager.initialize(workers, outcomes)
  }

  @AfterEach
  fun tearDown() {
    workers.shutdownNow()
    projectRoot.deleteRecursively()
  }

  @Test
  fun `submit runs command and delivers outcome on completion`() {
    val job = manager.submit(
      sessionId = "s1",
      command = "echo hello",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )

    assertEquals(JobStatus.RUNNING, job.status)
    assertEquals("s1", job.sessionId)
    assertEquals("echo hello", job.command)

    val outcome = runBlocking { outcomes.receive() }
    assertEquals(JobStatus.COMPLETED, outcome.job.status)
    assertEquals(0, outcome.exitCode)
    assertTrue("hello" in outcome.output, "output should contain 'hello': ${outcome.output}")
  }

  @Test
  fun `timeout kills process tree and marks TIMEOUT`() {
    manager.submit(
      sessionId = "s1",
      command = "sleep 100",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 1,
    )

    val outcome = runBlocking { outcomes.receive() }
    assertEquals(JobStatus.TIMEOUT, outcome.job.status)
    assertEquals(-1, outcome.exitCode)
  }

  @Test
  fun `cancel terminates running task`() {
    val job = manager.submit(
      sessionId = "s1",
      command = "sleep 100",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 30,
    )

    val cancelled = manager.cancel(job.taskId)
    assertTrue(cancelled, "cancel should succeed on running task")

    val outcome = runBlocking { outcomes.receive() }
    assertEquals(JobStatus.CANCELLED, outcome.job.status)
  }

  @Test
  fun `cancel is idempotent on terminal task`() {
    val job = manager.submit(
      sessionId = "s1",
      command = "echo done",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )

    runBlocking { outcomes.receive() }

    val secondCancel = manager.cancel(job.taskId)
    assertFalse(secondCancel, "cancel on terminal task should return false")
  }

  @Test
  fun `cancelSession terminates all running tasks for session`() {
    manager.submit(
      sessionId = "s1",
      command = "sleep 100",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 30,
    )
    manager.submit(
      sessionId = "s1",
      command = "sleep 100",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 30,
    )
    val otherJob = manager.submit(
      sessionId = "s2",
      command = "sleep 100",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 30,
    )

    val cancelledCount = manager.cancelSession("s1")
    assertEquals(2, cancelledCount, "both s1 tasks should be cancelled")

    val outcome1 = runBlocking { outcomes.receive() }
    val outcome2 = runBlocking { outcomes.receive() }
    assertEquals(JobStatus.CANCELLED, outcome1.job.status)
    assertEquals(JobStatus.CANCELLED, outcome2.job.status)

    assertFalse(manager.cancel(otherJob.taskId).not(), "other session task should still be running")
  }

  @Test
  fun `output is truncated to MAX_OUTPUT_CHARS`() {
    // Generates the payload inside the shell: a 1 MB echo argument would
    // exceed Linux's 128 KiB per-argument exec limit (E2BIG) and never spawn.
    manager.submit(
      sessionId = "s1",
      command = "yes x | head -c 1000000",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )

    val outcome = runBlocking { outcomes.receive() }
    assertTrue(
      outcome.output.length < 1_000_000,
      "output should be truncated: ${outcome.output.length} chars"
    )
    assertTrue(
      "output truncated at" in outcome.output,
      "output should contain truncation marker"
    )
  }

  @Test
  fun `get returns job snapshot and getOutcome returns terminal outcome`() {
    val job = manager.submit(
      sessionId = "s1",
      command = "echo test",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )

    runBlocking { outcomes.receive() }

    val snapshot = manager.get(job.taskId)
    assertNotNull(snapshot, "get should return snapshot after completion")
    assertEquals(JobStatus.COMPLETED, snapshot?.status)

    val outcome = manager.getOutcome(job.taskId)
    assertNotNull(outcome, "getOutcome should return outcome for terminal task")
    assertEquals(0, outcome?.exitCode)
  }

  @Test
  fun `listForSession returns jobs sorted by start time`() {
    manager.submit(
      sessionId = "s1",
      command = "echo first",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )
    manager.submit(
      sessionId = "s1",
      command = "echo second",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )

    runBlocking { outcomes.receive() }
    runBlocking { outcomes.receive() }

    val jobs = manager.listForSession("s1")
    assertEquals(2, jobs.size, "should return both jobs")
    assertTrue(jobs[0].startedAt >= jobs[1].startedAt, "jobs should be sorted by start time descending")
  }

  @Test
  fun `pendingForSession returns only running tasks`() {
    val runningJob = manager.submit(
      sessionId = "s1",
      command = "sleep 100",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 30,
    )
    manager.submit(
      sessionId = "s1",
      command = "echo done",
      projectRoot = projectRoot.absolutePath,
      timeoutSeconds = 10,
    )

    runBlocking { outcomes.receive() }

    val pending = manager.pendingForSession("s1")
    assertEquals(1, pending.size, "only running task should be pending")
    assertEquals(runningJob.taskId, pending[0]["taskId"], "running task should be in pending list")

    manager.cancel(runningJob.taskId)
    runBlocking { outcomes.receive() }

    val emptyPending = manager.pendingForSession("s1")
    assertTrue(emptyPending.isEmpty(), "no tasks should be pending after cancel")
  }
}
