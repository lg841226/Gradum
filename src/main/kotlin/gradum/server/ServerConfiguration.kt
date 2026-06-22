/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.server

data class ServerConfiguration(
    val hostAddress: String = "localhost",
    val providerName: String = "ollama",
    val portNumber: Int = 8765,
    val maximumSessions: Int = 10,
    val sessionTimeoutSeconds: Int = 3600,
    val contextWindowSize: Int = 4096,
    val maxGenerationTokens: Int = 16384,
    val requestTimeoutSeconds: Int = 3600,
    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
    val debugMode: Boolean = false,
    val enableThinking: Boolean = false,
    val defaultModel: String? = null,
    val serverBaseUrl: String? = null
)
