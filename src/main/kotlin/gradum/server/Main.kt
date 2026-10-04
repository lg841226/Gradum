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

fun main(arguments: Array<String>) {
  if (arguments.firstOrNull() == "acp") startAcp()
  else startHttp(arguments)
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
    |Gradum HTTP Server
    |
    |Usage: java -jar gradum@<version>.jar [--help]
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

private const val BANNER_RESET = "\u001B[0m"

/** Start of the logo gradient, left edge (blue). */
private val BANNER_GRADIENT_FROM: IntArray = intArrayOf(59, 130, 246)

/** End of the logo gradient, right edge (violet). */
private val BANNER_GRADIENT_TO: IntArray = intArrayOf(168, 85, 247)

/** Block-glyph logo; [renderGradientBanner] paints the colors. */
private val BANNER_ART: List<String> = listOf(
  "   ██████  ██████    ██████  ██████   ██    ██ ██      ██",
  "  ██       ██   ██  ██    ██ ██   ██  ██    ██ ███    ███",
  "  ██       ██   ██  ██    ██ ██    ██ ██    ██ ██ ████ ██",
  "  ██   ███ ██████   ████████ ██    ██ ██    ██ ██  ██  ██",
  "  ██    ██ ██  ██   ██    ██ ██   ██  ██    ██ ██      ██",
  "   ██████  ██   ██  ██    ██ ██████    ██████  ██      ██"
)

/** Prints the startup logo to stdout; HTTP mode only, since ACP stays silent. */
private fun printStartupBanner() {
  val workDir: String = abbreviatePath(System.getProperty("user.dir") ?: "?")

  println()
  println()
  println(renderGradientBanner(BANNER_ART))
  println()
  println("  Copyright (c) 2026 Gradum Authors, software version ${BuildConfig.version}")
  println()
  println("  (Working Directory $workDir)")
  println()
}

/**
 * Paints [art] with a left-to-right truecolor gradient so the logo fades from
 * [BANNER_GRADIENT_FROM] to [BANNER_GRADIENT_TO] across the full width. Spaces
 * are left uncolored so only the glyphs carry the gradient.
 */
private fun renderGradientBanner(art: List<String>): String {
  val lastColumn: Int = (art.maxOf { line -> line.length } - 1).coerceAtLeast(1)

  return art.joinToString(separator = "\n") { line ->
    buildString {
      line.forEachIndexed { column, glyph ->
        if (glyph == ' ') append(' ')
        else append(gradientColor(column.toFloat() / lastColumn)).append(glyph)
      }
      append(BANNER_RESET)
    }
  }
}

/** 24-bit ANSI foreground for [ratio] in `0f..1f` between the two brand stops. */
private fun gradientColor(ratio: Float): String {
  val red: Int = interpolateChannel(BANNER_GRADIENT_FROM[0], BANNER_GRADIENT_TO[0], ratio)
  val green: Int = interpolateChannel(BANNER_GRADIENT_FROM[1], BANNER_GRADIENT_TO[1], ratio)
  val blue: Int = interpolateChannel(BANNER_GRADIENT_FROM[2], BANNER_GRADIENT_TO[2], ratio)
  return "\u001B[38;2;$red;$green;${blue}m"
}

private fun interpolateChannel(from: Int, to: Int, ratio: Float): Int =
  (from + (to - from) * ratio).toInt().coerceIn(0, 255)

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
