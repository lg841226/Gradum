/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * Routes.kt  2026-08-13 11:34:17 Changed by gwy
 */

package gradum.server

import gradum.*
import gradum.Version
import gradum.agent.Agent
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
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ProviderProbeRequest(
  /** Provider kind: `ollama` or `lmstudio` (drives the probe endpoint). */
  val kind: String,
  /** Base URL of the provider, e.g. `http://localhost:11434`. */
  val baseUrl: String,
  /** Optional bearer token for protected providers. */
  val apiKey: String? = null,
)

@Serializable
data class EventsRequestBody(
  val message: String,
  /**
   * Stable conversation id supplied by the plugin on every request of the
   * same chat thread. The server scopes this session's persisted context
   * to `.gradum/sessions/<sessionId>/`; a blank value falls back to the
   * legacy `.gradum/context.json`. Optional so older plugin builds keep
   * working against this server.
   */
  val model: String? = null,
  val toolMode: String? = null,
  val sessionId: String? = null,
  val loadContext: Boolean = true,
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


@Serializable
data class DeleteSessionRequestBody(
  val projectRoot: String,
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
  val timeout: Int? = null,
  /**
   * Per-request bearer token. Overrides the server-wide
   * [ServerConfiguration.defaultApiKey] when non-blank, so a user
   * with multiple provider accounts can swap keys from the plugin UI
   * without restarting the server.
   */
  val apiKey: String? = null,
  /**
   * Path appended to `baseUrl` for chat completions. Default is
   * `/v1/chat/completions`; Zhipu BigModel uses `/chat/completions`
   * under its `api/coding/paas/v4` sub-domain.
   */
  val chatCompletionsPath: String? = null,
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
        apiKey = rawConfig["apiKey"]?.trim()?.takeIf { it.isNotEmpty() },
        chatCompletionsPath = rawConfig["chatCompletionsPath"]?.trim()?.takeIf { it.isNotEmpty() },
      )
    }
  }
}

/**
 * Infer the chat-completions path for an OpenAI-compatible provider
 * from its [baseUrl]. Most providers (`api.openai.com`,
 * `openrouter.ai/api`, `api.deepseek.com`) put the version in the
 * path (`/v1/chat/completions`); Zhipu BigModel under
 * `api/coding/paas/v4` does not — the URL fragment is already
 * versioned, so the chat-completions endpoint is the bare
 * `/chat/completions`.
 *
 * Plugin callers can always override the inference with the
 * `chatCompletionsPath` field in the request `config` map.
 *
 * Detection is by substring on the host portion of [baseUrl]; we
 * keep the matching conservative (single known host) to avoid
 * accidentally rewriting a custom deployment that just happens to
 * share a path shape.
 */
