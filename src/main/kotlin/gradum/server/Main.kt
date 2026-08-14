/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Main.kt  2026-08-14 22:29:44 Changed by gwy
 */

package gradum.server

import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("Main")

fun main(arguments: Array<String>) {
  val parsedArguments: ServerArguments = parseArguments(arguments)

  val resolvedPort: Int = if (parsedArguments.autoDetectPort) {
    val detectedPort: Int? = findAvailablePort(parsedArguments.portNumber)

    checkNotNull(detectedPort) {
      "No available port in range ${parsedArguments.portNumber} - " +
        "${parsedArguments.portNumber + 10}; aborting server start"
    }

    logger.info("Using auto-detected port: $detectedPort")
    detectedPort
  } else
    parsedArguments.portNumber

  val resolvedApiKey: String? = parsedArguments.apiKey
    ?.trim()?.takeIf { it.isNotEmpty() } ?: ServerConfiguration.resolveDefaultApiKeyFromEnv()
  if (resolvedApiKey != null) {
    val keyPreview: String = resolvedApiKey.take(6) + "XXX" + resolvedApiKey.takeLast(4)
    logger.info("Hosted providers will use API key $keyPreview (source: ${apiKeySourceLabel(parsedArguments.apiKey)})")
  } else {
    logger.info(
      "No hosted-provider API key resolved; Zhipu BigModel / DeepSeek / MiniMax probes " +
        "will be skipped at startup"
    )
  }

  val serverConfiguration = ServerConfiguration(
    hostAddress = parsedArguments.hostAddress,
    portNumber = resolvedPort,
    defaultApiKey = resolvedApiKey,
  )

  val server: GradumServer = createServerInstance(serverConfiguration)

  Runtime.getRuntime().addShutdownHook(Thread {
    server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
    logger.info("Gradum Server has been shut down successfully")
  })

  server.start(wait = true)
}

private fun apiKeySourceLabel(argumentValue: String?): String =
  if (argumentValue.isNullOrBlank()) "environment" else "--api-key argument"

private data class ServerArguments(
  val hostAddress: String,
  val portNumber: Int,
  val autoDetectPort: Boolean,
  val apiKey: String? = null,
)

private fun parseArguments(arguments: Array<String>): ServerArguments {
  var hostAddress = ServerConfiguration.DEFAULT_HOST_ADDRESS
  var portNumber = ServerConfiguration.DEFAULT_PORT_NUMBER
  var autoDetectPort = false
  var apiKey: String? = null

  val iterator: Iterator<String> = arguments.iterator()
  while (iterator.hasNext()) {
    when (iterator.next()) {
      "--host" -> hostAddress = iterator.next()
      "--port" -> portNumber = iterator.next().toIntOrNull() ?: portNumber
      "--auto-port" -> autoDetectPort = true
      "--api-key" -> apiKey = iterator.next()
      "--help" -> {
        printUsage()
      }
    }
  }

  return ServerArguments(
    hostAddress = hostAddress,
    portNumber = portNumber,
    autoDetectPort = autoDetectPort,
    apiKey = apiKey,
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
        |  --api-key <key>        Bearer token for OpenAI-compatible providers
        |                         (falls back to GRADUM_OPENAI_API_KEY /
        |                         BIGMODEL_API_KEY / DEEPSEEK_API_KEY /
        |                         MiniMax_API_KEY / OPENAI_API_KEY env)
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
