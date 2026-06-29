/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerConfiguration.kt  2026-06-30 02:29:20 Changed by gwy
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
    /**
     * Absolute path to the target project the server should operate on.
     * Resolved against CWD when relative. When `null`, the server falls
     * back to the process CWD (legacy behaviour). Forwarded to
     * `ProjectPaths.setProjectRoot(...)` from `Main.main()` so every
     * downstream file path follows the override.
     */
    val projectRootPath: String? = null,
)
