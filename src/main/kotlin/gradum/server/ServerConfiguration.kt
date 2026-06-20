/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-06-20 Created by gwy
 */

package gradum.server

data class ServerConfiguration(
    val hostAddress: String = "localhost",
    val portNumber: Int = 8765,
    val debugMode: Boolean = false,
    val maximumSessions: Int = 10,
    val sessionTimeoutSeconds: Int = 3600,
    val defaultModel: String? = null,
    val enableThinking: Boolean = false,
    val temperatureValue: Double = 0.7,
    val topPValue: Double = 0.9,
    val contextWindowSize: Int = 4096,
    val maxGenerationTokens: Int = 16384,
    val requestTimeoutSeconds: Int = 300,
    val providerName: String = "ollama",
    val serverBaseUrl: String? = null,
)
