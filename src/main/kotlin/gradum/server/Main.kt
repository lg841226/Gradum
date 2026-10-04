package gradum.server

import gradum.BuildConfig
import gradum.GradumRuntime
import gradum.acp.AcpServer
import gradum.logging.routeAcpLogsToFile
import gradum.mcp.McpConnectionManager
import gradum.mcp.McpToolCatalog
import gradum.mcp.McpToolsSkill
import gradum.skill.SkillRegistry
import gradum.skill.external.ExternalSkillDirectoryScanner
import gradum.skill.external.ExternalSkillDirectoryWatcher
import gradum.utils.CommandFilterRuntime
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.BindException

private val logger: Logger = LoggerFactory.getLogger("Main")

/** First CLI argument that switches the entry point to the ACP stdio server. */
private const val ACP_ARGUMENT: String = "acp"

/** First CLI argument that launches the interactive provider setup wizard. */
private const val SETUP_ARGUMENT: String = "setup"

fun main(arguments: Array<String>) {
  when {
    arguments.contains(SETUP_ARGUMENT) -> if (!runTuiSetup()) runSetup()
    arguments.firstOrNull() == ACP_ARGUMENT -> startAcp()
    else -> startHttp(arguments)
  }
}

/**
 * Shared startup block: loads settings, initializes MCP manager, scans
 * external skills, starts the directory watcher, and resolves the API key.
 * HTTP and ACP entry points both call this so the long-lived runtime
 * components are constructed exactly once per process.
 */
private fun initRuntime(): GradumRuntime {
  ServerSettingsStore.releaseDefaultsIfMissing()
  val settings: ServerSettings = ServerSettingsStore.load()

  CommandFilterRuntime.config = settings.commandFilter

  val mcpManager = McpConnectionManager()
  if (settings.mcpServers.isNotEmpty()) {
    runBlocking {
      mcpManager.connect(settings.mcpServers)
    }
  }

  SkillRegistry.register(McpToolsSkill())

  val skillScanner = ExternalSkillDirectoryScanner()
  val skillWatcher = ExternalSkillDirectoryWatcher(skillScanner = skillScanner).start()

  skillScanner.scan()
  logger.info("Registered {} MCP tool(s) in catalog", McpToolCatalog.toolCount())

  val resolvedApiKey: String? =
    ServerSettingsStore.resolveApiKeyFromFile(settings.apiKeyFile)
      ?: ServerConfiguration.resolveDefaultApiKeyFromEnv()

  return GradumRuntime(
    settings = settings,
    mcpManager = mcpManager,
    skillScanner = skillScanner,
    skillWatcher = skillWatcher,
    resolvedApiKey = resolvedApiKey
  )
}

private fun startHttp(arguments: Array<String>) {
  printStartupBanner()

  if (arguments.any { it == "--help" || it == "-h" }) {
    printUsage()
    return
  }

  val runtime = initRuntime()
  val settings = runtime.settings

  val resolvedPort: Int =
    if (settings.autoDetectPort) {
      run {
        val detectedPort = findAvailablePort(startPort = settings.port)
        checkNotNull(detectedPort) {
          "No available port in range ${settings.port} ~ ${settings.port + 10}; aborting server start"
        }
        logger.info("Using auto-detected port: $detectedPort")
        detectedPort
      }
    } else {
      settings.port
    }

  System.setProperty("gradum.server.port", resolvedPort.toString())

  if (runtime.resolvedApiKey != null) {
    val keyPreview: String = runtime.resolvedApiKey.take(n = 4) +
      "\u00B7\u00B7\u00B7" + runtime.resolvedApiKey.takeLast(n = 4)
    logger.info(
      "Hosted providers will use API key $keyPreview (src: ${apiKeySourceLabel(settings)})"
    )
  } else {
    logger.info("No hosted-provider API key; Zhipu / DeepSeek / MiniMax probes skipped")
  }

  val serverConfiguration = ServerConfiguration(
    portNumber = resolvedPort,
    plugins = settings.plugins,
    hostAddress = settings.host,
    defaultApiKey = runtime.resolvedApiKey,
    defaultBaseUrl = settings.defaultBaseUrl,
    defaultModelName = settings.defaultModelName,
    defaultThinkEnabled = settings.defaultThinkEnabled,
    defaultKeepAliveMinutes = settings.defaultKeepAliveMinutes
  )

  val server: GradumServer = createServerInstance(serverConfiguration)

  Runtime.getRuntime().addShutdownHook(Thread {
    runtime.skillWatcher.close()
    runtime.mcpManager.close()
    server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
    logger.info("Gradum Server has been shut down successfully")
  })

  try {
    server.start(wait = true)
  } catch (bindException: BindException) {
    runtime.skillWatcher.close()
    logger.error(
      "Port $resolvedPort on ${settings.host} is already in use (${bindException.message}). " +
        "Stop that process or set another port / autoDetectPort in ~/.gradum/settings.json, then restart."
    )
  }
}

