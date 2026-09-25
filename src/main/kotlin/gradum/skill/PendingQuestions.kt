/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PendingQuestions.kt  2026-09-24 23:20:11 Changed by gwy
 */

package gradum.skill

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of in-flight agent→user questions, keyed by `sessionId::requestId`.
 *
 * The skill's [AskScope.askInteraction] registers a [CompletableDeferred]
 * here and blocks on it; the `POST /events/respond` endpoint resolves the
 * matching deferred when the user answers. A question may be held open
 * **indefinitely** by design — there is deliberately no timeout (matching
 * mainstream agent CLIs): the blocked call stays parked until the user
 * responds or dismisses the card.
 *
 * Thread-safety: intentionally backed by a [ConcurrentHashMap]. The ask
 * runs on the agent's IO thread while the HTTP respond lands on a Ktor
 * worker thread, so registration and resolution race by design.
 */
class PendingQuestions {

  private data class Entry(
    val sessionId: String,
    val requestId: String,
    val deferred: CompletableDeferred<AskResult>
  )

  private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()

  /**
   * Registers a fresh pending question and blocks the calling thread until
   * [io.ktor.server.response.respond] (or [completeCancelled]) resolves it. Returns the answer.
   *
   * Registration happens before blocking so the response endpoint can find
   * the entry as soon as the accompanying `ask_interaction` event lands.
   */
  fun await(sessionId: String, requestId: String): AskResult {
    val entry = Entry(
      sessionId = sessionId,
      requestId = requestId,
      deferred = CompletableDeferred(),
    )
    entries[key(sessionId, requestId)] = entry
    return runBlocking { entry.deferred.await() }
  }

  /** Resolves a pending question with [AskResult.Case]. Returns the result or null when unknown/duplicate. */
  fun completeChoice(sessionId: String, requestId: String, choiceId: String): AskResult? =
    resolve(sessionId, requestId, AskResult.Case(choiceId))

  /** Resolves a pending question with [AskResult.Text]. Returns the result or null when unknown/duplicate. */
  fun completeText(sessionId: String, requestId: String, value: String): AskResult? =
    resolve(sessionId, requestId, AskResult.Text(value))

  /** Resolves a pending question as dismissed by the user. Returns the result or null when unknown/duplicate. */
  fun completeCancelled(sessionId: String, requestId: String): AskResult? =
    resolve(sessionId, requestId, AskResult.Cancelled)

  /** Number of currently pending questions (tests / diagnostics). */
  fun size(): Int = entries.size

  /** True when a question with this request key is still awaiting an answer. */
  fun isPending(sessionId: String, requestId: String): Boolean =
    entries.containsKey(key(sessionId, requestId))

  /**
   * Removes and completes the entry for `sessionId::requestId`. Returns the
   * [AskResult] the blocked [await] call will observe, or null when no live
   * entry existed (unknown id or already resolved duplicate).
   */
  private fun resolve(sessionId: String, requestId: String, result: AskResult): AskResult? {
    val removed: Entry = entries.remove(key(sessionId, requestId)) ?: return null
    removed.deferred.complete(result)
    return result
  }

  private fun key(sessionId: String, requestId: String): String = "$sessionId::$requestId"
}
