package gradum.skill

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of in-flight agent→user questions, keyed by `sessionId::requestId`.
 *
 * The skill's [AskScope.askInteraction] registers a [CompletableDeferred]
 * here and blocks on it; the POST /events/respond endpoint resolves the
 * matching deferred when the user answers. A question may be held open
 * **indefinitely** by design: there is deliberately no timeout (matching
 * mainstream agent CLIs): the blocked call stays parked until the user
 * responds or dismisses the card.
 *
 * Thread-safety: intentionally backed by a [ConcurrentHashMap]. The ask
 * runs on the agent's IO thread while the HTTP respond lands on a Ktor
 * worker thread, so registration and resolution race by design.
 *
 * await(sessionId, requestId) registers a fresh pending question and blocks
 * the calling thread until the respond endpoint (or completeCancelled)
 * resolves it: registration happens before blocking so the response endpoint
 * can find the entry as soon as the accompanying `ask_interaction` event
 * lands. completeChoice, completeText, and completeCancelled resolve a
 * pending question with the matching AskResult flavor and return the result,
 * or null when the id is unknown or already resolved. size() is the number
 * of currently pending questions (tests and diagnostics), isPending reports
 * whether a question with this request key is still awaiting an answer, and
 * the private resolve removes and completes the entry, returning the
 * AskResult the blocked await call will observe, or null when no live entry
 * existed.
 */
class PendingQuestions {

  private data class Entry(
    val sessionId: String,
    val requestId: String,
    val deferred: CompletableDeferred<AskResult>
  )

  private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()

  fun await(sessionId: String, requestId: String): AskResult {
    val entry = Entry(
      sessionId = sessionId,
      requestId = requestId,
      deferred = CompletableDeferred(),
    )
    entries[key(sessionId, requestId)] = entry
    return runBlocking { entry.deferred.await() }
  }

  fun completeChoice(sessionId: String, requestId: String, choice: String): AskResult? =
    resolve(sessionId, requestId, AskResult.Case(choice))

  fun completeText(sessionId: String, requestId: String, value: String): AskResult? =
    resolve(sessionId, requestId, AskResult.Text(value))

  fun completeCancelled(sessionId: String, requestId: String): AskResult? =
    resolve(sessionId, requestId, AskResult.Cancelled)

  /**
   * Cancels every pending question belonging to [sessionId], resolving each
   * blocked [await] with [AskResult.Cancelled]. Used by a transport's cancel
   * path: a turn parked on a user question must be woken, otherwise the abort
   * flag alone can never unblock it and the turn hangs until a forced stop.
   * Returns the number of questions that were still pending.
   */
  fun cancelSession(sessionId: String): Int {
    var cancelledCount = 0
    for ((entryKey, entry) in entries) {
      if (entry.sessionId != sessionId) continue
      if (entries.remove(entryKey, entry)) {
        entry.deferred.complete(AskResult.Cancelled)
        cancelledCount++
      }
    }
    return cancelledCount
  }

  fun size(): Int = entries.size

  fun isPending(sessionId: String, requestId: String): Boolean =
    entries.containsKey(key(sessionId, requestId))

  private fun resolve(sessionId: String, requestId: String, result: AskResult): AskResult? {
    val removed: Entry = entries.remove(key(sessionId, requestId)) ?: return null
    removed.deferred.complete(result)
    return result
  }

  private fun key(sessionId: String, requestId: String): String = "$sessionId::$requestId"
}