private fun startAcp() {
  routeAcpLogsToFile()
  logger.info("Gradum ACP server is starting (stdio JSON-RPC)")
  val runtime = initRuntime()
  logger.info("Gradum ACP server has been started, awaiting client frames on stdin")
  AcpServer(runtime).runBlocking()
  runtime.skillWatcher.close()
  runtime.mcpManager.close()
  logger.info("Gradum ACP server has been shut down")
}

private fun apiKeySourceLabel(settings: ServerSettings): String =
  if (settings.apiKeyFile != null) "settings.json (apiKeyFile)"
  else "environment"

private fun printUsage() {
  println(
    """
    |Gradum
    |
    |Usage: gradum [command] [--help]
    |
    |Commands:
    |  (none)   Start the HTTP server
    |  acp      Start the ACP stdio server
    |  setup    Interactive provider configuration
    |
    |All startup parameters are read from ~/.gradum/settings.json
    |(single source of truth; the old --host/--port/--auto-port/--api-key
    |flags have been removed). On first start the server writes a default
    |settings.json and settings.schema.json (editor autocompletion) there.
    |
    |Common settings:
    |  server.host / server.port / server.autoDetectPort
    |      HTTP bind (default: ${ServerConfiguration.DEFAULT_HOST_ADDRESS}:${ServerConfiguration.DEFAULT_PORT_NUMBER})
    |  server.apiKeyFile
    |      Bearer-token file for OpenAI-compatible providers (first
    |      non-blank line wins; env fallback: OPENAI_API_KEY,
    |      MiniMax_API_KEY, BIGMODEL_API_KEY, DEEPSEEK_API_KEY,
    |      GRADUM_OPENAI_API_KEY)
    |  llm.baseUrl / llm.model / llm.think / llm.keepAliveMinutes
    |      Provider defaults (llm.baseUrl default: ${gradum.AgentConfiguration.DEFAULT_OLLAMA_BASE_URL})
    |
    |HTTP Endpoints:
    |  POST /events           Execute agent, returns NDJSON stream
    |  POST /events/respond   Answer an open ask card
    |  POST /stop             Stop current agent task
    |  POST /session/delete   Delete session
    |  POST /session/rewind   Rewind session context to before a message id
    |  POST /provider/probe   Probe provider connectivity
    |  GET  /health           Liveness probe
    |  GET  /models           List available models
    |  GET  /skills           List registered skills
    |
    |Note: projectRoot is sent by the plugin on every /events request.
    |      It is not a server-side configuration.
    """.trimMargin()
  )
}

/** Prints the startup logo to stdout; HTTP mode only, since ACP stays silent. */
private fun printStartupBanner() {
  val workDir: String = abbreviatePath(System.getProperty("user.dir") ?: "?")

  println()
  println()
  println(renderGradientBanner(BANNER_ART))
  println()
  println("  Copyright (c) 2026 Gradum Authors, version ${BuildConfig.version}")
  println()
  println("  (Working Directory $workDir)")
  println()
}

private fun abbreviatePath(path: String): String {
  val homeDirectory: String = System.getProperty("user.home") ?: return path
  if (!path.startsWith(prefix = homeDirectory)) return path

  val relativeSegments: List<String> = path.removePrefix(homeDirectory)
    .split('/', '\\')
    .filter { it.isNotEmpty() }

  return when {
    relativeSegments.isEmpty() -> "~"
    relativeSegments.size == 1 -> "~/${relativeSegments.first()}"
    else -> "~/${relativeSegments.takeLast(n = 2).joinToString(separator = "/")}"
  }
}
