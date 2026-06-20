package gradum.server

import gradum.AgentConfiguration
import gradum.GRADUM_VERSION
import gradum.agent.Agent
import gradum.discovery.ModelEntry
import gradum.discovery.discoverModels
import gradum.skill.SkillRegistry
import gradum.util.JsonUtil
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EventsRequestBody(
    val message: String,
    val model: String? = null,
    val config: Map<String, String>? = null,
)

/**
 * Registers every HTTP route the Gradum server exposes:
 * - `POST /events` — streams an agent run as NDJSON.
 * - `GET /health`  — liveness probe.
 * - `GET /models`  — discovered LLM models.
 * - `GET /skills`  — registered Skill implementations.
 */
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
                    val agent = Agent(
                        configuration = agentConfiguration,
                        emitEvent = { eventType: String, data: Map<String, Any> ->
                            val eventMap = mapOf(
                                "type" to eventType,
                                "timestamp" to Instant.now().toString(),
                                "data" to data,
                            )
                            val ndjsonLine: String = JsonUtil.encodeMap(eventMap) + "\n"
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

            call.respondText(
                text = JsonUtil.encodeMap(mapOf(
                    "status" to "healthy",
                    "version" to GRADUM_VERSION,
                    "uptimeSeconds" to uptimeSeconds,
                    "timestamp" to Instant.now().toString(),
                )),
                contentType = ContentType.Application.Json,
            )
        }

        get("/models") {
            val discoveredModels: List<ModelEntry> = discoverModels()
            call.respondText(
                text = JsonUtil.encodeMap(mapOf(
                    "models" to discoveredModels.map { entry: ModelEntry ->
                        mapOf(
                            "name" to entry.modelName,
                            "provider" to entry.providerType,
                            "server" to entry.serverUrl,
                        )
                    },
                )),
                contentType = ContentType.Application.Json,
            )
        }

        get("/skills") {
            val skillRegistry: SkillRegistry = SkillRegistry()
            call.respondText(
                text = JsonUtil.encodeMap(mapOf(
                    "skills" to skillRegistry.getAllSkills().map { skill ->
                        mapOf(
                            "name" to skill.skillName,
                            "description" to skill.description,
                            "alias" to skill.alias,
                        )
                    },
                )),
                contentType = ContentType.Application.Json,
            )
        }
    }
}
