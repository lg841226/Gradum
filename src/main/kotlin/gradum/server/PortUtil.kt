/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PortUtil.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.server

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket

private const val TIME_OUT = 5000

private val logger: Logger = LoggerFactory.getLogger("PortUtil")

fun isPortAvailable(checkPort: Int, hostAddress: String = ServerConfiguration.DEFAULT_HOST_ADDRESS): Boolean {
  return try {
    Socket().use { socket ->
      socket.connect(
        InetSocketAddress(hostAddress, checkPort), TIME_OUT
      )
      false
    }
  } catch (connectException: Exception) {
    logger.warn("Could not connect to $checkPort - ${connectException.message}")
    true
  }
}

fun findAvailablePort(
  startPort: Int,
  maxAttempts: Int = 10,
  hostAddress: String = ServerConfiguration.DEFAULT_HOST_ADDRESS
): Int? {
  for (port in startPort until startPort + maxAttempts) {
    if (isPortAvailable(checkPort = port, hostAddress)) {
      return port
    }
  }
  logger.error("Could not find available port in range$startPort ~ ${startPort + maxAttempts}")
  return null
}
