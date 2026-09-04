/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ChatSessionStoreTest.kt  2026-08-31 19:21:55 Changed by gwy
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
 * Store tests: save → list (most recent first) → loadProperties → delete.
 * Runs against a temp directory so no IDE project is required.
 */
class ChatSessionStoreTest {

  private lateinit var root: Path
  private lateinit var store: ChatSessionStore

  @Before
  fun setUp() {
    root = Files.createTempDirectory("gradum-store-test")
    store = ChatSessionStore(projectRoot = root)
  }

  @After
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  private fun messages(text: String): List<ChatMessage> {
    val user = ChatMessage(role = "user", content = text, timestamp = 100L)
    val assistant = ChatMessage(role = "assistant", content = "", timestamp = 200L)
      .appendEvent(ChatEvent.Response(content = "I fixed it."))
    return listOf(user, assistant)
  }

  @Test
  fun `save writes transcript and hasSession reflects it`() {
    store.saveSession(
      SessionMeta(
        title =
          "Hello world", modelName = "qwen2.5:7b", sessionId = "a1", createdAt = 100L, updatedAt = 200L
      ),
      messages(text = "Hello world")
    )
    assertTrue(store.hasSession(sessionId = "a1"))
    assertTrue(Files.isRegularFile(root.resolve(".gradum/sessions/a1/conversation.md")))
  }

  @Test
  fun `list orders sessions most recent first`() {
    store.saveSession(
      SessionMeta(title = "Old session", modelName = "model-a", sessionId = "old", createdAt = 100L, updatedAt = 100L),
      messages(text = "Old session")
    )
    store.saveSession(
      SessionMeta(title = "New session", modelName = "model-b", sessionId = "new", createdAt = 200L, updatedAt = 500L),
      messages(text = "New session")
    )
    val listed: List<SessionMeta> = store.listSessions()
    assertEquals(
      listOf("new", "old"),
      listed.map { it.sessionId }
    )
    assertEquals(
      "New session",
      listed.first().title
    )
  }

  @Test
  fun `load restores messages`() {
    store.saveSession(
      SessionMeta(title = "Hello world", modelName = "qwen2.5:7b", sessionId = "a1", createdAt = 100L, updatedAt = 200L),
      messages("Hello world")
    )
    val loaded: ChatTranscript.ParsedTranscript? = store.loadSession(sessionId = "a1")
    assertTrue("expected session to loadProperties", loaded != null)
    assertEquals(
      2,
      loaded!!.messages.size
    )
    assertEquals(
      "Hello world",
      loaded.messages[0].content
    )
    assertEquals(
      "I fixed it.",
      loaded.messages[1].fullContent
    )
  }

  @Test
  fun `load returns null for unknown session`() {
    assertEquals(
      null,
      store.loadSession("nope")
    )
  }

  @Test
  fun `delete removes the entire session directory`() {
    store.saveSession(
      SessionMeta(title = "Hello world", modelName = "qwen2.5:7b", sessionId = "a1", createdAt = 100L, updatedAt = 200L),
      messages("Hello world")
    )
    assertTrue(store.deleteSession(sessionId = "a1"))
    assertFalse(Files.exists(root.resolve(".gradum/sessions/a1")))
    assertFalse(store.hasSession(sessionId = "a1"))
  }

  @Test
  fun `delete returns false for unknown session`() {
    assertFalse(store.deleteSession(sessionId = "nope"))
  }

  @Test
  fun `delete cannot escape the sessions root`() {
    val sibling: Path = root.resolve("sneaky.txt")
    Files.writeString(sibling, "keep me")
    assertFalse(store.deleteSession(sessionId = "../sneaky.txt"))
    assertTrue(Files.exists(sibling))
  }

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

  private fun chatRecord(marker: String, userTs: Long, assistantTs: Long, reply: String): List<ChatMessage> =
    listOf(
      ChatMessage(role = "user", content = "$marker q", timestamp = userTs),
      ChatMessage(role = "assistant", content = "", timestamp = assistantTs)
        .appendEvent(ChatEvent.Response(content = "$marker $reply"))
    )

