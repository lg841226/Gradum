/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DebugPlaybackEndToEndTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.server

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
  fun `plays a scenario and streams playback events`(): Unit = testApplication {
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
  fun `context falls back to legacy file when sessionId is absent`(): Unit = testApplication {
    application { module(ServerConfiguration()) }
    val project = Files.createTempDirectory("gradum_playback_legacy_")
    postScenario(client, project, scenarioXml = """<tls nam="ctx_legacy"><t nam="read_file" pth="x.txt" exp="error"/></tls>""")

    assertTrue(
      Files.isRegularFile(project.resolve(".gradum").resolve("context.json")),
      "legacy .gradum/context.json should still be used when no sessionId is sent"
    )
    assertTrue(
      Files.notExists(project.resolve(".gradum").resolve("sessions")),
      "no sessions dir should be created when no sessionId is sent"
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
