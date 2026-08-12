/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-07-14 21:27:12 Changed by gwy
 */

package gradum.server

/** Configuration for the Gradum embedded HTTP server. */
data class ServerConfiguration(
  val hostAddress: String = DEFAULT_HOST_ADDRESS,
  val portNumber: Int = DEFAULT_PORT_NUMBER,
) {
  companion object {
    /** Default bind host; the single source of truth. */
    const val DEFAULT_HOST_ADDRESS: String = "localhost"

    /** Default bind port; the single source of truth. */
    const val DEFAULT_PORT_NUMBER: Int = 8765
  }
}
