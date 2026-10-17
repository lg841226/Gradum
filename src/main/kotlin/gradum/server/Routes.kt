package gradum.server

import gradum.*
import gradum.Version
import gradum.agent.Agent
import gradum.client.ThinkingLevel
import gradum.server.ConfigOverrides.Companion.fromRequestMap
import gradum.server.persistence.ChatDb
import gradum.server.persistence.SessionTranscriptWriter
import gradum.skill.SkillRegistry
import gradum.skill.external.DeployResult
import gradum.skill.external.ExternalDiagnosticSeverity
import gradum.skill.external.ExternalSkillHost
import gradum.utils.ContextManager
import gradum.utils.JsonUtil
import gradum.vcs.SessionSnapshotManager
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val logger: Logger = LoggerFactory.getLogger("GradumServerRoutes")

/** Cap on buffered NDJSON events per session before the producer drops. */
private const val EVENTS_CHANNEL_CAPACITY: Int = 2048

private data class SessionEntry(
  val agent: Agent,
  val parentSessionId: String? = null,
  val childSessionIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
)

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
  /**
   * Plugin-generated identifier for this user message, stamped onto the
   * message the server stores in `context.json` (field `id`). The plugin
   * uses the same value to locate the message when withdrawing, keeping the
   * two ends' histories aligned.
   */
  val messageId: String? = null,
  val loadContext: Boolean = true,
  val promptVariant: String? = null,
  /**
   * Display-only view of the user input (without the context markers and
   * thinking-guide wrappers the plugin appends for the model). Persisted
   * onto the user message so transcripts show what the user actually
   * typed; never sent back to the LLM.
   */
  val displayText: String? = null,
  val config: Map<String, String>? = null,
  /**
   * Requested thinking effort for this turn (`low` / `medium` / `high`),
   * resolved into [ThinkingLevel] when the master think switch is on.
   * Null or unknown keeps the server default (medium).
   */
  val thinkingLevel: String? = null,
  /**
   * Optional tool-call scenario XML (`<tls>` format) that bypasses the LLM
   * entirely: the server executes the described tool calls through the same
   * real `executeSingleTool` pipeline and records each result. Used by the
   * debug tool-call playback mode: the developer authors the scenario
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
   * Empty by default: the common case is a text-only message.
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
   * expected to be `image/jpeg` for the foreseeable future,
   * kept explicit so a re-introduction of raw PNG
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
   * readability: not part of the multimodal payload.
   */
  val filename: String? = null,
)

@Serializable
data class StopRequestBody(
  val sessionId: String
)

/** Body for `POST /events/respond`: resolves an in-flight ask_interaction. */
@Serializable
data class RespondRequestBody(
  /** Session the ask belongs to. */
  val sessionId: String,
  /** Unique id carried by the `ask_interaction` event this answers. */
  val requestId: String,
  /** One of the semantic codes offered by the ask (choices flavor). */
  val choice: String? = null,
  /** Free text the user submitted (input flavor). */
  val text: String? = null,
  /** True when the user dismissed the card instead of answering. */
  val cancelled: Boolean = false,
)


@Serializable
data class DeleteSessionRequestBody(
  val projectRoot: String,
  val sessionId: String
)


@Serializable
data class RenameSessionRequestBody(
  val projectRoot: String,
  val sessionId: String,
  /** New session title shown in the recent-chats list. */
  val title: String
)


/**
 * Body for `POST /session/save`: persists a full transcript Markdown that
 * the plugin produced client-side (merge flow) under the session's
 * directory. [content] is written verbatim; a non-blank [title] is stamped
 * into the session header first.
 */
@Serializable
data class SaveSessionRequestBody(
  val content: String,
  val sessionId: String,
  val projectRoot: String,
  val title: String = "",
)


