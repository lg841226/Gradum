/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PortUtilTest.kt  2026-08-09 20:30:46 Changed by gwy
 */

package gradum.server

import java.net.ServerSocket
import kotlin.test.*

class PortUtilTest {

  @Test
  fun `isPortAvailable reports an unreachable port as available`() {
    val serverSocket = ServerSocket(0)
    val freePort = serverSocket.localPort
    serverSocket.close()

    assertTrue(isPortAvailable(freePort), "port should be free again after the socket closes")
  }

  @Test
  fun `isPortAvailable reports a listening port as unavailable`() {
    val serverSocket = ServerSocket(0)
    serverSocket.use { serverSocket ->
      assertFalse(isPortAvailable(serverSocket.localPort), "bound port must not be available")
    }
  }

  @Test
  fun `findAvailablePort returns the passed start port when it is free`() {
    val serverSocket = ServerSocket(0)
    val freePort = serverSocket.localPort
    serverSocket.close()

    val found = findAvailablePort(freePort, maxAttempts = 1)
    assertEquals(freePort, found)
  }

  @Test
  fun `findAvailablePort returns null when a single-port range is occupied`() {
    val serverSocket = ServerSocket(0)
    serverSocket.use { serverSocket ->
      val found = findAvailablePort(serverSocket.localPort, maxAttempts = 1)
      assertNull(found, "a fully occupied single-port range leads to null")
    }
  }
}
