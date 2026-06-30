/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Routes.kt  2026-06-30 11:24:19 Changed by gwy
 */

package gradum.server

import gradum.*
import gradum.Version
import gradum.agent.Agent
import gradum.discovery.ModelEntry
import gradum.discovery.discoverModels
import gradum.server.ConfigOverrides.Companion.fromRequestMap
import gradum.skill.SkillRegistry
import gradum.utils.JsonUtil
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
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class EventsRequestBody(
    val message: String,
    val model: String? = null,
    val config: Map<String, String>? = null,
    val loadContext: Boolean = true,
    val toolMode: String? = null,
    val promptVariant: String? = null,
    /**
     * Absolute path to the project the IDE has open. The server uses this
     * as the root for every file/process tool in this session. The plugin
     * is the only place that knows which project is open, so it must
     * provide it; the server has no other source of truth.
     */
    val projectRoot: String? = null,
)

@Serializable
data class StopRequestBody(
    val sessionId: String
)

/**
 * Typed view over the `config` map sent by the HTTP client. Each field is
 * nullable so the route handler can supply a default via `?:` instead of
 * scattering `?.toBoolean()` / `?.toIntOrNull()` across the call site.
 *
 * Construct via [fromRequestMap] to centralize every string→typed conversion
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
    val timeout: Int? = null
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
                timeout = rawConfig["timeout"]?.toIntOrNull()
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
fun Application.registerAllRoutes() {
    val serverStartTime: LocalDateTime = LocalDateTime.now()
    val activeSessions: ConcurrentHashMap<String, Agent> = ConcurrentHashMap()

    routing {
        post("/events") {
            val requestBody: EventsRequestBody = call.receive<EventsRequestBody>()
            val sessionId: String = UUID.randomUUID().toString()

            // The plugin is the only authority on which project is open in
            // the IDE. Reject requests that omit projectRoot (or send a
            // path that does not point to an existing directory) with 400
            // — letting the server fall back to a guessed CWD is exactly
            // the bug that caused the "tools read Gradum instead of the
            // user's test project" misroute.
            val rawProjectRoot: String? = requestBody.projectRoot
            if (rawProjectRoot.isNullOrBlank()) {
                call.respondText(
                    text = JsonUtil.encodeMap(
                        mapOf("error" to "projectRoot is required (the IDE must send the open project's absolute path)")
                    ),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                )
                return@post
            }
            val projectRootPath: java.nio.file.Path =
                java.nio.file.Paths.get(rawProjectRoot).toAbsolutePath().normalize()
            val projectRootFile: java.io.File = projectRootPath.toFile()
            if (!projectRootFile.exists() || !projectRootFile.isDirectory) {
                call.respondText(
                    text = JsonUtil.encodeMap(
                        mapOf("error" to "projectRoot is not an existing directory: $projectRootPath")
                    ),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                )
                return@post
            }

            // UNLIMITED is safe: the only producer is the agent emitting NDJSON, and the
            // emit rate is bounded by LLM response size. Reconsider if user input ever flows
            // through this channel unfiltered.
            val eventsChannel: Channel<String> = Channel(capacity = Channel.UNLIMITED)

            val configOverrides: ConfigOverrides = fromRequestMap(requestBody.config)

            val resolvedProvider: Provider = Provider.fromStringOrDefault(configOverrides.provider)
            val agentConfiguration = AgentConfiguration(
                modelName = requestBody.model ?: "minimax-m2.5:cloud",
                baseUrl = configOverrides.baseUrl ?: "http://localhost:11434",
                provider = resolvedProvider,
                enableThinking = configOverrides.think ?: false,
                temperatureValue = configOverrides.temperature ?: 0.7,
                topPValue = configOverrides.topP ?: 0.9,
                contextWindowSize = configOverrides.numCtx ?: 8192,
                maxTokensToGenerate = configOverrides.numPredict ?: 24576,
                timeoutSeconds = configOverrides.timeout ?: 3000,
                // Tool surface is purely a client choice — Ollama runs both
                // 7B laptops and 70B cloud models, so we never infer it from
                // the provider. The default is WRITE (every tool exposed)
                // when the client does not override.
                toolMode = requestBody.toolMode?.let { ToolMode.fromStringOrDefault(it) } ?: ToolMode.WRITE,
                promptVariant = PromptVariant.fromStringOrDefault(requestBody.promptVariant),
                // The plugin owns project selection; the server is just a per-session executor. We resolved + validated above so
                // AgentConfiguration can require a non-null String.
                projectRoot = projectRootPath.toString(),
            )

            launch(Dispatchers.IO) {
                try {
                    val agent = Agent(
                        configuration = agentConfiguration,
                        emitEvent = { eventType: String, data: Map<String, Any> ->
                            val ndjsonLine: String = JsonUtil.encodeMap(
                                mapOf(
                                    "type" to eventType,
                                    "timestamp" to LocalDateTime.now().toString(),
                                    "sessionId" to sessionId,
                                    "data" to data
                                )
                            ) + "\n"
                            eventsChannel.trySend(ndjsonLine)
                        },
                    )

                    activeSessions[sessionId] = agent
                    agent.executeTask(requestBody.message, requestBody.loadContext)
                } finally {
                    activeSessions.remove(sessionId)
                    eventsChannel.close()
                }
            }

            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.parse("application/x-ndjson")

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    for (ndjsonLine: String in eventsChannel) {
                        channel.writeStringUtf8(ndjsonLine)
                        channel.flush()
                    }
                }
            })
        }

        post("/stop") {
            val requestBody: StopRequestBody = call.receive<StopRequestBody>()
            val agent: Agent? = activeSessions[requestBody.sessionId]

            if (agent != null) {
                agent.abort()
                activeSessions.remove(requestBody.sessionId)
                call.respondText(
                    text = JsonUtil.encodeMap(mapOf("status" to "stopped", "sessionId" to requestBody.sessionId)),
                    contentType = ContentType.Application.Json
                )
            } else {
                call.respondText(
                    text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to requestBody.sessionId)),
                    status = HttpStatusCode.NotFound,
                    contentType = ContentType.Application.Json
                )
            }
        }

        get("/health") {
            val uptimeSeconds: Long = Duration.between(serverStartTime, LocalDateTime.now()).seconds

            call.respondText(
                text = JsonUtil.encodeMap(
                    mapOf(
                        "status" to "healthy",
                        "version" to Version.GRADUM_VERSION,
                        "uptimeSeconds" to uptimeSeconds,
                        "timestamp" to LocalDateTime.now().toString()
                    )
                ),
                contentType = ContentType.Application.Json
            )
        }

        get("/models") {
            val discoveredModels: List<ModelEntry> = discoverModels()
            application.log.info("Discovered ${discoveredModels.size} models: ${discoveredModels.map { it.modelName }}")
            call.respondText(
                text = JsonUtil.encodeMap(
                    mapOf(
                        "models" to discoveredModels.map { entry: ModelEntry ->
                            mapOf(
                                "name" to entry.modelName,
                                "provider" to entry.providerType,
                                "server" to entry.serverUrl,
                                "serverName" to entry.serverName,
                                "contextLimit" to entry.contextLimit,
                                "reasoning" to entry.reasoning,
                                "toolCall" to entry.toolCall,
                                "openWeights" to entry.openWeights,
                                "attachment" to entry.attachment
                            )
                        },
                    )
                ),
                contentType = ContentType.Application.Json
            )
        }

        get("/skills") {
            val skillRegistry = SkillRegistry
            call.respondText(
                text = JsonUtil.encodeMap(
                    mapOf(
                        "skills" to skillRegistry.getAllSkills().map { skill ->
                            mapOf(
                                "name" to skill.skillName,
                                "description" to skill.description,
                                "alias" to skill.alias
                            )
                        },
                    )
                ),
                contentType = ContentType.Application.Json
            )
        }
    }
}
