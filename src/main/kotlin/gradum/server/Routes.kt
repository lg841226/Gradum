/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Routes.kt  2026-06-20 20:22:43 Created by gwy
 */

package gradum.server

import gradum.AgentConfiguration
import gradum.GRADUM_VERSION
import gradum.Provider
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EventsRequestBody(
    val message: String,
    val model: String? = null,
    val config: Map<String, String>? = null,
    val loadContext: Boolean = false,
)

/**
 * Typed view over the `config` map sent by the HTTP client. Each field is
 * nullable so the route handler can supply a default via `?:` instead of
 * scattering `?.toBoolean()` / `?.toIntOrNull()` across the call site.
 *
 * Construct via [fromRequestMap] to centralise every string→typed conversion
 * in one place.
 */
@Serializable
data class ConfigOverrides(
    val baseUrl: String? = null,
    val provider: String? = null,
    val think: Boolean? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val numCtx: Int? = null,
    val numPredict: Int? = null,
    val timeout: Int? = null,
) {
    companion object {
        /**
         * Convert a request's optional `config` map (raw strings from JSON)
         * into a typed [ConfigOverrides]. Returns an empty instance when
         * [rawConfig] is null so callers can chain `field ?: default` without
         * a null check.
         */
        fun fromRequestMap(rawConfig: Map<String, String>?): ConfigOverrides {
            if (rawConfig == null) return ConfigOverrides()
            return ConfigOverrides(
                baseUrl = rawConfig["baseUrl"],
                provider = rawConfig["provider"],
                think = rawConfig["think"]?.toBoolean(),
                temperature = rawConfig["temperature"]?.toDoubleOrNull(),
                topP = rawConfig["topP"]?.toDoubleOrNull(),
                numCtx = rawConfig["numCtx"]?.toIntOrNull(),
                numPredict = rawConfig["numPredict"]?.toIntOrNull(),
                timeout = rawConfig["timeout"]?.toIntOrNull(),
            )
        }
    }
}

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

            val eventsChannel: Channel<String> = Channel(capacity = Channel.UNLIMITED)

            val configOverrides: ConfigOverrides = ConfigOverrides.fromRequestMap(requestBody.config)

            val agentConfiguration = AgentConfiguration(
                modelName = requestBody.model ?: "minimax-m2.5:cloud",
                baseUrl = configOverrides.baseUrl ?: "http://localhost:11434",
                provider = Provider.fromStringOrDefault(configOverrides.provider),
                enableThinking = configOverrides.think ?: false,
                temperatureValue = configOverrides.temperature ?: 0.7,
                topPValue = configOverrides.topP ?: 0.9,
                contextWindowSize = configOverrides.numCtx ?: 4096,
                maxTokensToGenerate = configOverrides.numPredict ?: 24576,
                timeoutSeconds = configOverrides.timeout ?: 3000,
            )

            launch(Dispatchers.IO) {
                try {
                    val agent = Agent(
                        configuration = agentConfiguration,
                        emitEvent = { eventType: String, data: Map<String, Any> ->
                            val ndjsonLine: String = JsonUtil.encodeMap(mapOf("type" to eventType, "timestamp" to Instant.now().toString(), "data" to data)) + "\n"
                            eventsChannel.trySend(ndjsonLine)
                        },
                    )

                    agent.executeTask(requestBody.message, requestBody.loadContext)
                } finally {
                    eventsChannel.close()
                }
            }

            call.response.header(HttpHeaders.ContentType, "application/x-ndjson")

            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    for (ndjsonLine: String in eventsChannel) {
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
