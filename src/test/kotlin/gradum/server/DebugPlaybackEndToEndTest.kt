package gradum.server

import gradum.server.persistence.SessionStateStore
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Verifies the debug tool-call playback mode end-to-end: manuscript
 * `<tls>` scenarios posted to `/events` run through the real skill
 * pipeline (no LLM), stream the expected NDJSON events, and record a
 * result file under the project's `.gradum/recordings/`.
 *
 * Most cases run against a throwaway temp directory so they neither
 * depend on the repo layout nor touch real source files; the happy-path
 * case reuses the current working directory so `read_file`/`grep` can
 * open a real file.
 */
class DebugPlaybackEndToEndTest {

  @Test
  fun `plays a scenario and streams playback events`(): Unit =
    testApplication {
      application { module(ServerConfiguration()) }
      val projectRoot = System.getProperty("user.dir")

      val scenarioXml = """
      <tls>
        <tt>Let me inspect the error codes first.</tt>
        <t nam="read_file" pth="src/main/kotlin/gradum/ErrorCode.kt" lin="1-10"/>
        <tt>Found them; searching for usages.</tt>
        <t nam="grep" pth="src/main" ptr="TOOL_NOT_PERMITTED" include="*.java" limit="5"/>
        <t nam="read_file" pth="gradum-does-not-exist.txt" exp="error"/>
      </tls>
    """.trimIndent()

      val body = postScenario(client, Path.of(projectRoot), scenarioXml)

      assertTrue(
        body.lines().any {
          it.contains(other = "\"type\":\"playback_start\"")
        },
        "missing playback_start"
      )
      assertTrue(
        body.lines().any {
          it.contains(other = "\"type\":\"response\"")
            && it.contains(other = "Let me inspect the error codes first.")
        },
        "missing response narration"
      )
      assertTrue(
        body.lines().any {
          it.contains(other = "\"type\":\"tool_call\"")
        },
        "missing tool_call"
      )
      assertTrue(
        body.lines().any {
          it.contains(other = "\"type\":\"playback_end\"")
        },
        "missing playback_end"
      )
      assertTrue(
        body.lines().any {
          it.contains(other = "\"type\":\"session_end\"")
        },
        "missing session_end"
      )
      assertTrue(
        body.lines().any {
          it.contains(other = "playback_2")
            && it.contains(other = "\"tool\":\"grep\"")
        },
        "grep tool_call event should carry the playback call id"
      )
    }

  @Test
  fun `session start carries the server-allocated ids when the request omits them`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project = Files.createTempDirectory("gradum_playback_ids_")
    val body = postScenario(client, project, scenarioXml = """<tls nam="ids"><tt>hello</tt></tls>""")

