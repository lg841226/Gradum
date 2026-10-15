package gradum.idea.chat.history

import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [ChatSessionStore]'s companion helpers. The
 * store's session CRUD now goes through the server REST API (the server
 * owns `.gradum/sessions/`), so the former local-file round-trip tests were
 * retired with that wiring.
 */
class ChatSessionStoreTest {

  @Test
  fun `nextSessionId is unique and lexically sortable`() {
    val ids: Set<String> = (1..100).map { ChatSessionStore.nextSessionId() }.toSet()
    assertEquals(
      100,
      ids.size
    )
    val pattern = Regex(pattern = """^\d{8}-\d{6}-[0-9a-f]{6}$""")
    ids.forEach {
      assertTrue("id $it does not match format", pattern.matches(it))
    }
  }

  @Test
  fun `interleave sorts messages chronologically and keeps ties stable`() {
    val alpha: List<ChatMessage> = listOf(
      ChatMessage(role = "user", timestamp = 100L, content = "alpha q"),
      ChatMessage(role = "assistant", timestamp = 300L)
        .appendEvent(ChatEvent.Response(content = "alpha a1"))
    )
    val beta: List<ChatMessage> = listOf(
      ChatMessage(role = "user", timestamp = 150L, content = "beta q"),
      ChatMessage(role = "assistant", timestamp = 250L)
        .appendEvent(ChatEvent.Response(content = "beta b1"))
    )
    val interleaved: List<ChatMessage> =
      ChatSessionStore.interleaveMessages(conversations = listOf(alpha, beta))
    assertEquals(
      listOf("alpha q", "beta q", "beta b1", "alpha a1"),
      interleaved.map { if (it.isUserMessage) it.content else it.fullContent }
    )
  }
}
