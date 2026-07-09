/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerRoutesTest.kt  2026-06-30 23:35:47 Changed by gwy
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
import kotlin.test.assertTrue

class ServerRoutesTest {

  @Test
  fun `GET health returns 200 with status and version`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/health")

    assertEquals(HttpStatusCode.OK, response.status)
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

    assertEquals(HttpStatusCode.OK, response.status)
    val body: String = response.bodyAsText()

    assertContains(body, "\"skills\"")
    assertTrue(body.contains("read_file"), "skills should include read_file")
    assertTrue(body.contains("edit_file"), "skills should include edit_file")
    assertTrue(body.contains("save_file"), "skills should include save_file")
  }

  @Test
  fun `GET models returns 200 with models array`(): Unit = testApplication {
    application { module(ServerConfiguration()) }

    val response: HttpResponse = client.get("/models")

    assertEquals(HttpStatusCode.OK, response.status)
    val body: String = response.bodyAsText()

    assertContains(body, "\"models\"")
  }
}