    val start: String = body.lines().first { it.contains(other = "\"type\":\"session_start\"") }
    val sessionId: String = Regex(""""sessionId":"([^"]*)"""").find(start)?.groupValues?.get(1).orEmpty()
    val messageId: String = Regex(""""messageId":"([^"]*)"""").find(start)?.groupValues?.get(1).orEmpty()

    assertTrue(sessionId.isNotBlank(), "the server should name the session")
    assertTrue(messageId.isNotBlank(), "the server should name the turn's user message")
  }

  /**
   * Playback is only the vehicle here: it produces a deterministic NDJSON
   * stream without an LLM, which is what this test needs to check the
   * envelope fields (`seq`, `gap`, `runId`, `sessionId`) the client relies on
   * to detect a lost event and to reconcile against the right session.
   */
  @Test
  fun `every streamed event carries a contiguous sequence and both ids`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val project = temporaryProject()
    val scenarioXml = """
      <tls nam="seq_probe">
        <tt>Looking for the file.</tt>
        <t nam="read_file" pth="gradum-does-not-exist.txt" exp="error"/>
      </tls>
    """.trimIndent()

    val body = postScenario(client, project, scenarioXml, sessionId = "seq-probe-session")
    val sequences: List<Long> = body.lines()
      .filter { it.isNotBlank() }
      .map { line ->
        val match = Regex(""""seq":(\d+)""").find(line)
        match?.groupValues?.get(1)?.toLong() ?: fail(message = "event without a seq field: $line")
      }

    assertEquals(
      (1L..sequences.size.toLong()).toList(),
      sequences,
      "seq must be contiguous and start at 1"
    )
    val eventsWithoutZeroGap: List<String> = body.lines()
      .filter { it.isNotBlank() && !it.contains(other = "\"gap\":0") }

    assertTrue(
      eventsWithoutZeroGap.isEmpty(),
      "no event should report a dropped predecessor, offenders=$eventsWithoutZeroGap"
    )
    val eventsWithoutSessionId: List<String> = body.lines()
      .filter { it.isNotBlank() && !it.contains(other = "\"sessionId\":\"seq-probe-session\"") }

    assertTrue(
      eventsWithoutSessionId.isEmpty(),
      "every event should carry the session id, offenders=$eventsWithoutSessionId"
    )
    val eventsWithoutRunId: List<String> = body.lines()
      .filter { it.isNotBlank() && !Regex(""""runId":"[0-9a-f-]{36}"""").containsMatchIn(it) }

    assertTrue(
      eventsWithoutRunId.isEmpty(),
      "every event should carry a run id, offenders=$eventsWithoutRunId"
    )
  }

  @Test
  fun `session state endpoint returns the persisted rich history`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val project = temporaryProject()
    val sessionId = "state-probe-session"
    postScenario(
      client,
      project,
      scenarioXml = """<tls nam="state_probe"><tt>Looking.</tt><t nam="read_file" pth="missing.txt" exp="error"/></tls>""",
      sessionId = sessionId
    )

    assertTrue(
      Files.isRegularFile(project.resolve(".gradum").resolve("sessions").resolve(sessionId).resolve("session-state.json")),
      "the sidecar should be written next to the transcript"
    )

    val response: HttpResponse =
      client.get("/session/state?projectRoot=${project.toAbsolutePath()}&sessionId=$sessionId")
    assertEquals(HttpStatusCode.OK, response.status)

    val body: String = response.bodyAsText()
    assertContains(body, "\"sessionId\":\"$sessionId\"")
    assertContains(body, "\"isRunning\":false")
    assertContains(body, "\"messages\":[")
    assertContains(body, "\"pendingAsks\":[]")
    assertContains(body, "\"subAgents\":[]")
    assertContains(body, "\"tokenUsage\":{\"promptTokens\":0")
  }

  @Test
  fun `session state endpoint reports an open ask card for the session`(): Unit = testApplication {
    val project = temporaryProject()
    val sessionId = "ask-probe-session"
    val parked = gradum.skill.PendingQuestions()
    val parkedPayload: Map<String, Any> = mapOf("requestId" to "req-1", "title" to "Run it?")
    val blocker = Thread { parked.await(sessionId, "req-1", parkedPayload) }
    blocker.start()
    val parkedDeadline: Long = System.currentTimeMillis() + 2000
    while (!parked.isPending(sessionId, "req-1") && System.currentTimeMillis() < parkedDeadline) {
      Thread.sleep(5)
    }

    SessionStateStore.write(project, sessionId, "model-x", emptyList())

    application { module(ServerConfiguration(), pendingQuestions = parked) }

    val response: HttpResponse =
      client.get("/session/state?projectRoot=${project.toAbsolutePath()}&sessionId=$sessionId")
    assertEquals(HttpStatusCode.OK, response.status)

    val body: String = response.bodyAsText()
    assertContains(body, "\"pendingAsks\":[{\"requestId\":\"req-1\",\"title\":\"Run it?\"}]")
    assertContains(body, "\"subAgents\":[]")

    parked.completeCancelled(sessionId, "req-1")
    blocker.join(2000)
  }

  @Test
  fun `session state endpoint rejects a missing session id`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/session/state?projectRoot=/tmp")

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `invalid scenario xml surfaces an INVALID_SCENARIO_XML error`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(client, temporaryProject(), scenarioXml = "this is not xml")

    assertNoPlayback(body)
    assertTrue(
      body.lines().any {
        it.contains(other = "\"code\":\"INVALID_SCENARIO_XML\"")
      },
      "missing INVALID_SCENARIO_XML error event"
    )
  }

  @Test
  fun `wrong root element is rejected`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(
      client,
      temporaryProject(),
      scenarioXml = """
      <scenario>
        <t nam="glob" pth="src" ptr="*"/>
      </scenario>
      """.trimIndent()
    )

