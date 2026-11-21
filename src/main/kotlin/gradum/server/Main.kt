package gradum.server

import gradum.BuildConfig
import gradum.GradumRuntime
import gradum.acp.AcpServer
import gradum.agent.ExternalToolRegistry
import gradum.logging.EventStreamDump
import gradum.logging.routeAcpLogsToFile
import gradum.mcp.*
import gradum.mode.ModeDirectoryScanner
import gradum.mode.ModeDirectoryWatcher
import gradum.runtime.EventStreamMonitor
import gradum.server.setup.*
import gradum.skill.SkillRegistry
import gradum.skill.external.ExternalSkillDirectoryScanner
import gradum.skill.external.ExternalSkillDirectoryWatcher
import gradum.skill.external.ExternalSkillHost
import gradum.utils.CommandFilterRuntime
import gradum.workspace.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException

private val logger: Logger = LoggerFactory.getLogger("Main")

private const val ACP_ARGUMENT: String = "acp"

private const val SETUP_ARGUMENT: String = "setup"

fun main(arguments: Array<String>) {
  when {
    arguments.contains(SETUP_ARGUMENT) -> if (!runTuiSetup()) runSetup()
    arguments.firstOrNull() == ACP_ARGUMENT -> startAcp()
    else -> startHttp(arguments)
  }
}

private class StartupRuntime(
  val runtime: GradumRuntime,
  val mcpFailures: List<McpConnectionFailure>
)

private fun initRuntime(): StartupRuntime {
  ServerSettingsStore.releaseDefaultsIfMissing()
  val settings: ServerSettings = ServerSettingsStore.load()

  CommandFilterRuntime.config = settings.commandFilter
  EventStreamDump.configure(enabled = settings.debugEventDump)

  val mcpManager = McpConnectionManager()
  val mcpFailures: List<McpConnectionFailure> =
    if (settings.mcpServers.isNotEmpty()) {
      runBlocking { mcpManager.connect(McpServerConfig.configsFromRaw(settings.mcpServers)) }
    } else {
      emptyList()
    }
  ExternalToolRegistry.provider = McpToolCatalog

  SkillRegistry.register(McpToolsSkill())

  val skillScanner = ExternalSkillDirectoryScanner()
  val skillWatcher = ExternalSkillDirectoryWatcher(skillScanner = skillScanner).start()
  ExternalSkillHost.scanner = skillScanner

  skillScanner.scan()

  val modeScanner = ModeDirectoryScanner()
  modeScanner.scan()
  val modeWatcher = ModeDirectoryWatcher(modeScanner = modeScanner).start()

  val uiDirectory = skillScanner.skillsDirectory.parentFile.resolve(UI_ROOT)
  if (!uiDirectory.exists()) {
    uiDirectory.mkdirs()
  }
  WorkspaceHost.workspace = Workspace(
    listOf(
      WorkspaceRoot(SKILLS_ROOT, skillScanner.skillsDirectory),
      WorkspaceRoot(UI_ROOT, uiDirectory),
    )
  )

  val resolvedApiKey: String? =
    ServerSettingsStore.resolveApiKeyFromFile(settings.apiKeyFile)
      ?: ServerConfiguration.resolveDefaultApiKeyFromEnv()

  return StartupRuntime(
    runtime = GradumRuntime(
      settings = settings,
      mcpManager = mcpManager,
      skillScanner = skillScanner,
      skillWatcher = skillWatcher,
      modeScanner = modeScanner,
      modeWatcher = modeWatcher,
      resolvedApiKey = resolvedApiKey
    ),
    mcpFailures = mcpFailures
  )
}

class GradumServerHandle internal constructor(
  val port: Int,
  val token: String,
  val runtime: GradumRuntime,
  private val server: GradumServer
) {
  fun startBlocking() {
    server.start(wait = true)
  }

  fun startAsync() {
    server.start(wait = false)
  }

  fun shutdown() {
    server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
  }
}

