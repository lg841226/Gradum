package gradum.server

import gradum.skill.PendingQuestions
import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.external.ExternalSkillClassLoader
import gradum.utils.JsonUtil
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("GradumServer")

// Liveness route that stays reachable without a token.
private const val HEALTH_PATH: String = "/health"

// Editor page route; it decides full access vs. reader mode (see Routes.kt).
private const val EDITOR_PAGE_PATH: String = "/skills/editor"

// The GET routes a token-less session may read so the editor can show code:
// the skill-source list and a single skill's source. Everything else: every
// POST included: keeps demanding the token, which is what makes that session
// read-only.
private val READER_PATHS: Set<String> = setOf("/skills/sources", "/skills/source")

typealias GradumServer = EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

fun createServerInstance(serverConfiguration: ServerConfiguration): GradumServer {
  val server: GradumServer = embeddedServer(
    factory = Netty,
    port = serverConfiguration.portNumber,
    host = serverConfiguration.hostAddress
  ) { module(serverConfiguration) }
  return server
}

/**
 * Ktor module that wires JSON content negotiation and registers all
 * application routes defined in [registerAllRoutes]. [pairingCode] fixes the
 * five-character unlock code (tests use it); a null default generates a fresh
 * one per start, printed below with the editor URLs. [pendingQuestions] is
 * injectable so a test can drive the ask channel.
 */
fun Application.module(
  serverConfiguration: ServerConfiguration,
  pairingCode: String? = null,
  pendingQuestions: PendingQuestions = PendingQuestions()
) {
  val pairingState = PairingState(pairingCode ?: PairingState.generatePairingCode())

  install(plugin = ContentNegotiation) {
    json(Json {
      prettyPrint = false
      isLenient = true
      ignoreUnknownKeys = true
    })
  }

  installRequestGuard(serverConfiguration)

  registerAllRoutes(
    pairingState = pairingState,
    serverConfiguration = serverConfiguration,
    pendingQuestions = pendingQuestions,
  )
  val skills = SkillRegistry.getAllSkills().sortedBy { it.skillName }
  if (skills.isNotEmpty()) {
    logger.info("Available skills: ${skills.count()}")
    skills.dropLast(n = 1).forEach { skill ->
      logger.info("\u251C\u2500\u2500 ${skillLabel(skill)}")
    }
    logger.info("\u2514\u2500\u2500 ${skillLabel(skills.last())}")
  }
  logger.info("Gradum Server starting on ${serverConfiguration.hostAddress}:${serverConfiguration.portNumber}")
  logger.info("Pairing code (unlock editing from other devices): ${pairingState.code}")
}

private fun Application.installRequestGuard(serverConfiguration: ServerConfiguration) {
  intercept(ApplicationCallPipeline.Plugins) {
    val requestHost: String =
      call.request.headers[HttpHeaders.Host] ?: call.request.host()
    if (!ServerAuth.isAllowedHostHeader(requestHost, bindHost = serverConfiguration.hostAddress)) {
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("error" to "forbidden host")),
        status = HttpStatusCode.Forbidden,
        contentType = ContentType.Application.Json,
      )
      return@intercept finish()
    }

    val authToken: String = serverConfiguration.authToken?.takeIf { it.isNotEmpty() }
      ?: return@intercept

    val requestPath: String = call.request.path()
    if (requestPath == HEALTH_PATH) return@intercept
    if (requestPath == ServerAuth.PAIR_PATH) return@intercept

    val isGet = call.request.httpMethod == HttpMethod.Get
    if (isGet) {
      if (requestPath == EDITOR_PAGE_PATH || requestPath.startsWith("$EDITOR_PAGE_PATH/")) {
        return@intercept
      }
      if (requestPath in READER_PATHS) return@intercept
    }

    val candidateToken: String? =
      bearerToken(call.request)
        ?: call.request.cookies[ServerAuth.TOKEN_COOKIE_NAME]
        ?: call.request.queryParameters[ServerAuth.TOKEN_QUERY_PARAM]

    if (!ServerAuth.tokenMatches(candidateToken, authToken)) {
      call.respondText(
        status = HttpStatusCode.Unauthorized,
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(mapOf("error" to "unauthorized"))
      )
      return@intercept finish()
    }
  }
}

private fun bearerToken(request: ApplicationRequest): String? {
  val headerValue: String = request.headers[HttpHeaders.Authorization] ?: return null
  if (!headerValue.startsWith(ServerAuth.BEARER_PREFIX)) return null
  return headerValue.removePrefix(ServerAuth.BEARER_PREFIX).trim().takeIf { it.isNotEmpty() }
}

private fun skillLabel(skill: Skill): String {
  val baseLabel = "${skill.alias}(${skill.skillName})"
  return if (skill.javaClass.classLoader is ExternalSkillClassLoader) "$baseLabel [external]" else baseLabel
}