  @Test
  fun `merge interleaves two sessions chronologically and keeps originals`() {
    store.saveSession(
      SessionMeta(title = "Alpha", modelName = "model-a", sessionId = "a1", createdAt = 100L, updatedAt = 100L),
      messages = chatRecord(marker = "alpha", userTs = 100L, assistantTs = 200L, reply = "a1")
    )
    store.saveSession(
      SessionMeta(title = "Beta", modelName = "model-b", sessionId = "b1", createdAt = 300L, updatedAt = 300L),
      messages = chatRecord(marker = "beta", userTs = 150L, assistantTs = 250L, reply = "b1")
    )

    val mergedId: String? = store.mergeSessions(sessionIds = listOf("a1", "b1"), resultTitle = "Merged conversation")
    assertTrue("expected merge to succeed", mergedId != null)

    // Non-destructive: both sources still exist.
    assertTrue(store.hasSession(sessionId = "a1"))
    assertTrue(store.hasSession(sessionId = "b1"))

    val merged: ChatTranscript.ParsedTranscript? = store.loadSession(sessionId = mergedId!!)
    assertTrue("merged session should loadProperties", merged != null)
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
    assertEquals(
      "Merged conversation",
      merged.sessionMeta.title
    )
    assertEquals(
      3,
      store.listSessions().size
    )
  }

  @Test
  fun `merge returns null when a source session is missing`() {
    store.saveSession(
      SessionMeta(title = "Alpha", modelName = "model-a", sessionId = "a1", createdAt = 100L, updatedAt = 100L),
      messages = chatRecord(marker = "alpha", userTs = 100L, assistantTs = 200L, reply = "a1")
    )
    assertNull(store.mergeSessions(sessionIds = listOf("a1", "nope"), resultTitle = "Merged conversation"))
    assertNull(store.mergeSessions(sessionIds = listOf("nope", "a1"), resultTitle = "Merged conversation"))
  }

  @Test
  fun `merge interleaves three sessions chronologically`() {
    store.saveSession(
      SessionMeta(title = "Alpha", modelName = "model-a", sessionId = "a1", createdAt = 100L, updatedAt = 100L),
      messages = chatRecord(marker = "alpha", userTs = 100L, assistantTs = 300L, reply = "a1")
    )
    store.saveSession(
      SessionMeta(title = "Beta", modelName = "model-b", sessionId = "b1", createdAt = 200L, updatedAt = 200L),
      messages = chatRecord(marker = "beta", userTs = 200L, assistantTs = 400L, reply = "b1")
    )
    store.saveSession(
      SessionMeta(title = "Gamma", modelName = "model-c", sessionId = "c1", createdAt = 300L, updatedAt = 300L),
      messages = chatRecord(marker = "gamma", userTs = 150L, assistantTs = 250L, reply = "c1")
    )
    val mergedId: String? = store.mergeSessions(
      sessionIds = listOf("a1", "b1", "c1"), resultTitle = "Merged conversation 1"
    )
    assertTrue("expected merge to succeed", mergedId != null)
    val merged: ChatTranscript.ParsedTranscript? = store.loadSession(sessionId = mergedId!!)
    assertTrue("merged session should loadProperties", merged != null)
    val rendered: List<String> = merged!!.messages.map {
      if (it.isUserMessage) it.content else it.fullContent
    }
    assertEquals(
      listOf("alpha q", "gamma q", "beta q", "gamma c1", "alpha a1", "beta b1"),
      rendered
    )
    assertEquals(
      4,
      store.listSessions().size
    )
  }

  @Test
  fun `rename rewrites the title and keeps the body`() {
    store.saveSession(
      SessionMeta(title = "Auto title", modelName = "model-a", sessionId = "a1", createdAt = 100L, updatedAt = 200L),
      messages = chatRecord(marker = "alpha", userTs = 100L, assistantTs = 200L, reply = "a1")
    )
    assertTrue(store.renameSession(sessionId = "a1", newTitle = "Hand-written title"))
    val loaded: ChatTranscript.ParsedTranscript? = store.loadSession(sessionId = "a1")
    assertTrue("renamed session should loadProperties", loaded != null)
    assertEquals(
      "Hand-written title",
      loaded?.sessionMeta?.title
    )
    assertEquals(
      "alpha a1",
      loaded?.messages?.lastOrNull()?.fullContent
    )
  }

  @Test
  fun `rename ignores blank titles and unknown sessions`() {
    store.saveSession(
      SessionMeta(title = "Auto title", modelName = "model-a", sessionId = "a1", createdAt = 100L, updatedAt = 200L),
      messages = chatRecord(marker = "alpha", userTs = 100L, assistantTs = 200L, reply = "a1")
    )
    assertFalse(store.renameSession(sessionId = "a1", newTitle = "   "))
    assertFalse(store.renameSession(sessionId = "nope", newTitle = "Anything"))
    assertEquals(
      "Auto title",
      store.loadSession(sessionId = "a1")?.sessionMeta?.title
    )
  }
}