fun bootServer(
  lineSink: (String) -> Unit = ::println,
  showBanner: Boolean = true,
  bannerColored: Boolean = true,
  uiRoutes: ((ServerConfiguration, Routing) -> Unit)? = null,
  uiPagePrefixes: List<String> = emptyList()
): GradumServerHandle? {
  val startup = initRuntime()
  val runtime = startup.runtime
  val settings = runtime.settings

  val exposedToNetwork: Boolean = !ServerAuth.isLoopbackBindHost(settings.host)
  if (exposedToNetwork && !settings.allowRemote) {
    logger.error(
      "server.host is '${settings.host}', which is not a loopback address, and server.allowRemote is off. " +
        "Set \"allowRemote\": true in ~/.gradum/settings.json to serve other machines on this network, " +
        "or keep server.host on localhost, 127.0.0.1, or ::1 to serve this machine only."
    )
    runtime.skillWatcher.close()
    runtime.modeWatcher.close()
    runtime.mcpManager.close()
    return null
  }
  if (exposedToNetwork) {
    logger.warn(
      "server.allowRemote is on: Gradum is reachable from other machines on this network. " +
        "Every request still requires the bearer token in ${ServerAuth.tokenFile().absolutePath}. " +
        "Set server.allowRemote to false to go back to serving this machine only."
    )
    try {
      InetAddress.getByName(settings.host)
    } catch (_: UnknownHostException) {
      logger.error(
        "server.host '${settings.host}' does not resolve to an address this machine can bind; " +
          "fix it in ~/.gradum/settings.json and restart."
      )
      runtime.skillWatcher.close()
      runtime.modeWatcher.close()
      runtime.mcpManager.close()
      return null
    }
  } else if (settings.allowRemote) {
    logger.info(
      "server.allowRemote is on, but server.host is '${settings.host}': still serving this machine only"
    )
  }

  val resolvedPort: Int =
    if (settings.autoDetectPort) {
      run {
        val detectedPort: Int? = findAvailablePort(startPort = settings.port)
        if (detectedPort == null) {
          logger.error(
            "No available port in range ${settings.port} ~ ${settings.port + 10}; aborting server start"
          )
          runtime.skillWatcher.close()
          runtime.modeWatcher.close()
          runtime.mcpManager.close()
          return null
        }
        logger.info("Using auto-detected port: $detectedPort")
        detectedPort
      }
    } else {
      settings.port
    }

  System.setProperty("gradum.server.port", resolvedPort.toString())

  if (showBanner) {
    printStartupBanner(
      hostAddress = settings.host,
      portNumber = resolvedPort,
      mcpToolCount = McpToolCatalog.toolCount(),
      skillCount = SkillRegistry.getAllSkills().size,
      mcpFailureCount = startup.mcpFailures.size,
      lineSink = lineSink,
      colored = bannerColored
    )
  }
  runtime.mcpManager.reportFailures(startup.mcpFailures)

  if (runtime.resolvedApiKey != null) {
    val keyPreview: String = runtime.resolvedApiKey.take(n = 4) +
      "\u00B7\u00B7\u00B7" + runtime.resolvedApiKey.takeLast(n = 4)
    logger.info(
      "Hosted providers will use API key $keyPreview (src: ${apiKeySourceLabel(settings)})"
    )
  } else {
    logger.info("No hosted-provider API key; Zhipu / DeepSeek / MiniMax probes skipped")
  }

  val authToken: String = ServerAuth.resolveToken()

  val serverConfiguration = ServerConfiguration(
    authToken = authToken,
    portNumber = resolvedPort,
    plugins = settings.plugins,
    hostAddress = settings.host,
    defaultApiKey = runtime.resolvedApiKey,
    defaultBaseUrl = settings.defaultBaseUrl,
    defaultModelName = settings.defaultModelName,
    defaultThinkEnabled = settings.defaultThinkEnabled,
    defaultKeepAliveMinutes = settings.defaultKeepAliveMinutes,
    uiRoutes = uiRoutes,
    uiPagePrefixes = uiPagePrefixes
  )

  val server: GradumServer = createServerInstance(serverConfiguration)

  if (uiRoutes != null) {
    val legatusPath = "/legatus?${ServerAuth.TOKEN_QUERY_PARAM}=$authToken"
    val lanAddresses: List<String> = ServerAuth.localAddresses()
    val primaryLan: String? = preferredLanAddress(lanAddresses)

    logger.info("Gradum Legatus (this machine): http://localhost:$resolvedPort$legatusPath")

    if (primaryLan != null) {
      if (!exposedToNetwork) {
        logger.info(
          "The LAN links below answer remote clients once \"allowRemote\": true is set in ~/.gradum/settings.json"
        )
      }
      val primaryHost: String = if (primaryLan.contains(':')) "[$primaryLan]" else primaryLan
      logger.info("Gradum Legatus (LAN): http://$primaryHost:$resolvedPort$legatusPath")
    }
  }

  Runtime.getRuntime().addShutdownHook(Thread {
    runtime.skillWatcher.close()
    runtime.modeWatcher.close()
    runtime.mcpManager.close()
    server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
    logger.info("Gradum Server has been shut down successfully")
  })

  return GradumServerHandle(resolvedPort, authToken, runtime, server)
}