@Serializable
data class RewindSessionRequestBody(
  val projectRoot: String,
  val sessionId: String,
  val messageId: String
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
  val numCtx: Int? = null,
  val topP: Double? = null,
  val timeout: Int? = null,
  val think: Boolean? = null,
  val apiKey: String? = null,
  val numPredict: Int? = null,
  val baseUrl: String? = null,
  val provider: String? = null,
  val temperature: Double? = null,
  val keepAliveMinutes: Int? = null,
  val chatCompletionsPath: String? = null
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
        topP = rawConfig["topP"]?.toDoubleOrNull(),
        numCtx = rawConfig["numCtx"]?.toIntOrNull(),
        timeout = rawConfig["timeout"]?.toIntOrNull(),
        numPredict = rawConfig["numPredict"]?.toIntOrNull(),
        temperature = rawConfig["temperature"]?.toDoubleOrNull(),
        keepAliveMinutes = rawConfig["keepAliveMinutes"]?.toIntOrNull(),
        apiKey = rawConfig["apiKey"]?.trim()?.takeIf { it.isNotEmpty() },
        chatCompletionsPath = rawConfig["chatCompletionsPath"]?.trim()?.takeIf { it.isNotEmpty() }
      )
    }
  }
}

/**
 * Infer the chat-completions path for an OpenAI-compatible provider
 * from its [baseUrl]. Most providers (`api.openai.com`,
 * `openrouter.ai/api`, `api.deepseek.com`) put the version in the
 * path (`/v1/chat/completions`); Zhipu BigModel under
 * `api/coding/paas/v4` does not: the URL fragment is already
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

@Serializable
internal data class SkillDeployRequest(val name: String, val source: String)

@Serializable
internal data class PairRequestBody(val code: String = "")

/**
 * Registers every HTTP route the Gradum server exposes:
 * - `POST /events`: streams an agent run as NDJSON.
 * - `POST /stop`  : aborts an active agent session.
 * - `GET /health` : liveness probe.
 * - `GET /models` : discovered LLM models.
 * - `GET /skills` : registered Skill implementations.
 * - `POST /skills/deploy` : write one external skill source and return compile diagnostics.
 * - `POST /skills/build` : compile one external skill source without writing or loading it.
 * - `POST /skills/pair` : trade the five-character pairing code for the auth cookie.
 * - `GET /skills/sources` : list the external skill source names.
 * - `GET /skills/source`  : read one external skill source.
 * - `GET /skills/editor`  : the bundled skill editor page.
 * - `GET /skills/editor/{asset...}` : static assets for the editor page.
 */
