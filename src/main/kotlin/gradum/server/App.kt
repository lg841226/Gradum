package gradum.server

import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.external.ExternalSkillClassLoader
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("GradumServer")

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
 * Startup log label for one skill: alias(skillName), suffixed with
 * [external] when the skill class was loaded from ~/.gradum/skills/ by
 * [ExternalSkillClassLoader]. Built-in and MCP skills come from the app
 * classloader, so they stay untagged. Derived from the live classloader
 * instead of a registry snapshot, so hot-reloaded skills keep the tag.
 */
private fun skillLabel(skill: Skill): String {
  val baseLabel = "${skill.alias}(${skill.skillName})"
  return if (skill.javaClass.classLoader is ExternalSkillClassLoader) "$baseLabel [external]" else baseLabel
}
