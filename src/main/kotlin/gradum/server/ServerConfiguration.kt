/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-07-05 22:56:12 Changed by gwy
 */

package gradum.server

data class ServerConfiguration(
  val hostAddress: String = "localhost",
  val portNumber: Int = 8765,
)
