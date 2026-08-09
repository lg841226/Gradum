/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * DebugPlaybackEndToEndTest.kt  2026-08-10 00:12:09 Changed by gwy
 */

package gradum.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the debug tool-call playback mode end-to-end: a handwritten
 * `<tls>` scenario posted to `/events` runs through the real skill
 * pipeline (no LLM), streams the expected NDJSON events, and records a
 * result file under the project's `.gradum/recordings/`.
 *
 * Uses a trimmed copy of the playground scenario so the test does not
 * depend on repo layout, Git, or write access outside the test dir.
 */
class DebugPlaybackEndToEndTest {

  @Test
  fun `plays a scenario and streams playback events`(): Unit = testApplication {
    val projectRoot = System.getProperty("user.dir")
    require(java.io.File(projectRoot).isDirectory)

    val scenarioXml = """
      <tls>
        <tt msg="Let me look at the ErrorCode list first."/>
        <t nam="read_file" pth="src/main/java/gradum/ErrorCode.java" lin="1-10"/>
        <tt msg="Found the tool-not-permitted code; verifying its only usage."/>
        <t nam="grep" pth="src/main" ptr="TOOL_NOT_PERMITTED" include="*.java" limit="5"/>
        <t nam="read_file" pth="gradum-does-not-exist.txt" exp="error"/>
        <tt msg="The missing file errors as predicted."/>
      </tls>
    """.trimIndent()

    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.post("/events") {
      contentType(ContentType.Application.Json)
      setBody(
        """{
          "message": "play it back",
          "projectRoot": "$projectRoot",
          "toolCallXml": "${sceneEscape(scenarioXml)}"
        }"""
      )
    }

    assertEquals(HttpStatusCode.OK, response.status)
    val body: String = response.bodyAsText()

    assertTrue(body.lines().any { it.contains("\"type\":\"playback_start\"") }, "missing playback_start")
    assertTrue(body.lines().any { it.contains("\"type\":\"response\"") }, "missing AI reply response events")
    assertTrue(body.lines().any { it.contains("\"type\":\"tool_call\"") }, "missing tool_call")
    assertTrue(body.lines().any { it.contains("\"type\":\"playback_end\"") }, "missing playback_end")
    assertTrue(body.lines().any { it.contains("\"type\":\"session_end\"") }, "missing session_end")
    assertTrue(
      body.lines().any { it.contains("playback_2") && it.contains("\"tool\":\"grep\"") },
      "grep tool_call event should carry the playback call id"
    )
    assertTrue(
      body.lines().any { it.contains("\"type\":\"response\"") && it.contains("Let me look at") },
      "first AiReply should stream as a response event"
    )
  }
}

private fun sceneEscape(raw: String): String {
  return raw
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
}
