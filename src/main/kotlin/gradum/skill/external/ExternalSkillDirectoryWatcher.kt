/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExternalSkillDirectoryWatcher.kt  2026-09-27 13:27:35 Changed by gwy
 */

package gradum.skill.external

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.ClosedWatchServiceException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the external skills directory for changes and hot-reloads skills
 * without restarting the server.
 *
 * Reloads are driven two ways, both funneling into [scanner.reconcile] (which
 * is state-based, so a reload always reflects the full current `.kt` set):
 *
 * - **Event-driven**: a [WatchService] on the directory fires on create /
 *   modify / delete of a top-level `.kt` file, debounced to coalesce bursts.
 * - **Periodic fallback**: a daemon scheduler re-checks [currentFingerprint]
 *   every [fallbackIntervalMillis] to catch events the platform's watch
 *   service may have missed (a known macOS quirk).
 *
 * Only top-level `.kt` files are relevant; the compiler's own `.build` output
 * is deliberately ignored so a reload can never trigger itself.
 */
class ExternalSkillDirectoryWatcher(
  private val scanner: ExternalSkillDirectoryScanner,
  private val debounceMillis: Long = 300,
  private val fallbackIntervalMillis: Long = 3000,
  private val watchService: WatchService = FileSystems.getDefault().newWatchService(),
) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillDirectoryWatcher")
  private val closed: AtomicBoolean = AtomicBoolean(false)

  @Volatile
  private var lastFingerprint: String? = null
  private var listenerThread: Thread? = null

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "external-skill-watcher-fallback").apply { isDaemon = true }
    }

  /** Starts event listening and the periodic fallback task. Idempotent. */
  fun start(): ExternalSkillDirectoryWatcher {
    if (closed.get()) return this
    lastFingerprint = scanner.currentFingerprint()
    registerWatch()
    scheduler.scheduleWithFixedDelay(
      { onPeriodicTick() },
      fallbackIntervalMillis,
      fallbackIntervalMillis,
      TimeUnit.MILLISECONDS
    )
    val thread = Thread({ listen() }, "external-skill-watcher").apply { isDaemon = true }
    thread.start()
    listenerThread = thread
    logger.info("Watching external skills directory {} for hot reload", scanner.skillsDirectory.absolutePath)
    return this
  }

  /** Stops listening and the periodic task. Idempotent. */
  fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      watchService.close()
    } catch (ignored: Exception) {
      // already closed or not closeable; nothing more to do
    }
    scheduler.shutdownNow()
    val thread = listenerThread
    if (thread != null && thread !== Thread.currentThread()) {
      try {
        thread.join(1_000)
      } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  /**
   * Forces a reload now and treats the current state as already-handled.
   * Exposed so tests can trigger a reload without depending on real event
   * timing.
   */
  internal fun reconcileNow() {
    lastFingerprint = scanner.currentFingerprint()
    scanner.reconcile()
  }

  /** Periodic fallback: reload only when the source fingerprint changed. */
  internal fun onPeriodicTick() {
    if (closed.get()) return
    val current = scanner.currentFingerprint()
    if (current != lastFingerprint) {
      lastFingerprint = current
      scanner.reconcile()
    }
  }

  private fun registerWatch() {
    try {
      scanner.skillsDirectory.toPath().register(
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
      while (!closed.get()) {
        val key: WatchKey =
          try {
            watchService.take()
          } catch (ignored: ClosedWatchServiceException) {
            break
          } catch (ignored: InterruptedException) {
            if (closed.get()) break else continue
          }
        if (closed.get()) break
        drainAndReload(key)
      }
    } catch (listenerError: Exception) {
      if (!closed.get()) {
        logger.error("External skills watcher loop failed", listenerError)
      }
    }
  }

  private fun drainAndReload(key: WatchKey) {
    val hasRelevantChange =
      key.pollEvents().any { event ->
        val relativeName = (event.context() as? Path)?.fileName?.toString()
        relativeName != null && isRelevantSource(relativeName)
      }
    // reset() returns false once the key is no longer valid (e.g. the watched
    // directory was deleted); the periodic fallback then owns recovery.
    if (!hasRelevantChange || !key.reset()) return

    // Coalesce a burst of rapid writes before reloading.
    if (debounceMillis > 0) {
      try {
        Thread.sleep(debounceMillis)
      } catch (ignored: InterruptedException) {
        if (closed.get()) return
        Thread.currentThread().interrupt()
      }
    }
    scanner.reconcile()
  }

  private fun isRelevantSource(fileName: String): Boolean = fileName.endsWith(".kt")
}
