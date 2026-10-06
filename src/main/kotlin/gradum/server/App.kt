package gradum.server

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

/** Liveness route that stays reachable without a token. */
private const val HEALTH_PATH: String = "/health"

/** Editor page route; it enforces its own credential check (see Routes.kt). */
private const val EDITOR_PAGE_PATH: String = "/skills/editor"

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
 * application routes defined in [registerAllRoutes].
 */
fun Application.module(serverConfiguration: ServerConfiguration) {
  install(plugin = ContentNegotiation) {
    json(Json {
      prettyPrint = false
      isLenient = true
      ignoreUnknownKeys = true
    })
  }

  installRequestGuard(serverConfiguration)

  registerAllRoutes(serverConfiguration)
  val skills = SkillRegistry.getAllSkills().sortedBy { it.skillName }
  if (skills.isNotEmpty()) {
    logger.info("Available skills: ${skills.count()}")
    skills.dropLast(n = 1).forEach { skill ->
      logger.info("\u251C\u2500\u2500 ${skillLabel(skill)}")
    }
    logger.info("\u2514\u2500\u2500 ${skillLabel(skills.last())}")
  }
  logger.info("Gradum Server starting on ${serverConfiguration.hostAddress}:${serverConfiguration.portNumber}")
}

/**
 * Installs the Host whitelist and bearer-token check.
 *
 * Runs in the `Plugins` phase, before routing, so an unauthorized call is
 * rejected before any handler body executes. Two routes bypass the token
 * check: `/health` (used by the build's readiness probe and monitoring)
 * and `GET /skills/editor` (the page enforces its own credential check so
 * it can set the auth cookie and return a human-readable hint; its static
 * assets remain guarded here).
 */
private fun Application.installRequestGuard(serverConfiguration: ServerConfiguration) {
  intercept(ApplicationCallPipeline.Plugins) {
    val requestHost: String =
      call.request.headers[HttpHeaders.Host] ?: call.request.host()
    if (!ServerAuth.isAllowedHostHeader(requestHost)) {
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
    if (requestPath == EDITOR_PAGE_PATH && call.request.httpMethod == HttpMethod.Get) return@intercept

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

/** Extracts the token from an `Authorization: Bearer <token>` header, or null. */
private fun bearerToken(request: ApplicationRequest): String? {
  val headerValue: String = request.headers[HttpHeaders.Authorization] ?: return null
  if (!headerValue.startsWith(ServerAuth.BEARER_PREFIX)) return null
  return headerValue.removePrefix(ServerAuth.BEARER_PREFIX).trim().takeIf { it.isNotEmpty() }
}

/**
 * Startup log label for one skill: alias(skillName), suffixed with
 * external when the skill class was loaded from ~/.gradum/skills/ by
 * [ExternalSkillClassLoader]. Built-in and MCP skills come from the app
 * classloader, so they stay untagged. Derived from the live classloader
 * instead of a registry snapshot, so hot-reloaded skills keep the tag.
 */
private fun skillLabel(skill: Skill): String {
  val baseLabel = "${skill.alias}(${skill.skillName})"
  return if (skill.javaClass.classLoader is ExternalSkillClassLoader) "$baseLabel [external]" else baseLabel
}
