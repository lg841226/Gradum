package gradum.server.persistence

import gradum.agent.MessageRecord
import gradum.agent.assistantMessage
import gradum.agent.userMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionMergerTest {

  @Test
  fun `merge weaves sources by updatedAt and writes sidecar plus transcript`() {
    val projectRoot: Path = Files.createTempDirectory("gradum-merge")
    seedSession(
      projectRoot,
      sessionId = "alpha",
      messages = turn("alpha q", "alpha a1"),
      updatedAt = 1_000L,
      createdAt = 400L
    )
    seedSession(
      projectRoot,
      sessionId = "beta",
      messages = turn("beta q", "beta a1"),
      updatedAt = 2_000L,
      createdAt = 1_500L
    )

    val mergeResult: SessionMerger.MergeResult? =
      SessionMerger.merge(projectRoot, listOf("beta", "alpha"), baseTitle = "Merged conversation")

    assertNotNull(mergeResult)
    assertEquals("Merged conversation 1", mergeResult.title)
    assertEquals(
      listOf("alpha q", "alpha a1", "beta q", "beta a1"),
      storedContents(projectRoot, mergeResult.sessionId)
    )
    val mergedMeta: SessionTranscriptWriter.TranscriptMeta? =
      SessionTranscriptWriter.readMeta(projectRoot, mergeResult.sessionId)
    assertEquals("Merged conversation 1", mergedMeta?.title)
    assertEquals(400L, mergedMeta?.createdAt)
  }

  @Test
  fun `merge numbers the title past the highest existing one`() {
    val projectRoot: Path = Files.createTempDirectory("gradum-merge")
    seedSession(
      projectRoot,
      sessionId = "prior",
      messages = turn("prior q", "prior a1"),
      updatedAt = 500L,
      title = "Merged conversation 3"
    )
    seedSession(projectRoot, sessionId = "alpha", messages = turn("alpha q", "alpha a1"), updatedAt = 1_000L)
    seedSession(projectRoot, sessionId = "beta", messages = turn("beta q", "beta a1"), updatedAt = 2_000L)

    val mergeResult: SessionMerger.MergeResult? =
      SessionMerger.merge(projectRoot, listOf("alpha", "beta"), baseTitle = "Merged conversation")

    assertNotNull(mergeResult)
    assertEquals("Merged conversation 4", mergeResult.title)
  }

  @Test
  fun `merge fails when a source has no sidecar`() {
    val projectRoot: Path = Files.createTempDirectory("gradum-merge")
    seedSession(projectRoot, sessionId = "alpha", messages = turn("alpha q", "alpha a1"), updatedAt = 1_000L)

    assertNull(SessionMerger.merge(projectRoot, listOf("alpha", "missing"), baseTitle = "Merged conversation"))
  }

  private fun turn(userText: String, assistantText: String): List<MessageRecord> = listOf(
    userMessage { id = "$userText-id"; text = userText },
    assistantMessage { content = assistantText; modelName = "test-model" }
  )

  // Sidecar via the real writer; a hand-written header so the timestamps are deterministic.
  private fun seedSession(
    projectRoot: Path,
    sessionId: String,
    messages: List<MessageRecord>,
    updatedAt: Long,
    createdAt: Long = updatedAt,
    title: String = sessionId
  ) {
    SessionStateStore.write(
      projectRoot = projectRoot,
      sessionId = sessionId,
      modelName = "test-model",
      messages = messages
    )
    val sessionDirectory: Path = projectRoot.resolve(".gradum").resolve("sessions").resolve(sessionId)
    Files.createDirectories(sessionDirectory)
    Files.writeString(
      sessionDirectory.resolve(SessionTranscriptWriter.TRANSCRIPT_FILE),
      "<!-- gradum-transcript v1 -->\n" +
        "<!-- gradum-session id=\"$sessionId\" title=\"$title\" createdAt=\"$createdAt\" " +
        "updatedAt=\"$updatedAt\" model=\"test-model\" -->\n"
    )
  }

  private fun storedContents(projectRoot: Path, sessionId: String): List<String> {
    val storedState: JsonObject = SessionStateStore.read(projectRoot, sessionId) ?: return emptyList()
    return (storedState["messages"] as? JsonArray)?.mapNotNull { element ->
      (element as? JsonObject)?.get("content")?.jsonPrimitive?.content
    } ?: emptyList()
  }
}
