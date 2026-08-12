/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Main.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.server

import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("Main")

fun main(arguments: Array<String>) {
  val parsedArguments: ServerArguments = parseArguments(arguments)

  val resolvedPort: Int = if (parsedArguments.autoDetectPort) {
    val detectedPort: Int? = findAvailablePort(parsedArguments.portNumber)

    // fail-fast: Ktor would throw on bind(-1) with a less informative "port out of range" message.
    checkNotNull(detectedPort) {
      "No available port in range ${parsedArguments.portNumber} - " +
        "${parsedArguments.portNumber + 10}; aborting server start"
    }

    logger.info("Using auto-detected port: $detectedPort")
    detectedPort
  } else
    parsedArguments.portNumber

  val serverConfiguration = ServerConfiguration(
    hostAddress = parsedArguments.hostAddress,
    portNumber = resolvedPort
  )

  val server: GradumServer = createServerInstance(serverConfiguration)

  Runtime.getRuntime().addShutdownHook(Thread {
    server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
    logger.info("Gradum Server has been shut down successfully")
  })

  server.start(wait = true)
}

private data class ServerArguments(
  val hostAddress: String,
  val portNumber: Int,
  val autoDetectPort: Boolean,
)

private fun parseArguments(arguments: Array<String>): ServerArguments {
  var hostAddress = ServerConfiguration.DEFAULT_HOST_ADDRESS
  var portNumber = ServerConfiguration.DEFAULT_PORT_NUMBER
  var autoDetectPort = false

  val iterator: Iterator<String> = arguments.iterator()
  while (iterator.hasNext()) {
    when (iterator.next()) {
      "--host" -> hostAddress = iterator.next()
      "--port" -> portNumber = iterator.next().toIntOrNull() ?: portNumber
      "--auto-port" -> autoDetectPort = true
      "--help" -> {
        printUsage()
      }
    }
  }

  return ServerArguments(
    hostAddress = hostAddress,
    portNumber = portNumber,
    autoDetectPort = autoDetectPort,
  )
}

private fun printUsage() {
  println(
    """
        |Gradum HTTP Server
        |
        |Usage: java -jar gradum@<version>.jar [options]
        |
        |Available Options:
        |  --host <host>          Host to bind (default: ${ServerConfiguration.DEFAULT_HOST_ADDRESS})
        |  --port <port>          Port to bind (default: ${ServerConfiguration.DEFAULT_PORT_NUMBER})
        |  --auto-port            Auto-find available port
        |  --base-url <url>       Provider base URL (default: ${gradum.AgentConfiguration.DEFAULT_OLLAMA_BASE_URL})
        |  --model <name>         Default model name
        |  --think                Enable thinking mode (default: off)
        |  --project-root <path>  Project root path for the session
        |  --help                 Show this help message
        |
        |HTTP Endpoints:
        |  POST /events   Execute agent, returns NDJSON stream
        |  POST /stop     Stop current agent task
        |  GET  /health   Liveness probe
        |  GET  /models   List available models
        |  GET  /skills   List registered skills
        |
        |Note: projectRoot is sent by the plugin on every /events request.
        |      It is not a server-side configuration.
    """.trimMargin()
  )
}
