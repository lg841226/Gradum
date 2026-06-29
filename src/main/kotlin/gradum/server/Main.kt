/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Main.kt  2026-06-21 07:53:44 Changed by gwy
 */

package gradum.server

import gradum.ProjectPaths
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import java.nio.file.Path
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("GradumMain")

fun main(arguments: Array<String>) {
    val parsedArguments: ServerArguments = parseArguments(arguments)

    // Resolve --project-root before any skill touches ProjectPaths so that
    // log directories, context storage, and run_cmd cwd all follow the
    // override. When omitted we leave the default (process CWD) in place.
    parsedArguments.projectRootPath?.let { rawPath: String ->
        val resolvedRoot: Path = Paths.get(rawPath).toAbsolutePath().normalize()
        ProjectPaths.setProjectRoot(resolvedRoot)
        logger.info("Project root overridden to: $resolvedRoot")
    }

    val resolvedPort: Int = if (parsedArguments.autoDetectPort) {
        val detectedPort: Int? = findAvailablePort(parsedArguments.portNumber)

        // fail-fast: Ktor would throw on bind(-1) with a less informative "port out of range" message.
        checkNotNull(detectedPort) { "No available port in range ${parsedArguments.portNumber} - ${parsedArguments.portNumber + 10}; aborting server start" }
        logger.info("Using auto-detected port: $detectedPort")
        detectedPort
    } else {
        parsedArguments.portNumber
    }

    val serverConfiguration = ServerConfiguration(
        hostAddress = parsedArguments.hostAddress,
        portNumber = resolvedPort,
        providerName = parsedArguments.providerName,
        projectRootPath = parsedArguments.projectRootPath,
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
    val providerName: String,
    val projectRootPath: String?,
)

private fun parseArguments(arguments: Array<String>): ServerArguments {
    var hostAddress = "localhost"
    var portRange = "8765-8775"
    var providerName = "ollama"
    var portNumber = 8765
    var autoDetectPort = false
    var projectRootPath: String? = null

    val iterator: Iterator<String> = arguments.iterator()
    while (iterator.hasNext()) {
        when (iterator.next()) {
            "--host" -> hostAddress = iterator.next()
            "--port" -> portNumber = iterator.next().toIntOrNull() ?: portNumber
            "--auto-port" -> autoDetectPort = true
            "--port-range" -> portRange = iterator.next()
            "--provider" -> providerName = iterator.next()
            "--project-root" -> projectRootPath = iterator.next()
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
        providerName = providerName,
        projectRootPath = projectRootPath,
    )
}

private fun printUsage() {
    println("Gradum HTTP Server")
    println()
    println("Usage: java -jar gradum@<version>.jar [options]")
    println()
    println("Available Options:")
    println("  --host <host>           Host to bind (default: localhost)")
    println("  --port <port>           Port to bind (default: 8765)")
    println("  --auto-port             Auto-find available port")
    println("  --port-range <range>    Port range for auto-port (default: 8765-8775)")
    println("  --provider <provider>   LLM provider (ollama/openai)")
    println("  --project-root <path>   Override the project root (defaults to CWD).")
    println("                          Use this to run the server from a Gradle Run Config")
    println("                          while operating on a different target project.")
    println("  --help                  Show this help message")
}
