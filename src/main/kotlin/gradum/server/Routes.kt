/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Routes.kt  2026-08-10 23:11:18 Changed by gwy
 */

package gradum.server

import gradum.*
import gradum.Version
import gradum.agent.Agent
import gradum.discovery.ModelEntry
import gradum.discovery.RecommendationContext
import gradum.discovery.discoverModels
import gradum.discovery.recommend
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
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class EventsRequestBody(
  val message: String,
  val loadContext: Boolean = true,
  val model: String? = null,
  val toolMode: String? = null,
  val promptVariant: String? = null,
  val config: Map<String, String>? = null,
  /**
   * Optional tool-call scenario XML (`<tls>` format) that bypasses the LLM
   * entirely: the server executes the described tool calls through the same
   * real `executeSingleTool` pipeline and records each result. Used by the
   * debug tool-call playback mode — the developer authors the scenario
   * instead of spending LLM tokens.
   */
  val toolCallXml: String? = null,
  /**
   * Absolute path to the project the IDE has open. The server uses this
   * as the root for every file/process tool in this session. The plugin
   * is the only place that knows which project is open, so it must
   * provide it; the server has no other source of truth.
   */
  val projectRoot: String? = null,
  /**
   * Image (and in the future, generic binary) attachments sent
   * alongside the message. Wire shape mirrors the plugin's
   * `GradumApiClient.ApiImageAttachment`; the discriminator is
   * `type` and currently only `image` is supported.
   *
   * Empty by default — the common case is a text-only message.
   * Server-side `Agent.executeTask` projects the image entries
   * into a `content` array on the user message before pushing
   * it into the conversation history; the per-provider
   * `LLMClient` then re-shapes that array into either
   * Ollama's `images: [...]` field or OpenAI's
   * `image_url: {url: "data:..."}`.
   */
  val attachments: List<AttachmentDto> = emptyList(),
)

/**
 * A single element of [EventsRequestBody.attachments]. The `type`
 * field is the discriminator; only `image` is implemented today
 * but the structure is intentionally generic so PDF / audio /
 * future attachment kinds can be added without a wire-format
 * break.
 */
@Serializable
data class AttachmentDto(
  val type: String,
  /**
   * The wire-mime, e.g. `image/jpeg`. The plugin pipeline
   * normalizes every uploaded image to JPEG, so this is
   * expected to be `image/jpeg` for the foreseeable future
   * — kept explicit so a re-introduction of raw PNG
   * passthrough can be negotiated in a follow-up.
   */
  val mime: String? = null,
  /**
   * Base64-encoded payload. The plugin re-encodes images to
   * JPEG at quality 0.85 before serializing, so the on-wire
   * byte count is bounded (a typical 4 MB screenshot
   * becomes ~500 KB base64).
   */
  val data: String? = null,
  /**
   * Original file name as the user picked it. Used by the
   * LLM prompt as a hint (`"image filename.png attached"`)
   * and by the persisted conversation history for human
   * readability — not part of the multimodal payload.
   */
  val filename: String? = null,
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
 * - `POST /stop`   — aborts an active agent session.
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

      // Plugin provides the IDE project root via `projectRoot` in every request.
      // Reject missing or invalid paths with 400 — no fallback to server CWD,
      // which previously caused tools to target Gradum itself instead of the
      // user's project.
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
      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
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
      val resolvedToolMode: ToolMode = if (requestBody.toolCallXml != null) {
        // Debug tool-call playback always runs in Full Agent mode so every
        // skill is reachable; the scenario author chooses the tool list.
        ToolMode.AGENT
      } else {
        requestBody.toolMode?.let { ToolMode.fromStringOrDefault(it) } ?: ToolMode.AGENT
      }
      val agentConfiguration = AgentConfiguration(
        provider = resolvedProvider,
        modelName = requestBody.model
          ?: discoverModels().firstOrNull { it.available }?.modelName
          ?: "",
        baseUrl = configOverrides.baseUrl ?: "http://localhost:11434",
        toolMode = resolvedToolMode,
        promptVariant = PromptVariant.fromStringOrDefault(requestBody.promptVariant),
        enableThinking = configOverrides.think ?: false,
        topPValue = configOverrides.topP ?: 0.9,
        temperatureValue = configOverrides.temperature ?: 0.7,
        timeoutSeconds = configOverrides.timeout ?: 3000,
        // Tool mode is a client decision, not inferred from provider.
        // Defaults to AGENT (all tools) when client doesn't specify.
        maxTokensToGenerate = configOverrides.numPredict ?: 24576,
        contextWindowSize = configOverrides.numCtx ?: AgentConfiguration.DEFAULT_CONTEXT_WINDOW_SIZE,
        // The plugin owns project selection; the server is just a per-session executor. We resolved + validated above so
        // AgentConfiguration can require a non-null String.
        projectRoot = projectRootPath.toString(),
      )

      launch(Dispatchers.IO) {
        try {
          val agentInstance = Agent(
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

          activeSessions[sessionId] = agentInstance
          agentInstance.executeTask(
            userInput = requestBody.message,
            loadPreviousContext = requestBody.loadContext,
            toolCallXml = requestBody.toolCallXml,
            attachments = requestBody.attachments.map { attachment ->
              gradum.agent.AttachmentPayload(
                type = attachment.type,
                mime = attachment.mime,
                data = attachment.data.orEmpty(),
                filename = attachment.filename,
              )
            },
          )
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
      val requestBody = call.receive<StopRequestBody>()
      val sessionId = requestBody.sessionId
      val targetAgent = activeSessions.remove(sessionId)

      if (targetAgent != null) {
        targetAgent.abort()
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("status" to "stopped", "sessionId" to sessionId)),
          contentType = ContentType.Application.Json
        )
      } else {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionId)),
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
      // Re-snapshot free memory on every request so a freshly
      // opened IDE / browser does not push the local ranking
      // past a user's available headroom. Per-request cost is
      // one getFreeMemorySize() syscall — negligible.
      val recommendationContext: RecommendationContext = RecommendationContext.fromSystemMemory()
      val recommended: ModelEntry? = recommend(discoveredModels, recommendationContext)
      application.log.info(
        "Discovered ${discoveredModels.size} models; available RAM headroom " +
          "= ${"%.1f".format(recommendationContext.availableRamGB)} GB; " +
          "recommended = ${recommended?.modelName ?: "<none>"}"
      )
      val modelToJson: (ModelEntry) -> Map<String, Any?> = { entry: ModelEntry ->
        // `unavailableReason` is null when healthy, non-null when unavailable.
        // Plugin decodes it as `UnavailableReason?` enum; an empty string would
        // fail serialization and break the entire `/models` response.
        mapOf(
          "name" to entry.modelName,
          "provider" to entry.providerType,
          "server" to entry.serverUrl,
          "serverName" to entry.serverName,
          "contextLimit" to entry.contextLimit,
          "reasoning" to entry.reasoning,
          "toolCall" to entry.toolCall,
          "openWeights" to entry.openWeights,
          "attachment" to entry.attachment,
          "available" to entry.available,
          "unavailableReason" to entry.unavailableReason?.name
        )
      }
      call.respondText(
        text = JsonUtil.encodeMap(
          mapOf(
            "models" to discoveredModels.map(modelToJson),
            "recommended" to recommended?.let(modelToJson)
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
