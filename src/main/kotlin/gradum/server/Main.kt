/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Main.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.server

import org.slf4j.LoggerFactory

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("GradumMain")

fun main(arguments: Array<String>) {
    val parsedArguments: ServerArguments = parseArguments(arguments)

    val resolvedPort: Int = if (parsedArguments.autoDetectPort) {
        val detectedPort: Int? = findAvailablePort(parsedArguments.portNumber)
        checkNotNull(detectedPort) { "No available port in range ${parsedArguments.portNumber} - ${parsedArguments.portNumber + 10}; aborting server start" }
        logger.info("Using auto-detected port: $detectedPort")
        detectedPort
    } else {
        parsedArguments.portNumber
    }

    val serverConfiguration: ServerConfiguration = ServerConfiguration(
        hostAddress = parsedArguments.hostAddress,
        portNumber = resolvedPort,
        debugMode = parsedArguments.debugMode,
        defaultModel = parsedArguments.modelName,
        enableThinking = parsedArguments.enableThinking,
        providerName = parsedArguments.providerName,
        serverBaseUrl = parsedArguments.baseUrl,
    )

    val server: GradumServer = createServerInstance(serverConfiguration)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
        logger.info("Gradum Server shut down")
    })

    server.start(wait = true)
}

private data class ServerArguments(
    val hostAddress: String,
    val portNumber: Int,
    val autoDetectPort: Boolean,
    val portRange: String,
    val debugMode: Boolean,
    val modelName: String?,
    val enableThinking: Boolean,
    val providerName: String,
    val baseUrl: String?,
)

private fun parseArguments(arguments: Array<String>): ServerArguments {
    var hostAddress: String = "localhost"
    var portNumber: Int = 8765
    var autoDetectPort: Boolean = false
    var portRange: String = "8765-8775"
    var debugMode: Boolean = false
    var modelName: String? = null
    var enableThinking: Boolean = false
    var providerName: String = "ollama"
    var baseUrl: String? = null

    val iterator: Iterator<String> = arguments.iterator()
    while (iterator.hasNext()) {
        when (val flag: String = iterator.next()) {
            "--host" -> hostAddress = iterator.next()
            "--port" -> portNumber = iterator.next().toIntOrNull() ?: portNumber
            "--auto-port" -> autoDetectPort = true
            "--port-range" -> portRange = iterator.next()
            "--debug" -> debugMode = true
            "--model" -> modelName = iterator.next()
            "--think" -> enableThinking = true
            "--provider" -> providerName = iterator.next()
            "--base-url" -> baseUrl = iterator.next()
            "--help" -> {
                printUsage()
            }
        }
    }

    return ServerArguments(
        hostAddress = hostAddress,
        portNumber = portNumber,
        autoDetectPort = autoDetectPort,
        portRange = portRange,
        debugMode = debugMode,
        modelName = modelName,
        enableThinking = enableThinking,
        providerName = providerName,
        baseUrl = baseUrl,
    )
}

private fun printUsage(): Unit {
    println("Gradum HTTP Server")
    println()
    println("Usage: java -jar gradum.jar [options]")
    println()
    println("Options:")
    println("  --host <host>           Host to bind (default: localhost)")
    println("  --port <port>           Port to bind (default: 8765)")
    println("  --auto-port             Auto-find available port")
    println("  --port-range <range>    Port range for auto-port (default: 8765-8775)")
    println("  --debug                 Enable debug mode")
    println("  --model <model>         Default model name")
    println("  --think                 Enable thinking mode")
    println("  --provider <provider>   LLM provider (ollama/openai)")
    println("  --base-url <url>        LLM server URL")
    println("  --help                  Show this help message")
}
