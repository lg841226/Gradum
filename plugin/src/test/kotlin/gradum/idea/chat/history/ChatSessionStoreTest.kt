package gradum.idea.chat.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [ChatSessionStore]'s companion helpers. The
 * store's session CRUD and merge now go through the server REST API (the
 * server owns `.gradum/sessions/`), so the former local-file round-trip tests
 * were retired with that wiring.
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
}
