/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Main.kt  2026-09-26 00:04:31 Changed by gwy
 */

package gradum.server

import gradum.BuildConfig
import gradum.mcp.McpConnectionManager
import gradum.skill.SkillRegistry
import gradum.utils.CommandFilterRuntime
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.BindException

private val logger: Logger = LoggerFactory.getLogger("Main")

fun main(arguments: Array<String>) {
  printStartupBanner()
  if (arguments.any { it == "--help" || it == "-h" }) {
    printUsage()
    return
  }

  // Ensure a user-editable settings file + its autocompletion schema exist.
  ServerSettingsStore.releaseDefaultsIfMissing()
  val settings: ServerSettings = ServerSettingsStore.load()

  CommandFilterRuntime.config = settings.commandFilter

  // Connect any configured MCP servers and register their tools as skills
  // before the HTTP server starts, so tools are available from the first call.
  val mcpManager = McpConnectionManager()
  if (settings.mcpServers.isNotEmpty()) {
    runBlocking {
      val adapters = mcpManager.connect(settings.mcpServers)
      adapters.forEach { adapter -> SkillRegistry.register(adapter) }
      logger.info("Registered {} MCP tool(s)", adapters.size)
    }
  }

  val resolvedApiKey: String? =
    ServerSettingsStore.resolveApiKeyFromFile(settings.apiKeyFile)
      ?: ServerConfiguration.resolveDefaultApiKeyFromEnv()

  val resolvedPort: Int = if (settings.autoDetectPort) {
    val detectedPort: Int? = findAvailablePort(startPort = settings.port)

    checkNotNull(value = detectedPort) {
      "No available port in range ${settings.port} ~ ${settings.port + 10}; aborting server start"
    }

    logger.info("Using auto-detected port: $detectedPort")
    detectedPort
  } else
    settings.port

  // Publish resolved port to logging converter
  System.setProperty("gradum.server.port", resolvedPort.toString())

  if (resolvedApiKey != null) {
    val keyPreview: String = resolvedApiKey.take(n = 4) + "···" + resolvedApiKey.takeLast(n = 4)
    logger.info(
      "Hosted providers will use API key $keyPreview (src: ${apiKeySourceLabel(settings)})"
    )
  } else {
    logger.info("No hosted-provider API key; Zhipu / DeepSeek / MiniMax probes skipped")
  }

  val serverConfiguration = ServerConfiguration(
    portNumber = resolvedPort,
    hostAddress = settings.host,
    defaultApiKey = resolvedApiKey,
    defaultBaseUrl = settings.defaultBaseUrl,
    defaultKeepAliveMinutes = settings.defaultKeepAliveMinutes,
    defaultModelName = settings.defaultModelName,
    defaultThinkEnabled = settings.defaultThinkEnabled
  )

  val server: GradumServer = createServerInstance(serverConfiguration)

  Runtime.getRuntime().addShutdownHook(Thread {
    mcpManager.close()
    server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
    logger.info("Gradum Server has been shut down successfully")
  })

  try {
    server.start(wait = true)
  } catch (bindException: BindException) {
    logger.error(
      "Port $resolvedPort on ${settings.host} is already in use (${bindException.message}). " +
        "Stop that process or set another port / autoDetectPort in ~/.gradum/settings.json, then restart."
    )
  }
}

private fun apiKeySourceLabel(settings: ServerSettings): String =
  if (settings.apiKeyFile != null) "settings.json (apiKeyFile)" else "environment"

private fun printUsage() {
  println(
    """
        |Gradum HTTP Server
        |
        |Usage: java -jar gradum@<version>.jar [--help]
        |
        |All startup parameters are read from ~/.gradum/settings.json
        |(single source of truth — the old --host/--port/--auto-port/--api-key
        |flags have been removed). On first start the server writes a default
        |settings.json and settings.schema.json (editor autocompletion) there.
        |
        |Overridable settings:
        |  server.host          Host to bind (default: ${ServerConfiguration.DEFAULT_HOST_ADDRESS})
        |  server.port          Port to bind (default: ${ServerConfiguration.DEFAULT_PORT_NUMBER})
        |  server.autoDetectPort  Auto-find an available port
        |  server.apiKeyFile    Path to a file holding the bearer token for
        |                         OpenAI-compatible providers; its first non-blank
        |                         line is used (falls back to GRADUM_OPENAI_API_KEY /
        |                         BIGMODEL_API_KEY / DEEPSEEK_API_KEY /
        |                         MiniMax_API_KEY / OPENAI_API_KEY env)
        |  llm.baseUrl          Default provider base URL (default: ${gradum.AgentConfiguration.DEFAULT_OLLAMA_BASE_URL})
        |  llm.model            Default model name (empty = first available)
        |  llm.think            Enable thinking mode by default (default: off)
        |  --help               Show this help message
        |
        |HTTP Endpoints:
        |  POST /events          Execute agent, returns NDJSON stream
        |  POST /stop            Stop current agent task
        |  POST /session/delete  Delete session
        |  POST /session/rewind  Rewind session context to before a message id
        |  POST /provider/probe  Probe provider connectivity
        |  GET  /health          Liveness probe
        |  GET  /models          List available models
        |  GET  /skills          List registered skills
        |
        |Note: projectRoot is sent by the plugin on every /events request.
        |      It is not a server-side configuration.
    """.trimMargin()
  )
}

private const val BANNER_BLUE = "\u001B[1;34m"
private const val BANNER_RESET = "\u001B[0m"

private fun printStartupBanner() {
  val workDir: String = abbreviatePath(System.getProperty("user.dir") ?: "?")

  println(
    """
      |
      |
      |$BANNER_BLUE   ██████╗ ██████╗  █████╗ ██████╗ ██╗   ██╗███╗   ███╗$BANNER_RESET
      |$BANNER_BLUE  ██╔════╝ ██╔══██╗██╔══██╗██╔══██╗██║   ██║████╗ ████║$BANNER_RESET
      |$BANNER_BLUE  ██║  ███╗██████╔╝███████║██║  ██║██║   ██║██╔████╔██║$BANNER_RESET
      |$BANNER_BLUE  ██║   ██║██╔══██╗██╔══██║██║  ██║██║   ██║██║╚██╔╝██║$BANNER_RESET
      |$BANNER_BLUE  ╚██████╔╝██║  ██║██║  ██║██████╔╝╚██████╔╝██║ ╚═╝ ██║$BANNER_RESET
      |$BANNER_BLUE   ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝  ╚═════╝ ╚═╝     ╚═╝$BANNER_RESET
      |
      |  Copyright (c) 2026 Gradum Authors, software version ${BuildConfig.version}
      |
      |  (Working Directory $workDir)
      |
    """.trimMargin()
  )
}

private fun abbreviatePath(path: String): String {
  val homeDirectory: String = System.getProperty("user.home") ?: return path
  if (!path.startsWith(prefix = homeDirectory)) return path

  val relativePath: String = path.removePrefix(homeDirectory).trimStart('/')
  val pathSegments = relativePath.split("/")

  return if (pathSegments.size >= 2) "~/${pathSegments.takeLast(n = 2).joinToString(separator = "/")}"
  else "~/$relativePath"
}
