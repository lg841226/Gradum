package gradum.idea.chat.state

import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.history.SessionProjection
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import kotlinx.serialization.json.JsonObject

/**
 * Server-authoritative reconciliation for the chat panel.
 *
 * The client still assembles the live turn from the event stream (cheap, and it
 * carries UI-only decorations the server history does not store), but it no
 * longer *trusts* that assembly once events were lost or a session is reloaded.
 * In those cases it swaps in the server's cold projection instead of keeping a
 * silently diverged copy.
 */
internal suspend fun GradumChatSession.adoptServerProjection(sessionId: String): Boolean {
  val projectRoot: String = project?.basePath ?: return false
  val state: GradumApiClient.ApiSessionState = apiClient.fetchSessionState(projectRoot, sessionId)
    ?: return false
  log.info(
    "Adopted server projection for session $sessionId: ${state.messages.size} message(s), " +
      "${state.pendingAsks.size} pending ask(s)"
  )
  applyServerProjection(state)
  return true
}

/**
 * Replaces the in-memory conversation with [state]'s projection and re-attaches
 * the still-parked ask cards. Shared by session reopen and gap recovery, so the
 * two paths cannot rebuild the view differently.
 */
internal fun GradumChatSession.applyServerProjection(state: GradumApiClient.ApiSessionState) {
  val rebuiltMessages: List<ChatMessage> = SessionProjection.toMessages(
    projectedMessages = state.messages,
    timestamp = state.updatedAt
  )
  messages.clear()
  messages.addAll(rebuiltMessages)
  appendPendingAsks(payloads = state.pendingAsks)
  currentSessionTitle = state.title
  hasSentMessage = messages.isNotEmpty()
}

/** Appends replayed ask cards to the last assistant message, where a live ask would land. */
private fun GradumChatSession.appendPendingAsks(payloads: List<JsonObject>) {
  val assistantIndex: Int = messages.lastIndex
  if (assistantIndex < 0 || messages[assistantIndex].isUserMessage) return

  var assistantMessage: ChatMessage = messages[assistantIndex]
  payloads.forEach { payload: JsonObject ->
    val interaction: ChatEvent.AskInteraction = parseAskInteraction(payload) ?: return@forEach
    assistantMessage = assistantMessage.appendEvent(interaction)
  }
  messages[assistantIndex] = assistantMessage
}
