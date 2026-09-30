package gradum.skill.external

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the external skills directory for changes and hot-reloads skills
 * without restarting the server.
 *
 * Reloads are driven two ways, both funneling into the scanner's
 * skillScanner.reconcile(), which is state-based, so a reload always reflects
 * the full current set of .kt sources:
 *
 * - **Event-driven**: a [WatchService] on the directory fires on create or
 *   modify or delete of a top-level .kt file, debounced to coalesce bursts.
 * - **Periodic fallback**: a daemon scheduler rechecks the currentFingerprint()
 *   fingerprint every fallbackIntervalMillis to catch events the platform's
 *   watch service may have missed (a known macOS quirk).
 *
 * Only top-level .kt files are relevant; the compiler's own .build output is
 * deliberately ignored so a reload can never trigger itself.
 *
 * start() begins event listening and the periodic fallback task; close() stops
 * listening and the periodic task. Both are idempotent: a second call is a
 * no-op, and closing an already closed or non-closeable watch service is
 * ignored. Neither call disturbs skills that are already registered.
 *
 * reconcileNow() forces a reload and treats the current state as
 * already-handled, so tests can trigger a reload without depending on real
 * event timing. onPeriodicTick() is where the periodic fallback lands: it
 * reloads only when the source fingerprint changed since the last reload.
 */
class ExternalSkillDirectoryWatcher(
  private val debounceMillis: Long = 300,
  private val fallbackIntervalMillis: Long = 60_000,
  private val skillScanner: ExternalSkillDirectoryScanner,
  private val watchService: WatchService = FileSystems.getDefault().newWatchService()
) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillDirectoryWatcher")
  private val isClosed: AtomicBoolean = AtomicBoolean(false)

  @Volatile
  private var lastFingerprint: String? = null
  private var listenerThread: Thread? = null

  private val fallbackScheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { threadRunnable ->
      Thread(threadRunnable, "external-skill-watcher-fallback").apply { isDaemon = true }
    }

  fun start(): ExternalSkillDirectoryWatcher {
    if (isClosed.get()) return this
    lastFingerprint = skillScanner.currentFingerprint()
    registerWatch()
    fallbackScheduler.scheduleWithFixedDelay(
      { onPeriodicTick() }, fallbackIntervalMillis, fallbackIntervalMillis, TimeUnit.MILLISECONDS
    )
    val watchThread = Thread({ listen() }, "external-skill-watcher").apply {
      isDaemon = true
    }
    watchThread.start()
    listenerThread = watchThread
    logger.info(
      "Watching external skills directory {} for hot reload",
      skillScanner.skillsDirectory.absolutePath
    )
    return this
  }

  fun close() {
    if (!isClosed.compareAndSet(false, true)) return
    try {
      watchService.close()
    } catch (_: Exception) {
    }
    fallbackScheduler.shutdownNow()
    val activeThread = listenerThread
    if (activeThread != null && activeThread !== Thread.currentThread()) {
      try {
        activeThread.join(1_000)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  internal fun reconcileNow() {
    lastFingerprint = skillScanner.currentFingerprint()
    skillScanner.reconcile()
  }

  internal fun onPeriodicTick() {
    if (isClosed.get()) return
    val currentFingerprint = skillScanner.currentFingerprint()
    if (currentFingerprint != lastFingerprint) {
      lastFingerprint = currentFingerprint
      skillScanner.reconcile()
    }
  }

  private fun registerWatch() {
    try {
      skillScanner.skillsDirectory.toPath().register(
        watchService,
        ENTRY_CREATE,
        ENTRY_MODIFY,
        ENTRY_DELETE
      )
    } catch (notFound: NoSuchFileException) {
      logger.warn("External skills directory missing; periodic fallback will handle it", notFound)
    }
  }

  private fun listen() {
    try {
      while (!isClosed.get()) {
        val watchKey: WatchKey =
          try {
            watchService.take()
          } catch (_: ClosedWatchServiceException) {
            break
          } catch (_: InterruptedException) {
            if (isClosed.get()) break else continue
          }
        if (isClosed.get()) break
        drainAndReload(watchKey)
      }
    } catch (listenerError: Exception) {
      if (!isClosed.get()) {
        logger.error("External skills watcher loop failed", listenerError)
      }
    }
  }

  private fun drainAndReload(watchKey: WatchKey) {
    val hasRelevantChange =
      watchKey.pollEvents().any { watchEvent ->
        val relativeName = (watchEvent.context() as? Path)?.fileName?.toString()
        relativeName != null && isRelevantSource(relativeName)
      }

    if (!hasRelevantChange || !watchKey.reset()) return

    if (debounceMillis > 0) {
      try {
        Thread.sleep(debounceMillis)
      } catch (_: InterruptedException) {
        if (isClosed.get()) return
        Thread.currentThread().interrupt()
      }
    }
    skillScanner.reconcile()
  }

  private fun isRelevantSource(fileName: String): Boolean = fileName.endsWith(".kt")
}