fun Application.registerAllRoutes(
  pairingState: PairingState = PairingState(),
  serverConfiguration: ServerConfiguration = ServerConfiguration(),
  pendingQuestions: gradum.skill.PendingQuestions = gradum.skill.PendingQuestions()
) {
  val serverStartTime: LocalDateTime = LocalDateTime.now()
  val activeSessions: ConcurrentHashMap<String, SessionEntry> = ConcurrentHashMap()

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
      val eventsChannel: Channel<String> = Channel(capacity = EVENTS_CHANNEL_CAPACITY)
      val eventSequencer: EventStreamSequencer = EventStreamSequencer()
      val configOverrides: ConfigOverrides = fromRequestMap(requestBody.config)
      val resolvedToolMode: ToolMode =
        if (requestBody.toolCallXml != null) ToolMode.AGENT
        else requestBody.toolMode?.let { ToolMode.fromStringOrDefault(it) } ?: ToolMode.AGENT

      val serverDefaultModel: String? =
        serverConfiguration.defaultModelName.trim().takeIf { it.isNotEmpty() }

      val resolvedModelName: String = requestBody.model
        ?: serverDefaultModel
        ?: ModelIdentity.discoverModels().firstOrNull { it.available }?.modelName
        ?: ""

      val resolvedTarget: ModelIdentity.ResolvedModelTarget = ModelIdentity.resolveModelTarget(
        modelName = resolvedModelName,
        overrideApiKey = configOverrides.apiKey,
        overrideBaseUrl = configOverrides.baseUrl,
        overrideProvider = configOverrides.provider,
        fallbackApiKey = serverConfiguration.defaultApiKey,
        fallbackBaseUrl = serverConfiguration.defaultBaseUrl,
        fallbackProvider = Provider.fromStringOrDefault(configOverrides.provider).wireType
      )
      val resolvedProvider: Provider = Provider.fromStringOrDefault(resolvedTarget.providerType)
      val resolvedBaseUrl: String = resolvedTarget.baseUrl

      // The legacy `think` config override still owns the master switch;
      // the level only matters while thinking is enabled.
      val thinkingMasterOn: Boolean =
        configOverrides.think ?: serverConfiguration.defaultThinkEnabled

      val agentConfiguration = AgentConfiguration(
        baseUrl = resolvedBaseUrl,
        provider = resolvedProvider,
        toolMode = resolvedToolMode,
        modelName = resolvedModelName,
        apiKey = resolvedTarget.apiKey,
        projectRoot = projectRootPath.toString(),
        chatCompletionsPath =
          configOverrides.chatCompletionsPath ?: inferChatCompletionsPath(resolvedBaseUrl),
        topPValue = configOverrides.topP ?: AgentConfiguration.DEFAULT_TOP_P,
        promptVariant = PromptVariant.fromStringOrDefault(requestBody.promptVariant),
        enableThinking = thinkingMasterOn,
        thinkingLevel =
          if (thinkingMasterOn)
            ThinkingLevel.fromStringOrDefault(requestBody.thinkingLevel, default = ThinkingLevel.MEDIUM)
          else ThinkingLevel.OFF,
        timeoutSeconds = configOverrides.timeout ?: AgentConfiguration.DEFAULT_TIMEOUT_SECONDS,
        temperatureValue = configOverrides.temperature ?: AgentConfiguration.DEFAULT_TEMPERATURE,
        contextWindowSize = configOverrides.numCtx ?: AgentConfiguration.DEFAULT_CONTEXT_WINDOW_SIZE,
        keepAliveMinutes = configOverrides.keepAliveMinutes ?: serverConfiguration.defaultKeepAliveMinutes,
        maxTokensToGenerate = configOverrides.numPredict ?: AgentConfiguration.DEFAULT_MAX_TOKENS_TO_GENERATE,
        sessionId = resolvedSessionId
      )

      launch(Dispatchers.IO) {
        try {
          // Sub-agent registration/unregistration callbacks scoped to this
          // session's parent-child hierarchy. See [SessionEntry].
          val registerChildSession: (String, Agent) -> Unit = { childId: String, childAgent: Agent ->
            val childEntry = SessionEntry(
              agent = childAgent,
              parentSessionId = sessionId
            )
            activeSessions[childId] = childEntry
            activeSessions.computeIfPresent(sessionId) { _: String, parentEntry: SessionEntry ->
              parentEntry.childSessionIds.add(childId)
              parentEntry
            }
            logger.info("Registered sub-agent $childId under parent $sessionId")
          }
          val unregisterChildSession: (String) -> Unit = { childId: String ->
            activeSessions.remove(childId)?.let {
              activeSessions.computeIfPresent(sessionId) { _: String, parentEntry: SessionEntry ->
                parentEntry.childSessionIds.remove(childId)
                parentEntry
              }
              logger.info("Unregistered sub-agent $childId")
            }
          }

          val agentInstance = Agent(
            configuration = agentConfiguration,
            emitEvent = { eventType: String, data: Map<String, Any> ->
              val ndjsonLine: String = JsonUtil.encodeMap(
                mapOf(
                  "type" to eventType,
                  "timestamp" to LocalDateTime.now().toString(),
                  "sessionId" to sessionId,
                  "seq" to eventSequencer.claimSequence(),
                  "gap" to eventSequencer.pendingGap(),
                  "data" to data
                )
              ) + "\n"
              val sendResult: ChannelResult<Unit> = eventsChannel.trySend(ndjsonLine)
              if (sendResult.isFailure) {
                eventSequencer.recordDrop()
                logger.warn(
                  "Events channel full for session $sessionId \u2014 dropping event " +
                    "'$eventType' (client is not draining; streaming is already stalled)"
                )
              } else {
                eventSequencer.markDelivered()
              }
            },
            registerChildSession = registerChildSession,
            unregisterChildSession = unregisterChildSession,
            askScopeHolder = pendingQuestions,
            plugins = serverConfiguration.plugins,
          )

          activeSessions[sessionId] = SessionEntry(agent = agentInstance)
          agentInstance.executeTask(
            userInput = requestBody.message,
            displayText = requestBody.displayText.orEmpty(),
            messageId = requestBody.messageId,
            toolCallXml = requestBody.toolCallXml,
            loadPreviousContext = requestBody.loadContext,
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
          val undeliveredDrops: Int = eventSequencer.pendingGap()
          if (undeliveredDrops > 0) {
            logger.warn(
              "Session $sessionId ended with $undeliveredDrops dropped event(s) never " +
                "reported to the client"
            )
          }
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

    post("/events/respond") {
      val requestBody: RespondRequestBody = call.receive<RespondRequestBody>()
      val sessionId: String = requestBody.sessionId.trim()
      val requestId: String = requestBody.requestId.trim()
      if (sessionId.isEmpty() || requestId.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "sessionId and requestId are required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val (action, toResult) =
        when {
          requestBody.cancelled ->
            "cancelled" to pendingQuestions.completeCancelled(sessionId, requestId)

          requestBody.choice != null ->
            "choice" to pendingQuestions.completeChoice(sessionId, requestId, requestBody.choice)

          requestBody.text != null ->
            "text" to pendingQuestions.completeText(sessionId, requestId, requestBody.text)

          else -> "" to null
        }

      if (action.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "exactly one of choice, text, or cancelled must be provided")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      if (toResult == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionId, "requestId" to requestId)),
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      call.respondText(
        text = JsonUtil.encodeMap(mapOf("status" to "delivered", "sessionId" to sessionId, "requestId" to requestId)),
        contentType = ContentType.Application.Json,
      )
    }

    post("/stop") {
      val requestBody = call.receive<StopRequestBody>()
      val sessionId = requestBody.sessionId
      val targetEntry = activeSessions.remove(sessionId)

      if (targetEntry != null) {
        val agentsToStop: MutableList<Agent> = mutableListOf(targetEntry.agent)
        val childIdsToRemove: MutableList<String> = mutableListOf()

        fun collectDescendants(entry: SessionEntry) {
          for (childId: String in entry.childSessionIds.toSet()) {
            val childEntry: SessionEntry? = activeSessions.remove(childId)
            if (childEntry != null) {
              agentsToStop.add(childEntry.agent)
              childIdsToRemove.add(childId)
              collectDescendants(childEntry)
            }
          }
        }
        collectDescendants(targetEntry)

        targetEntry.childSessionIds.removeAll(childIdsToRemove.toSet())

        for (agent: Agent in agentsToStop) agent.abort()

        call.respondText(
          text = JsonUtil.encodeMap(
            mapOf(
              "status" to "stopped",
              "sessionId" to sessionId,
              "cascaded" to agentsToStop.size - 1
            )
          ),
          contentType = ContentType.Application.Json
        )
      } else {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionId))
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

      if (sessionKey == "." || sessionKey == ".." || !sessionDir.startsWith(sessionsRoot) ||
        sessionDir.parent != sessionsRoot
      ) {
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

      SessionSnapshotManager.revertSnapshot(
        sessionId = sessionKey,
        projectRoot = projectRootPath.toString()
      )

      ChatDb.deleteSession(projectRoot = projectRootPath, sessionId = sessionKey)

      val isDeleted: Boolean = sessionFile.deleteRecursively()
      val resultStatus: String = if (isDeleted) "deleted" else "partial_failure"
      call.respondText(
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(mapOf("status" to resultStatus, "sessionId" to sessionKey)),
      )
    }

    post("/session/rewind") {
      val requestBody = call.receive<RewindSessionRequestBody>()
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
      val messageId: String = requestBody.messageId.trim()
      if (messageId.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "messageId is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val sessionsRoot: Path = projectRootPath.resolve(".gradum").resolve("sessions").normalize()
      val sessionDir: Path = sessionsRoot.resolve(sessionKey).normalize()

      if (sessionKey == "." || sessionKey == ".." || !sessionDir.startsWith(sessionsRoot) ||
        sessionDir.parent != sessionsRoot
      ) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "invalid sessionId")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      if (!sessionDir.toFile().exists()) {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionKey)),
        )
        return@post
      }

      val rewinded: Boolean = ContextManager(sessionDir).rewindTo(messageId)
      if (rewinded) {
        // Worktree restore is best-effort: the message rewind must succeed even
        // when the snapshot ref is missing (older sessions) or git fails.
        SessionSnapshotManager.revertTurnSnapshot(
          projectRoot = projectRootPath.toString(),
          sessionId = sessionKey,
          messageId = messageId
        )
        val survivingMessages: List<Map<String, Any>> =
          ChatDb.loadContext(projectRoot = projectRootPath, sessionId = sessionKey)
        val transcriptMeta: SessionTranscriptWriter.TranscriptMeta? =
          SessionTranscriptWriter.readMeta(projectRoot = projectRootPath, sessionId = sessionKey)
        SessionTranscriptWriter.writeTranscript(
          projectRoot = projectRootPath,
          sessionId = sessionKey,
          modelName = transcriptMeta?.modelName.orEmpty(),
          messages = survivingMessages,
          title = transcriptMeta?.title,
        )
      }
      call.respondText(
        status = if (rewinded) HttpStatusCode.OK else HttpStatusCode.NotFound,
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(
          mapOf(
            "status" to if (rewinded) "rewound" else "message_not_found",
            "sessionId" to sessionKey,
            "messageId" to messageId
          )
        ),
      )
    }

    get("/sessions") {
      val rawProjectRoot: String? = call.request.queryParameters["projectRoot"]
      if (rawProjectRoot.isNullOrBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "projectRoot is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val sessionList: List<Map<String, Any?>> =
        SessionTranscriptWriter.listSessions(projectRoot = projectRootPath).map { meta ->
          mapOf(
            "id" to meta.sessionId,
            "title" to meta.title,
            "model" to meta.modelName,
            "createdAt" to meta.createdAt,
            "updatedAt" to meta.updatedAt
          )
        }
      call.respondText(
        contentType = ContentType.Application.Json,
        text = Json.encodeToString(JsonElement.serializer(), JsonUtil.toJsonElement(sessionList))
      )
    }

    get("/session/transcript") {
      val rawProjectRoot: String? = call.request.queryParameters["projectRoot"]
      val sessionKey: String = call.request.queryParameters["sessionId"]?.trim().orEmpty()
      if (rawProjectRoot.isNullOrBlank() || sessionKey.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "projectRoot and sessionId are required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val transcriptContent: String? =
        SessionTranscriptWriter.loadTranscript(projectRoot = projectRootPath, sessionId = sessionKey)
      if (transcriptContent == null) {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionKey)),
        )
        return@get
      }
      call.respondText(text = transcriptContent, contentType = ContentType.parse("text/markdown"))
    }

    post("/session/save") {
      val requestBody = call.receive<SaveSessionRequestBody>()
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
      val saved: Boolean = SessionTranscriptWriter.saveTranscript(
        projectRoot = projectRootPath,
        sessionId = sessionKey,
        newTitle = requestBody.title,
        content = requestBody.content
      )
      call.respondText(
        status = if (saved) HttpStatusCode.OK else HttpStatusCode.BadRequest,
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(
          mapOf("status" to if (saved) "saved" else "rejected", "sessionId" to sessionKey)
        ),
      )
    }

    post("/session/rename") {
      val requestBody = call.receive<RenameSessionRequestBody>()
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
      if (requestBody.title.isBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "title is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val renamed: Boolean = SessionTranscriptWriter.renameSession(
        projectRoot = projectRootPath,
        sessionId = sessionKey,
        newTitle = requestBody.title
      )
      call.respondText(
        status = if (renamed) HttpStatusCode.OK else HttpStatusCode.NotFound,
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(
          mapOf("status" to if (renamed) "renamed" else "not_found", "sessionId" to sessionKey)
        ),
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
      val modelToJson: (ModelEntry) -> Map<String, Any?> = { entry: ModelEntry ->
        mapOf(
          "name" to entry.modelName,
          "server" to entry.serverUrl,
          "toolCall" to entry.toolCall,
          "reasoning" to entry.reasoning,
          "available" to entry.available,
          "provider" to entry.providerType,
          "attachment" to entry.attachment,
          "serverName" to entry.serverName,
          "openWeights" to entry.openWeights,
          "remoteHost" to entry.remoteHost,
          "parameterSizeB" to entry.parameterSizeB,
          "contextLimit" to entry.contextLimit,
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

    get("/skills/sources") {
      val sourceNames: List<String> = ExternalSkillHost.scanner?.listSourceNames().orEmpty()
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("sources" to sourceNames)),
        contentType = ContentType.Application.Json,
      )
    }

    get("/skills/source") {
      val requestedName: String? = call.request.queryParameters["name"]
      val source: String? = requestedName?.let { name -> ExternalSkillHost.scanner?.readSource(name) }
      if (requestedName.isNullOrBlank() || source == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Unknown skill source: $requestedName")),
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("name" to requestedName, "source" to source)),
        contentType = ContentType.Application.Json,
      )
    }

    post("/skills/deploy") {
      val scanner = ExternalSkillHost.scanner
      if (scanner == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "External skills directory is not initialized")),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val request: SkillDeployRequest =
        try {
          call.receive<SkillDeployRequest>()
        } catch (malformedBody: Exception) {
          call.respondText(
            text = JsonUtil.encodeMap(mapOf("error" to "Invalid deploy request body: ${malformedBody.message}")),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Application.Json,
          )
          return@post
        }
      val deployResult =
        try {
          scanner.deploy(request.name, request.source)
        } catch (invalidName: IllegalArgumentException) {
          call.respondText(
            text = JsonUtil.encodeMap(mapOf("error" to (invalidName.message ?: "Invalid skill name"))),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Application.Json,
          )
          return@post
        } catch (deployFailure: Exception) {
          logger.error("Skill deploy failed for {}", request.name, deployFailure)
          call.respondText(
            text = JsonUtil.encodeMap(
              mapOf("error" to "Deploy failed: ${deployFailure.message ?: deployFailure.toString()}")
            ),
            status = HttpStatusCode.InternalServerError,
            contentType = ContentType.Application.Json,
          )
          return@post
        }
      call.respondSkillCompileResult(deployResult)
    }

    post("/skills/build") {
      val scanner = ExternalSkillHost.scanner
      if (scanner == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "External skills directory is not initialized")),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val request: SkillDeployRequest =
        try {
          call.receive<SkillDeployRequest>()
        } catch (malformedBody: Exception) {
          call.respondText(
            text = JsonUtil.encodeMap(mapOf("error" to "Invalid build request body: ${malformedBody.message}")),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Application.Json,
          )
          return@post
        }
      val buildResult =
        try {
          scanner.compileOnly(request.name, request.source)
        } catch (invalidName: IllegalArgumentException) {
          call.respondText(
            text = JsonUtil.encodeMap(mapOf("error" to (invalidName.message ?: "Invalid skill name"))),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Application.Json,
          )
          return@post
        } catch (buildFailure: Exception) {
          logger.error("Skill build failed for {}", request.name, buildFailure)
          call.respondText(
            text = JsonUtil.encodeMap(
              mapOf("error" to "Build failed: ${buildFailure.message ?: buildFailure.toString()}")
            ),
            status = HttpStatusCode.InternalServerError,
            contentType = ContentType.Application.Json,
          )
          return@post
        }
      call.respondSkillCompileResult(buildResult)
    }

    post(ServerAuth.PAIR_PATH) {
      val requestBody: PairRequestBody = call.receive<PairRequestBody>()
      if (!pairingState.tryUnlock(requestBody.code)) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "bad pairing code")),
          status = HttpStatusCode.Unauthorized,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val authToken: String? = serverConfiguration.authToken?.takeIf { it.isNotEmpty() }
      if (authToken != null) {
        call.response.header(HttpHeaders.SetCookie, ServerAuth.authCookieHeader(authToken))
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("ok" to true)),
        contentType = ContentType.Application.Json,
      )
    }

    get("/skills/editor") {
      val editorHtml: String? = readBundledResource("/skills-editor/index.html")
      if (editorHtml == null) {
        call.respondText(
          text = "Skill editor page is missing from the server resources",
          status = HttpStatusCode.NotFound,
        )
        return@get
      }

      if (call.request.queryParameters["lock"] == "1") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
          text = editorHtml.replaceFirst("<html", "<html data-reader"),
          contentType = ContentType.Text.Html,
        )
        return@get
      }

      val authToken: String? = serverConfiguration.authToken?.takeIf { it.isNotEmpty() }
      if (authToken != null) {
        val queryToken: String? = call.request.queryParameters[ServerAuth.TOKEN_QUERY_PARAM]
        val cookieToken: String? = call.request.cookies[ServerAuth.TOKEN_COOKIE_NAME]

        if (ServerAuth.tokenMatches(queryToken, authToken)) {
          call.response.header(HttpHeaders.SetCookie, ServerAuth.authCookieHeader(authToken))
        } else if (!ServerAuth.tokenMatches(cookieToken, authToken)) {
          val marker =
            if (queryToken != null) " data-reader=\"invalid\""
            else " data-reader"

          call.response.header(HttpHeaders.CacheControl, "no-store")
          call.respondText(
            text = editorHtml.replaceFirst("<html", "<html$marker"),
            contentType = ContentType.Text.Html,
          )
          return@get
        }
      }
      call.response.header(HttpHeaders.CacheControl, "no-store")
      call.respondText(text = editorHtml, contentType = ContentType.Text.Html)
    }

    get("/skills/editor/{asset...}") {
      val segments: List<String> = call.parameters.getAll("asset").orEmpty()
      val asset: String = segments.joinToString("/")
      val contentType: ContentType? =
        when {
          asset.endsWith(".css") -> ContentType.Text.CSS
          asset.endsWith(".js") -> ContentType.Text.JavaScript
          asset.endsWith(".svg") -> ContentType.Image.SVG
          asset.endsWith(".html") -> ContentType.Text.Html
          else -> null
        }

      val isSafe: Boolean = segments.isNotEmpty() && segments.all { segment ->
        segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains('\\')
      }

      val body: String? =
        if (isSafe) readBundledResource("/skills-editor/$asset")
        else null

      if (body == null || contentType == null) {
        call.respondText(
          status = HttpStatusCode.NotFound,
          text = "Unknown editor asset: $asset"
        )
        return@get
      }
      call.respondText(text = body, contentType = contentType)
    }
  }
}

/**
 * The single place that shapes a compile outcome for the wire. Both deploy and
 * build answer with this body, so the editor parses one result shape whichever
 * request it sent.
 */
private suspend fun ApplicationCall.respondSkillCompileResult(result: DeployResult) {
  respondText(
    text = JsonUtil.encodeMap(
      mapOf(
        "fileName" to result.fileName,
        "compiled" to result.compiled,
        "diagnostics" to result.diagnostics
          .filter { diagnostic -> diagnostic.severity != ExternalDiagnosticSeverity.INFO }
          .map { diagnostic ->
            mapOf(
              "severity" to diagnostic.severity.name.lowercase(),
              "message" to diagnostic.message,
              "line" to diagnostic.line,
              "column" to diagnostic.column,
            )
          },
      )
    ),
    contentType = ContentType.Application.Json,
  )
}

private fun readBundledResource(resourcePath: String): String? =
  SkillDeployRequest::class.java.getResourceAsStream(resourcePath)
    ?.bufferedReader()
    ?.use { reader -> reader.readText() }
