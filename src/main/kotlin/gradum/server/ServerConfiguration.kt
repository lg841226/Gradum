/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-06-30 02:35:10 Changed by gwy
 */

package gradum.server

data class ServerConfiguration(
    val hostAddress: String = "localhost",
    val providerName: String = "ollama",
    val portNumber: Int = 8765,
    val contextWindowSize: Int = 4096,
    val maxGenerationTokens: Int = 16384,
    val requestTimeoutSeconds: Int = 3600,
    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
)