internal fun inferChatCompletionsPath(baseUrl: String): String {
  val lower: String = baseUrl.lowercase()
  return when {
    "bigmodel.cn" in lower -> "/chat/completions"
    else -> AgentConfiguration.DEFAULT_CHAT_COMPLETIONS_PATH
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
fun Application.registerAllRoutes(serverConfiguration: ServerConfiguration = ServerConfiguration()) {
  val serverStartTime: LocalDateTime = LocalDateTime.now()
  val activeSessions: ConcurrentHashMap<String, Agent> = ConcurrentHashMap()

  routing {
    post("/events") {
      val requestBody: EventsRequestBody = call.receive<EventsRequestBody>()
      val sessionId: String = UUID.randomUUID().toString()

      val rawProjectRoot: String? = requestBody.projectRoot
      if (rawProjectRoot.isNullOrBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(
            mapOf("error" to "projectRoot is required, the IDE must send the open project's absolute path")
          ),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val projectRootFile: File = projectRootPath.toFile()
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

      val resolvedSessionId: String? = requestBody.sessionId?.trim()?.takeIf { it.isNotEmpty() }
      val eventsChannel: Channel<String> = Channel(capacity = Channel.UNLIMITED)

      val configOverrides: ConfigOverrides = fromRequestMap(requestBody.config)

      val resolvedProvider: Provider = Provider.fromStringOrDefault(configOverrides.provider)
      val resolvedToolMode: ToolMode =
        if (requestBody.toolCallXml != null) ToolMode.AGENT
        else requestBody.toolMode?.let { ToolMode.fromStringOrDefault(it) } ?: ToolMode.AGENT

      val resolvedBaseUrl: String =
        configOverrides.baseUrl ?: AgentConfiguration.DEFAULT_OLLAMA_BASE_URL

      val agentConfiguration = AgentConfiguration(
        modelName = requestBody.model
          ?: ModelIdentity.discoverModels().firstOrNull { it.available }?.modelName
          ?: "",
        provider = resolvedProvider,
        baseUrl = resolvedBaseUrl,
        // Per-request key wins so the plugin UI can override; otherwise
        // fall back to the server-wide key resolved at startup. Null
        // here is the correct state for the local Ollama path.
        apiKey = configOverrides.apiKey ?: serverConfiguration.defaultApiKey,
        // Plugin → server path override always wins; otherwise infer
        // the path from the base URL host so plugin users don't have
        // to know Zhipu's quirk (no /v1 prefix under api/coding/paas/v4).
        chatCompletionsPath = configOverrides.chatCompletionsPath
          ?: inferChatCompletionsPath(resolvedBaseUrl),
        toolMode = resolvedToolMode,
        promptVariant = PromptVariant.fromStringOrDefault(requestBody.promptVariant),
        enableThinking = configOverrides.think ?: AgentConfiguration.DEFAULT_ENABLE_THINKING,
        topPValue = configOverrides.topP ?: AgentConfiguration.DEFAULT_TOP_P,
        temperatureValue = configOverrides.temperature ?: AgentConfiguration.DEFAULT_TEMPERATURE,
        timeoutSeconds = configOverrides.timeout ?: AgentConfiguration.DEFAULT_TIMEOUT_SECONDS,
        maxTokensToGenerate = configOverrides.numPredict ?: AgentConfiguration.DEFAULT_MAX_TOKENS_TO_GENERATE,
        contextWindowSize = configOverrides.numCtx ?: AgentConfiguration.DEFAULT_CONTEXT_WINDOW_SIZE,
        projectRoot = projectRootPath.toString(),
        sessionId = resolvedSessionId,
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

    post("/session/delete") {
      val requestBody = call.receive<DeleteSessionRequestBody>()
      val rawProjectRoot: String = requestBody.projectRoot
      if (rawProjectRoot.isBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "projectRoot is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val sessionKey: String = requestBody.sessionId.trim()
      if (sessionKey.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "sessionId is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val sessionsRoot: Path = projectRootPath.resolve(".gradum").resolve("sessions").normalize()
      val sessionDir: Path = sessionsRoot.resolve(sessionKey).normalize()

      if (!sessionDir.startsWith(sessionsRoot)) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "invalid sessionId")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val sessionFile: File = sessionDir.toFile()
      if (!sessionFile.exists()) {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionKey)),
        )
        return@post
      }

      val isDeleted: Boolean = sessionFile.deleteRecursively()
      val resultStatus: String = if (isDeleted) "deleted" else "partial_failure"
      call.respondText(
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(mapOf("status" to resultStatus, "sessionId" to sessionKey)),
      )
    }

    get("/health") {
      val uptimeSeconds: Long = Duration.between(serverStartTime, LocalDateTime.now()).seconds

      call.respondText(
        text = JsonUtil.encodeMap(
          mapOf(
            "status" to "healthy",
            "uptimeSeconds" to uptimeSeconds,
            "version" to Version.GRADUM_VERSION,
            "timestamp" to LocalDateTime.now().toString()
          )
        ),
        contentType = ContentType.Application.Json
      )
    }

    get("/models") {
      val discoveredModels: List<ModelEntry> = ModelIdentity.discoverModels()
      val availableCount: Int = discoveredModels.count { it.available }
      val filteredCount: Int = discoveredModels.size - availableCount
      application.log.info(
        "Discovered ${discoveredModels.size} models ($availableCount available, $filteredCount filtered)"
      )
      val modelToJson: (ModelEntry) -> Map<String, Any?> = { entry: ModelEntry ->
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
          mapOf("models" to discoveredModels.map(modelToJson))
        ),
        contentType = ContentType.Application.Json
      )
    }

    post("/provider/probe") {
      val requestBody: ProviderProbeRequest = call.receive<ProviderProbeRequest>()
      val result: ModelIdentity.ProviderProbeResult =
        ModelIdentity.probeProvider(requestBody.kind, requestBody.baseUrl, requestBody.apiKey)
      call.respondText(
        text = JsonUtil.encodeMap(
          mapOf(
            "status" to result.status,
            "latencyMs" to result.latencyMs,
            "error" to result.error,
          )
        ),
        contentType = ContentType.Application.Json,
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