    assertNoPlayback(body)
    assertTrue(
      body.lines().any {
        it.contains(other = "\"code\":\"INVALID_SCENARIO_XML\"") && it.contains(other = "Expected root element")
      },
      "root mismatch should produce an INVALID_SCENARIO_XML error"
    )
  }

  @Test
  fun `scenario with no tools is rejected`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(client, temporaryProject(), "<tls nam=\"empty\"/>")

    assertNoPlayback(body)
    assertTrue(
      body.lines().any { it.contains("\"code\":\"INVALID_SCENARIO_XML\"") && it.contains("no <t> tool entries") },
      "empty scenario should be rejected as INVALID_SCENARIO_XML"
    )
  }

  @Test
  fun `unknown tool streams a SKILL_NOT_FOUND tool_call and error`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(
      client,
      temporaryProject(),
      """
      <tls>
        <t nam="no_such_skill" pth="x"/>
      </tls>
      """.trimIndent()
    )

    assertTrue(body.lines().any { it.contains("\"type\":\"tool_call\"") && it.contains("no_such_skill") }, "missing tool_call")
    assertTrue(
      body.lines().any { it.contains("\"code\":\"SKILL_NOT_FOUND\"") },
      "missing SKILL_NOT_FOUND error event"
    )
    assertTrue(
      body.lines().any { it.contains("\"type\":\"tool_expect_mismatch\"") },
      "default (success) expectation on a failing call should be a mismatch"
    )
  }

  @Test
  fun `expected failure passes without a mismatch`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(
      client,
      temporaryProject(),
      """
      <tls>
        <t nam="read_file" pth="does-not-exist.txt" exp="error"/>
      </tls>
      """.trimIndent()
    )

    assertTrue(actual = body.lines().any {
      it.contains(other = "\"type\":\"tool_call\"")
    }, message = "missing too other = l_call")
    assertTrue(actual = body.lines().any {
      it.contains(other = "\"code\":\"FILE_NOT_FOUND\"")
    }, message = "missing FILE_NOT_FOUND error")
    assertTrue(
      actual = body.lines().none {
        it.contains(other = "\"type\":\"tool_expect_mismatch\"")
      },
      message = "expected failure must not be a mismatch"
    )
    assertTrue(
      actual = body.lines().any {
        it.contains(other = "\"type\":\"playback_end\"")
          && it.contains(other = "\"mismatchCount\":0")
      },
      message = "playback_end should report zero mismatches"
    )
  }

  @Test
  fun `unexpected failure surfaces as a tool_expect_mismatch`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(
      client,
      temporaryProject(),
      scenarioXml = """
      <tls>
        <t nam="read_file" pth="does-not-exist.txt"/>
      </tls>
      """.trimIndent()
    )

    assertTrue(
      body.lines().any {
        it.contains(other = "\"type\":\"tool_expect_mismatch\"")
      },
      "missing tool_expect_mismatch for an unexpected failure"
    )
    assertTrue(
      body.lines().any {
        it.contains(other = "\"type\":\"playback_end\"")
          && it.contains("\"mismatchCount\":1")
      },
      "playback_end should report one mismatch"
    )
  }

  @Test
  fun `narration-only scenario streams response but no tool_call`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(
      client, temporaryProject(),
      scenarioXml = """
      <tls>
        <tt>Just talking about the plan.</tt>
      </tls>
      """.trimIndent()
    )

    assertTrue(
      body.lines().any {
        it.contains(
          other = "\"type\":\"response\""
        ) && it.contains(other = "Just talking about the plan.")
      },
      "missing response narration"
    )
    assertTrue(
      body.lines().none {
        it.contains(
          other = "\"type\":\"tool_call\""
        )
      },
      "narration-only scenario must not emit tool_call"
    )
    assertTrue(
      body.lines().any {
        it.contains(
          other = "\"type\":\"playback_end\""
        ) && it.contains(other = "\"executedCalls\":0")
      },
      "playback_end should report zero executed calls"
    )
  }

  @Test
  fun `tools keep document order across narration`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val body = postScenario(
      client,
      temporaryProject(),
      scenarioXml = """
      <tls>
        <tt>first</tt>
        <t nam="read_file" pth="a.txt" exp="error"/>
        <tt>second</tt>
        <t nam="read_file" pth="b.txt" exp="error"/>
      </tls>
      """.trimIndent()
    )

    val toolLines: List<String> = body.lines().filter {
      it.contains(other = "\"type\":\"tool_call\"")
    }
    assertEquals(
      2,
      toolLines.size,
      "expected exactly two tool_call events, got ${toolLines.size}"
    )
    assertTrue(
      toolLines[0].contains("a.txt"),
      "first tool_call should be the first tool"
    )
    assertTrue(
      toolLines[1].contains("b.txt"),
      "second tool_call should be the second tool"
    )
  }


  @Test
  fun `recording file is written under gradum recordings dir`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project = Files.createTempDirectory("gradum_playback_rec_")
    val recordingsDir = project.resolve(".gradum").resolve("recordings")

    postScenario(
      client, project,
      scenarioXml = """<tls nam="recording_probe"><t nam="read_file" pth="x.txt" exp="error"/></tls>"""
    )

    val files = Files.list(recordingsDir).use { stream -> stream.toList() }

    assertTrue(
      Files.isDirectory(recordingsDir),
      "recordings dir should exist under the project root"
    )
    assertTrue(
      files.isNotEmpty(),
      "expected at least one recording file"
    )
    assertTrue(
      files.any { it.fileName.toString().startsWith("recording_probe-") },
      "recording file should be prefixed with the scenario name"
    )
  }

  @Test
  fun `context is written under gradum sessions dir when sessionId is sent`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project = Files.createTempDirectory("gradum_playback_session_")
    val sessionDir = project.resolve(".gradum").resolve("sessions").resolve("20260812-131500-a1b2c3")

    postScenario(
      client, project,
      scenarioXml = """<tls nam="ctx_session"><t nam="read_file" pth="x.txt" exp="error"/></tls>""",
      sessionId = "20260812-131500-a1b2c3"
    )

    assertTrue(
      Files.isRegularFile(sessionDir.resolve("context.json")),
      "context.json should be written under .gradum/sessions/<sessionId>/"
    )
    assertTrue(
      Files.notExists(project.resolve(".gradum").resolve("context.json")),
      "legacy .gradum/context.json should not be used when a sessionId is sent"
    )
  }

  @Test
  fun `allocates a session when sessionId is absent`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project = Files.createTempDirectory("gradum_playback_alloc_")
    postScenario(client, project, scenarioXml = """<tls nam="ctx_legacy"><t nam="read_file" pth="x.txt" exp="error"/></tls>""")

    assertTrue(
      Files.isDirectory(project.resolve(".gradum").resolve("sessions")),
      "the server should allocate a session directory when no sessionId is sent"
    )
    assertTrue(
      Files.notExists(project.resolve(".gradum").resolve("context.json")),
      "legacy .gradum/context.json should not be used once the server names the session"
    )
  }

  private suspend fun postScenario(client: HttpClient, project: Path, scenarioXml: String): String {
    return postScenario(client, project, scenarioXml, sessionId = null)
  }

  private suspend fun postScenario(
    client: HttpClient, project: Path, scenarioXml: String, sessionId: String?
  ): String {
    val projectRoot = project.toAbsolutePath().toString()
    val sessionField: String = if (sessionId != null) """"sessionId": "$sessionId",""" else ""
    val response: HttpResponse = client.post("/events") {
      contentType(ContentType.Application.Json)
      setBody(
        """{
          "message": "play it back",
          "projectRoot": "$projectRoot",
          $sessionField
          "toolCallXml": "${sceneEscape(raw = scenarioXml)}"
        }"""
      )
    }
    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    return response.bodyAsText()
  }

  private fun assertNoPlayback(body: String) {
    assertTrue(
      body.lines().none {
        it.contains(other = "\"type\":\"playback_start\"")
      },
      "no playback_start on parse failure"
    )
    assertTrue(
      body.lines().none {
        it.contains(other = "\"type\":\"playback_end\"")
      },
      "no playback_end on parse failure"
    )
    assertTrue(
      body.lines().none {
        it.contains(other = "\"type\":\"tool_call\"")
      },
      "no tool_call on parse failure"
    )
  }

  private fun temporaryProject(): Path = Files.createTempDirectory("gradum_playback_e2e_")
}

private fun sceneEscape(raw: String): String {
  return raw
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
}
