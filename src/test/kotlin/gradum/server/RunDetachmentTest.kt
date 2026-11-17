package gradum.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A run must survive the death of its subscriber: the agent executes on the
 * application scope, `/events?runId=&since=` replays the buffered frames to a
 * reconnecting client, and a stopped or reclaimed run answers 404. Playback
 * scenarios are the vehicle — deterministic NDJSON without an LLM.
 */
class RunDetachmentTest {

  @Test
  fun `a run keeps going after its subscriber disconnects and a reconnect replays the whole turn`(): Unit =
    testApplication {
      application { module(ServerConfiguration()) }
      val project = Files.createTempDirectory("gradum_detach_")

      val firstFrame: String = client.preparePost("/events") {
        contentType(ContentType.Application.Json)
        setBody(scenarioBody(project, sessionId = "detach-session"))
      }.execute { response ->
        assertEquals(HttpStatusCode.OK, response.status)
        val channel: ByteReadChannel = response.bodyAsChannel()
        channel.readUTF8Line() ?: error("the stream produced no frame before the disconnect")
      }

      val runId: String = Regex(""""runId":"([^"]+)"""").find(firstFrame)?.groupValues?.get(1)
        ?: error("the first frame carries no runId: $firstFrame")

      // Reconnect from the very start: the buffer must hold the full run,
      // including every event emitted after the subscriber walked away.
      val replayed: List<String> = client.preparePost("/events?runId=$runId&since=0") {
        contentType(ContentType.Application.Json)
        setBody("{}")
      }.execute { response ->
        assertEquals(HttpStatusCode.OK, response.status)
        val channel: ByteReadChannel = response.bodyAsChannel()
        val lines = mutableListOf<String>()
        while (true) {
          val line: String = channel.readUTF8Line() ?: break
          if (line.isNotBlank()) {
            lines.add(line)
          }
        }
        lines
      }

      assertTrue(
        replayed.size > 1,
        "the run must have kept emitting after the disconnect, replay=${replayed.size} frame(s)"
      )
      val sequences: List<Long> = replayed.map {
        Regex(""""seq":(\d+)""").find(it)?.groupValues?.get(1)?.toLong()
          ?: error("frame without a seq: $it")
      }
      assertEquals(
        (1L..sequences.size.toLong()).toList(),
        sequences,
        "the replay must be contiguous from the first frame"
      )
      assertTrue(
        replayed.any { it.contains("\"type\":\"session_end\"") },
        "the replay must reach the run's end, replay=$replayed"
      )
      assertTrue(
        replayed.all { it.contains(""""runId":"$runId"""") },
        "every replayed frame should carry the run id"
      )
    }

  @Test
  fun `reconnecting to an unknown run answers 404 with json`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse =
      client.post("/events?runId=00000000-0000-0000-0000-000000000000&since=0") {
        contentType(ContentType.Application.Json)
        setBody("{}")
      }

    assertEquals(HttpStatusCode.NotFound, response.status)
    assertContains(response.bodyAsText(), "not_found")
  }

  private fun scenarioBody(project: Path, sessionId: String): String {
    val scenarioXml = """
      <tls nam="detach_probe">
        <tt>Looking for the file.</tt>
        <t nam="read_file" pth="gradum-does-not-exist.txt" exp="error"/>
      </tls>
    """.trimIndent()
    val escaped: String = scenarioXml
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
    return """{
      "message": "play it back",
      "projectRoot": "${project.toAbsolutePath()}",
      "sessionId": "$sessionId",
      "toolCallXml": "$escaped"
    }"""
  }
}
