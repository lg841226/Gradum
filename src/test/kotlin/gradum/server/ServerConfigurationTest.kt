/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfigurationTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigurationTest {

  @Test
  fun `default configuration binds localhost on port 8765`() {
    val configuration = ServerConfiguration()

    assertEquals(
      "localhost",
      configuration.hostAddress
    )
    assertEquals(
      8765,
      configuration.portNumber
    )
  }

  @Test
  fun `explicit host and port are preserved`() {
    val configuration = ServerConfiguration(
      portNumber = 9999,
      hostAddress = "127.0.0.1",
    )

    assertEquals(
      "127.0.0.1",
      configuration.hostAddress
    )
    assertEquals(
      9999,
      configuration.portNumber
    )
  }

  @Test
  fun `configuration equality compares host and port`() {
    val first = ServerConfiguration(portNumber = 7000, hostAddress = "0.0.0.0")
    val second = ServerConfiguration(portNumber = 7000, hostAddress = "0.0.0.0")

    assertEquals(
      first,
      second
    )
  }
}