private fun startHttp(arguments: Array<String>) {
  if (arguments.any { it == "--help" || it == "-h" }) {
    printUsage()
    return
  }

  val monitor: EventStreamMonitor? = EventStreamMonitor.openForServer()
  val lineSink: (String) -> Unit =
    if (monitor != null) {
      { text: String -> monitor.serverLogSink("I", text) }
    } else {
      ::println
    }

  val handle = bootServer(lineSink = lineSink, bannerColored = monitor == null) ?: return
  try {
    handle.startBlocking()
  } catch (bindException: BindException) {
    handle.runtime.skillWatcher.close()
    handle.runtime.modeWatcher.close()
    logger.error(
      "Port ${handle.port} on ${handle.runtime.settings.host} is already in use (${bindException.message}). " +
        "Stop that process or set another port / autoDetectPort in ~/.gradum/settings.json, then restart."
    )
  }
}

private fun startAcp() {
  val startup = initRuntime()
  val runtime = startup.runtime
  routeAcpLogsToFile()
  runtime.mcpManager.reportFailures(startup.mcpFailures)

  logger.info("Gradum ACP server is starting (stdio JSON-RPC)")
  logger.info("Gradum ACP server has been started, awaiting client frames on stdin")

  AcpServer(runtime).runBlocking()
  runtime.skillWatcher.close()
  runtime.modeWatcher.close()
  runtime.mcpManager.close()

  logger.info("Gradum ACP server has been shut down")
}

private fun apiKeySourceLabel(settings: ServerSettings): String =
  if (settings.apiKeyFile != null) "settings.json (apiKeyFile)"
  else "environment"

private fun printUsage() {
  println(
    """
    |Gradum Runtime
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
    |  GET  /modes            List configured agent modes
    |  GET  /skills           List registered skills
    |
    |Note: projectRoot is sent by the plugin on every /events request.
    |      It is not a server-side configuration.
    """.trimMargin()
  )
}

private fun printStartupBanner(
  portNumber: Int,
  skillCount: Int,
  mcpToolCount: Int,
  mcpFailureCount: Int,
  hostAddress: String,
  lineSink: (String) -> Unit,
  colored: Boolean
) {
  val cellWidth = 46
  val mcpSummary: String =
    if (mcpFailureCount == 0) "$mcpToolCount"
    else "$mcpToolCount ($mcpFailureCount failed)"

  val rows: List<String> = buildList {
    add(emphasize(rightCell(" Gradum v${BuildConfig.version}", cellWidth), colored, gradientColor(ratio = 0.1f)))
    add(rightCell("  MCP tools     $mcpSummary", cellWidth))
    add(rightCell("  Skills        $skillCount", cellWidth))
    add(rightCell("  Settings      ~/.gradum/settings.json", cellWidth))
    add(rightCell("  Server        http://$hostAddress:$portNumber", cellWidth))
    add(" ".repeat(cellWidth))
    add(emphasize(rightCell(" Getting started", cellWidth), colored, gradientColor(ratio = 0f)))
    add(rightCell("  Run gradum setup to configure a provider", cellWidth))
  }

  val topRow: String = "╭" + "─".repeat(cellWidth) + "╮"
  val bottomRow: String = "╰" + "─".repeat(cellWidth) + "╯"

  lineSink("")
  lineSink(paintGradientRow(topRow, colored))
  rows.forEachIndexed { index, row ->
    val bar: String = if (colored) {
      gradientColor(ratio = index.toFloat() / (rows.size - 1).coerceAtLeast(1)) + "│" + ANSI_RESET
    } else {
      "│"
    }
    lineSink(bar + row + bar)
  }
  lineSink(paintGradientRow(bottomRow, colored))
  lineSink("")
}

private fun rightCell(text: String, width: Int): String = text.take(width).padEnd(width)

private fun preferredLanAddress(addresses: List<String>): String? {
  val outbound: String? =
    try {
      DatagramSocket().use { socket: DatagramSocket ->
        socket.connect(InetAddress.getByName("8.8.8.8"), 53)
        socket.localAddress.hostAddress?.takeUnless { it.isEmpty() }
      }
    } catch (_: Exception) {
      null
    }?.takeIf { candidate -> candidate in addresses }
  if (outbound != null) return outbound

  val ipv4: List<String> = addresses.filter { !it.contains(':') }
  return ipv4.firstOrNull { it.startsWith("192.168.") && !it.endsWith(".1") }
    ?: ipv4.firstOrNull { it.startsWith("192.168.") }
    ?: ipv4.firstOrNull { it.startsWith("10.") }
    ?: ipv4.firstOrNull()
    ?: addresses.firstOrNull()
}

private fun emphasize(text: String, colored: Boolean, style: String): String =
  if (colored) ANSI_BOLD + style + text + ANSI_RESET else text

private fun paintGradientRow(text: String, colored: Boolean): String {
  if (!colored) return text
  return buildString {
    text.forEachIndexed { index, glyph ->
      append(gradientColor(ratio = index.toFloat() / (text.length - 1).coerceAtLeast(1))).append(glyph)
    }
    append(ANSI_RESET)
  }
}
