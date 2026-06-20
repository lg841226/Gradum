package gradum.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("GradumServer")

fun createServerInstance(serverConfiguration: ServerConfiguration): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val server = embeddedServer(Netty, host = serverConfiguration.hostAddress, port = serverConfiguration.portNumber) {
        module(serverConfiguration)
    }
    return server
}

fun Application.module(serverConfiguration: ServerConfiguration) {
    registerAllRoutes()
    logger.info("Gradum Server starting on ${serverConfiguration.hostAddress}:${serverConfiguration.portNumber}")
}
