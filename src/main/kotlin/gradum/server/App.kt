/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * App.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.server

import gradum.skill.SkillRegistry
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
      logger.info("├── ${skill.alias}(${skill.skillName})")
    }
    logger.info("└── ${skills.last().alias}(${skills.last().skillName})")
  }
  logger.info("Gradum Server starting on ${serverConfiguration.hostAddress}:${serverConfiguration.portNumber}")
}
