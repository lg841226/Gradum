/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerRoutesTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.server

import gradum.Version
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerRoutesTest {

  @Test
  fun `GET health returns 200 with status and version`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/health")

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    val body: String = response.bodyAsText()

    assertContains(body, "\"status\":\"healthy\"")
    assertContains(body, "\"version\":\"${Version.GRADUM_VERSION}\"")
    assertContains(body, "\"uptimeSeconds\"")
    assertContains(body, "\"timestamp\"")
  }

  @Test
  fun `GET skills returns 200 with non-empty skills array`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/skills")

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    val body: String = response.bodyAsText()

    assertContains(body, "\"skills\"")
    assertTrue(body.contains("read_file"), "skills should include read_file")
    assertTrue(body.contains("write_file"), "skills should include write_file")
    assertFalse(body.contains("save_file"), "save_file was merged into write_file")
    assertFalse(body.contains("edit_file"), "edit_file was renamed to write_file")
  }

  @Test
  fun `GET models returns 200 with models array`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/models")

    assertEquals(
      HttpStatusCode.OK,
      response.status
    )
    val body: String = response.bodyAsText()

    assertContains(body, "\"models\"")
  }
}
