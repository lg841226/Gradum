package gradum.mode

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Polls the user modes directory (see [ModeDirectoryScanner.currentFingerprint])
 * and reloads [ModeRegistry] when it changes, so a saved mode file takes
 * effect without restarting the service.
 *
 * Mirrors [gradum.skill.external.ExternalSkillDirectoryWatcher]: the poll
 * interval doubles as to debounce, and the initial fingerprint is captured
 * before the thread starts so a burst of writes counts as one reload.
 *
 * [onChanged] is the hook the ACP server wires to push a
 * `config_option_update` to live sessions after each reload; the HTTP server
 * leaves it unset because its clients fetch `GET /modes` on load.
 */
class ModeDirectoryWatcher(
  private val modeScanner: ModeDirectoryScanner,
  private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) : AutoCloseable {

  private companion object {
    private val logger: Logger = LoggerFactory.getLogger("ModeDirectoryWatcher")
    private const val DEFAULT_POLL_INTERVAL_MS = 1_000L
  }

  @Volatile
  var onChanged: (() -> Unit)? = null

  private val running = AtomicBoolean(false)
  private var worker: Thread? = null
  private var lastFingerprint: String = ""

  fun start(): ModeDirectoryWatcher {
    if (!running.compareAndSet(false, true)) return this
    lastFingerprint = modeScanner.currentFingerprint()
    worker = thread(isDaemon = true, name = "gradum-mode-watcher") {
      while (running.get()) {
        try {
          Thread.sleep(pollIntervalMs)
          if (!running.get()) break
          if (modeScanner.currentFingerprint() != lastFingerprint) {
            logger.info("Modes directory changed; reloading mode registry")
            reconcileNow()
          }
        } catch (_: InterruptedException) {
          break
        } catch (pollException: Exception) {
          logger.warn("Mode directory poll failed; keeping the current registry", pollException)
        }
      }
    }
    return this
  }

  fun reconcileNow() {
    lastFingerprint = modeScanner.currentFingerprint()
    modeScanner.reconcile()
    onChanged?.invoke()
  }

  override fun close() {
    running.set(false)
    worker?.interrupt()
    worker = null
    onChanged = null
  }
}
