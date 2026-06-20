package gradum.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger: org.slf4j.Logger = LoggerFactory.getLogger("GradumServer")

typealias GradumServer = EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

fun createServerInstance(serverConfiguration: ServerConfiguration): GradumServer {
    val server: GradumServer = embeddedServer(
        Netty,
        host = serverConfiguration.hostAddress,
        port = serverConfiguration.portNumber,
    ) {
        module(serverConfiguration)
    }
    return server
}

/**
 * Ktor module that wires JSON content negotiation and registers all
 * application routes defined in [registerAllRoutes].
 */
fun Application.module(serverConfiguration: ServerConfiguration) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    registerAllRoutes()
    logger.info("Gradum Server starting on ${serverConfiguration.hostAddress}:${serverConfiguration.portNumber}")
}
