package gradum.server

import gradum.GRADUM_VERSION
import gradum.agent.Agent
import gradum.AgentConfiguration
import gradum.discovery.discoverModels
import gradum.skill.SkillRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.Instant

private val jsonFormatter: Json = Json { prettyPrint = false }

@Serializable
data class EventsRequestBody(
    val message: String,
    val model: String? = null,
    val config: Map<String, String>? = null,
)

fun Application.registerAllRoutes(): Unit {
    val serverStartTime: Instant = Instant.now()

    routing {
        post("/events") {
            val requestBody: EventsRequestBody = call.receive<EventsRequestBody>()

            val eventsChannel: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 128)

            val agentConfiguration: AgentConfiguration = AgentConfiguration(
                modelName = requestBody.model ?: "minimax-m2.5:cloud",
                baseUrl = requestBody.config?.get("baseUrl") ?: "http://localhost:11434",
                providerName = requestBody.config?.get("provider") ?: "ollama",
                enableThinking = requestBody.config?.get("think")?.toBoolean() ?: false,
                temperatureValue = requestBody.config?.get("temperature")?.toDoubleOrNull() ?: 0.7,
                topPValue = requestBody.config?.get("topP")?.toDoubleOrNull() ?: 0.9,
                contextWindowSize = requestBody.config?.get("numCtx")?.toIntOrNull() ?: 4096,
                maxTokensToGenerate = requestBody.config?.get("numPredict")?.toIntOrNull() ?: 2048,
                timeoutSeconds = requestBody.config?.get("timeout")?.toIntOrNull() ?: 300,
            )

            launch(Dispatchers.IO) {
                try {
                    val agent: Agent = Agent(
                        configuration = agentConfiguration,
                        emitEvent = { eventType: String, data: Map<String, Any> ->
                            val eventMap = mapOf(
                                "type" to eventType,
                                "timestamp" to Instant.now().toString(),
                                "data" to data,
                            )
                            val ndjsonLine: String = jsonFormatter.encodeToString(serializer<Map<String, Any>>(), eventMap) + "\n"
                            eventsChannel.tryEmit(ndjsonLine)
                        },
                    )

                    agent.executeTask(requestBody.message)
                } finally {
                    eventsChannel.emit("\n")
                }
            }

            call.response.header(HttpHeaders.ContentType, "application/x-ndjson")

            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    eventsChannel
                        .takeWhile { line: String -> line != "\n" }
                        .collect { ndjsonLine: String ->
                            channel.writeStringUtf8(ndjsonLine)
                            channel.flush()
                        }
                }
            })
        }

        get("/health") {
            val uptimeSeconds: Long = java.time.Duration.between(serverStartTime, Instant.now()).seconds

            call.respond(mapOf(
                "status" to "healthy",
                "version" to GRADUM_VERSION,
                "uptimeSeconds" to uptimeSeconds,
                "timestamp" to Instant.now().toString(),
            ))
        }

        get("/models") {
            val discoveredModels = discoverModels()
            call.respond(mapOf(
                "models" to discoveredModels.map { entry ->
                    mapOf(
                        "name" to entry.modelName,
                        "provider" to entry.providerType,
                        "server" to entry.serverUrl,
                    )
                },
            ))
        }

        get("/skills") {
            val skillRegistry: SkillRegistry = SkillRegistry()
            call.respond(mapOf(
                "skills" to skillRegistry.getAllSkills().map { skill ->
                    mapOf(
                        "name" to skill.skillName,
                        "description" to skill.description,
                        "alias" to skill.alias,
                    )
                },
            ))
        }
    }
}
