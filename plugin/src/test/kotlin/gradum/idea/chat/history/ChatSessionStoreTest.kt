/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatSessionStoreTest.kt  2026-08-12 15:57:39 Changed by gwy
 */

package gradum.idea.chat.history

import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Store tests: save → list (most recent first) → load → delete.
 * Runs against a temp directory so no IDE project is required.
 */
class ChatSessionStoreTest {

  private lateinit var root: Path
  private lateinit var store: ChatSessionStore

  @Before
  fun setUp() {
    root = Files.createTempDirectory("gradum-store-test")
    store = ChatSessionStore(root)
  }

  @After
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  private fun messages(text: String): List<ChatMessage> {
    val user = ChatMessage(role = "user", content = text, timestamp = 100L)
    val assistant = ChatMessage(role = "assistant", content = "", timestamp = 200L)
      .appendEvent(ChatEvent.Response("I fixed it."))
    return listOf(user, assistant)
  }

  @Test
  fun `save writes transcript and hasSession reflects it`() {
    store.saveSession(
      SessionMeta("a1", "Hello world", 100L, 200L, "qwen2.5:7b"),
      messages("Hello world")
    )
    assertTrue(store.hasSession("a1"))
    assertTrue(Files.isRegularFile(root.resolve(".gradum/sessions/a1/conversation.md")))
  }

  @Test
  fun `list orders sessions most recent first`() {
    store.saveSession(
      SessionMeta("old", "Old session", 100L, 100L, "model-a"),
      messages("Old session")
    )
    store.saveSession(
      SessionMeta("new", "New session", 200L, 500L, "model-b"),
      messages("New session")
    )
    val listed: List<SessionMeta> = store.listSessions()
    assertEquals(listOf("new", "old"), listed.map { it.sessionId })
    assertEquals("New session", listed.first().title)
  }

  @Test
  fun `load restores messages`() {
    store.saveSession(
      SessionMeta("a1", "Hello world", 100L, 200L, "qwen2.5:7b"),
      messages("Hello world")
    )
    val loaded: ChatTranscript.ParsedTranscript? = store.loadSession("a1")
    assertTrue("expected session to load", loaded != null)
    assertEquals(2, loaded!!.messages.size)
    assertEquals("Hello world", loaded.messages[0].content)
    assertEquals("I fixed it.", loaded.messages[1].fullContent)
  }

  @Test
  fun `load returns null for unknown session`() {
    assertEquals(null, store.loadSession("nope"))
  }

  @Test
  fun `delete removes the entire session directory`() {
    store.saveSession(
      SessionMeta("a1", "Hello world", 100L, 200L, "qwen2.5:7b"),
      messages("Hello world")
    )
    assertTrue(store.deleteSession("a1"))
    assertFalse(Files.exists(root.resolve(".gradum/sessions/a1")))
    assertFalse(store.hasSession("a1"))
  }

  @Test
  fun `delete returns false for unknown session`() {
    assertFalse(store.deleteSession("nope"))
  }

  @Test
  fun `delete cannot escape the sessions root`() {
    val sibling: Path = root.resolve("sneaky.txt")
    Files.writeString(sibling, "keep me")
    assertFalse(store.deleteSession("../sneaky.txt"))
    assertTrue(Files.exists(sibling))
  }

  @Test
  fun `nextSessionId is unique and lexically sortable`() {
    val ids: Set<String> = (1..100).map { ChatSessionStore.nextSessionId() }.toSet()
    assertEquals(100, ids.size)
    val pattern = Regex("""^\d{8}-\d{6}-[0-9a-f]{6}$""")
    ids.forEach { assertTrue("id $it does not match format", pattern.matches(it)) }
  }

  private fun chatRecord(marker: String, userTs: Long, assistantTs: Long, reply: String): List<ChatMessage> =
    listOf(
      ChatMessage(role = "user", content = "$marker q", timestamp = userTs),
      ChatMessage(role = "assistant", content = "", timestamp = assistantTs)
        .appendEvent(ChatEvent.Response("$marker $reply"))
    )

  @Test
  fun `merge interleaves two sessions chronologically and keeps originals`() {
    store.saveSession(
      SessionMeta("a1", "Alpha", 100L, 100L, "model-a"),
      chatRecord("alpha", 100L, 200L, "a1")
    )
    store.saveSession(
      SessionMeta("b1", "Beta", 300L, 300L, "model-b"),
      chatRecord("beta", 150L, 250L, "b1")
    )

    val mergedId: String? = store.mergeSessions("a1", "b1", "Merged conversation")
    assertTrue("expected merge to succeed", mergedId != null)

    // Non-destructive: both sources still exist.
    assertTrue(store.hasSession("a1"))
    assertTrue(store.hasSession("b1"))

    val merged: ChatTranscript.ParsedTranscript? = store.loadSession(mergedId!!)
    assertTrue("merged session should load", merged != null)
    val rendered: List<Pair<String, String>> = merged!!.messages.map {
      (if (it.isUserMessage) "user" else "assistant") to (if (it.isUserMessage) it.content else it.fullContent)
    }
    assertEquals(
      listOf(
        "user" to "alpha q",
        "user" to "beta q",
        "assistant" to "alpha a1",
        "assistant" to "beta b1"
      ),
      rendered
    )
    assertEquals("Merged conversation", merged.sessionMeta.title)
    // The merged session is a brand-new file, distinct from both sources.
    assertEquals(3, store.listSessions().size)
  }

  @Test
  fun `merge returns null when a source session is missing`() {
    store.saveSession(
      SessionMeta("a1", "Alpha", 100L, 100L, "model-a"),
      chatRecord("alpha", 100L, 200L, "a1")
    )
    assertNull(store.mergeSessions("a1", "nope", "Merged conversation"))
    assertNull(store.mergeSessions("nope", "a1", "Merged conversation"))
  }
}
