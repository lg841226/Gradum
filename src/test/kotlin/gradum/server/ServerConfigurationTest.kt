/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfigurationTest.kt  2026-08-25 14:21:49 Changed by gwy
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
      hostAddress = "127.0.0.1",
      portNumber = 9999,
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
    val first = ServerConfiguration(hostAddress = "0.0.0.0", portNumber = 7000)
    val second = ServerConfiguration(hostAddress = "0.0.0.0", portNumber = 7000)

    assertEquals(
      first,
      second
    )
  }
}
