package gradum.server

import gradum.*
import gradum.Version
import gradum.agent.Agent
import gradum.agent.GradumEventType
import gradum.client.ThinkingLevel
import gradum.server.ConfigOverrides.Companion.fromRequestMap
import gradum.server.persistence.ChatDb
import gradum.server.persistence.SessionIds
import gradum.server.persistence.SessionMerger
import gradum.server.persistence.SessionStateStore
import gradum.server.persistence.SessionTranscriptWriter
import gradum.skill.PendingQuestions
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
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger: Logger = LoggerFactory.getLogger("GradumServerRoutes")

/** Cap on buffered NDJSON events per run before the producer drops. */
private const val EVENTS_CHANNEL_CAPACITY: Int = 2048

/**
 * One live `/events` run, keyed in `activeSessions` by the run id.
 *
 * A run belongs to a [sessionId] (the durable session) and may have child
 * runs (sub-agents), which is why the parent link is a run id.
 */
private data class SessionEntry(
  val agent: Agent,
  val sessionId: String? = null,
  val parentRunId: String? = null,
  val childRunIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
  /** Live `sub_agent:start` payloads, mirrored so a reload can redraw them. */
  val subAgents: MutableList<Map<String, Any>> = CopyOnWriteArrayList()
)

/** Sums per-message token usage; `contextTokens` comes from the newest message reporting it. */
private fun aggregateTokenUsage(messages: List<JsonElement>): Map<String, Any> {
  var totalSum = 0
  var promptSum = 0
  var completionSum = 0
  var contextTokens = 0

  for (message: JsonElement in messages) {
    val usage: JsonObject = ((message as? JsonObject)?.get("tokenUsage") as? JsonObject) ?: continue

    promptSum += usage.intField("promptTokens")
    completionSum += usage.intField("completionTokens")
    totalSum += usage.intField("totalTokens")
    contextTokens = usage.intField("contextTokens").takeIf { it > 0 } ?: contextTokens
  }
  return mapOf(
    "promptTokens" to promptSum,
    "completionTokens" to completionSum,
    "totalTokens" to totalSum,
    "contextTokens" to contextTokens
  )
}

private fun JsonObject.intField(key: String): Int =
  (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

@Serializable
data class ProviderProbeRequest(
  val kind: String,
  val baseUrl: String,
  val apiKey: String? = null,
)

@Serializable
data class EventsRequestBody(
  val message: String,
  val model: String? = null,
  val toolMode: String? = null,
  /** Scopes persisted context to `.gradum/sessions/<sessionId>/`; blank falls back to legacy `context.json`. */
  val sessionId: String? = null,
  /** Stamped onto the stored user message; the plugin reuses it to locate the message when withdrawing. */
  val messageId: String? = null,
  val loadContext: Boolean = true,
  val promptVariant: String? = null,
  /** Display-only user input (no context markers), persisted for transcripts, never sent to the LLM. */
  val displayText: String? = null,
  val config: Map<String, String>? = null,
  /** `low` / `medium` / `high`; null or unknown keeps the server default. */
  val thinkingLevel: String? = null,
  /** Debug tool-call scenario XML (`<tls>`): executes through the real tool pipeline instead of the LLM. */
  val toolCallXml: String? = null,
  /** Absolute path of the IDE-open project; root for every file/process tool this session. */
  val projectRoot: String? = null,
  /** Image attachments; wire shape mirrors the plugin's `ApiImageAttachment`. */
  val attachments: List<AttachmentDto> = emptyList(),
)

/** One attachment element; `type` discriminates, only `image` is implemented today. */
@Serializable
data class AttachmentDto(
  val type: String,
  /** Wire-mime; the plugin normalizes uploads to JPEG. */
  val mime: String? = null,
  /** Base64 payload. */
  val data: String? = null,
  /** Original file name; prompt and transcript hint only. */
  val filename: String? = null
)

@Serializable
data class StopRequestBody(
  val runId: String
)

/** Body for `POST /events/respond`: resolves an in-flight ask_interaction. */
@Serializable
data class RespondRequestBody(
  val sessionId: String,
  val requestId: String,
  val text: String? = null,
  val choice: String? = null,
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
  val title: String
)

/** Body for `POST /sessions/merge`; the server numbers [baseTitle] per existing sessions. */
@Serializable
data class MergeSessionsRequestBody(
  val projectRoot: String,
  val sessionIds: List<String>,
  val baseTitle: String
)


@Serializable
data class RewindSessionRequestBody(
  val projectRoot: String,
  val sessionId: String,
  val messageId: String
)

/** Typed view over the request's `config` map; build via [fromRequestMap]. */
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
    /** Converts the raw string `config` map; null input yields an empty instance. */
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
  pendingQuestions: PendingQuestions = PendingQuestions()
) {
  val serverStartTime: LocalDateTime = LocalDateTime.now()
  val activeSessions: ConcurrentHashMap<String, SessionEntry> = ConcurrentHashMap()

  routing {
    post("/events") {
      val requestBody: EventsRequestBody = call.receive<EventsRequestBody>()
      val runId: String = UUID.randomUUID().toString()

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

      // Blank means "new chat": the server names the session.
      val resolvedSessionId: String =
        requestBody.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: SessionIds.next()
      val eventsChannel: Channel<String> = Channel(capacity = EVENTS_CHANNEL_CAPACITY)
      val eventSequencer = EventStreamSequencer()
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
          val registerChildSession: (String, Agent) -> Unit = { childId: String, childAgent: Agent ->
            val childEntry = SessionEntry(
              agent = childAgent,
              sessionId = resolvedSessionId,
              parentRunId = runId
            )
            activeSessions[childId] = childEntry
            activeSessions.computeIfPresent(runId) { _: String, parentEntry: SessionEntry ->
              parentEntry.childRunIds.add(childId)
              parentEntry
            }
            logger.info("Registered sub-agent $childId under run $runId")
          }
          val unregisterChildSession: (String) -> Unit = { childId: String ->
            activeSessions.remove(childId)?.let {
              activeSessions.computeIfPresent(runId) { _: String, parentEntry: SessionEntry ->
                parentEntry.childRunIds.remove(childId)
                parentEntry
              }
              logger.info("Unregistered sub-agent $childId")
            }
          }

          val agentInstance = Agent(
            configuration = agentConfiguration,
            emitEvent = { eventType: String, data: Map<String, Any> ->
              when (eventType) {
                GradumEventType.SUB_AGENT_START.wireName ->
                  activeSessions[runId]?.subAgents?.add(data)

                GradumEventType.SUB_AGENT_SESSION_END.wireName ->
                  activeSessions[runId]?.subAgents?.clear()
              }

              val ndjsonLine: String = JsonUtil.encodeMap(
                mapOf(
                  "data" to data,
                  "type" to eventType,
                  "runId" to runId,
                  "sessionId" to resolvedSessionId,
                  "gap" to eventSequencer.pendingGap(),
                  "seq" to eventSequencer.claimSequence(),
                  "timestamp" to LocalDateTime.now().toString()
                )
              ) + "\n"
              val sendResult: ChannelResult<Unit> = eventsChannel.trySend(ndjsonLine)
              if (sendResult.isFailure) {
                eventSequencer.recordDrop()
                logger.warn(
                  "Events channel full for run $runId \u2014 dropping event " +
                    "'$eventType' (client is not draining; streaming is already stalled)"
                )
              } else {
                eventSequencer.markDelivered()
              }
            },
            askScopeHolder = pendingQuestions,
            plugins = serverConfiguration.plugins,
            registerChildSession = registerChildSession,
            unregisterChildSession = unregisterChildSession,
          )

          activeSessions[runId] = SessionEntry(agent = agentInstance, sessionId = resolvedSessionId)
          agentInstance.executeTask(
            userInput = requestBody.message,
            messageId = requestBody.messageId,
            toolCallXml = requestBody.toolCallXml,
            loadPreviousContext = requestBody.loadContext,
            displayText = requestBody.displayText.orEmpty(),
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
              "Run $runId ended with $undeliveredDrops dropped event(s) never " +
                "reported to the client"
            )
          }
          activeSessions.remove(runId)
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
      val runId = requestBody.runId
      val targetEntry = activeSessions.remove(runId)

      if (targetEntry != null) {
        val agentsToStop: MutableList<Agent> = mutableListOf(targetEntry.agent)
        val childIdsToRemove: MutableList<String> = mutableListOf()

        fun collectDescendants(entry: SessionEntry) {
          for (childId: String in entry.childRunIds.toSet()) {
            val childEntry: SessionEntry? = activeSessions.remove(childId)
            if (childEntry != null) {
              agentsToStop.add(childEntry.agent)
              childIdsToRemove.add(childId)
              collectDescendants(childEntry)
            }
          }
        }
        collectDescendants(targetEntry)

        targetEntry.childRunIds.removeAll(childIdsToRemove.toSet())

        for (agent: Agent in agentsToStop) agent.abort()

        call.respondText(
          text = JsonUtil.encodeMap(
            mapOf(
              "status" to "stopped",
              "runId" to runId,
              "cascaded" to agentsToStop.size - 1
            )
          ),
          contentType = ContentType.Application.Json
        )
      } else {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "runId" to runId))
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

      val previousTitle: String = SessionTranscriptWriter
        .readMeta(projectRoot = projectRootPath, sessionId = sessionKey)?.title.orEmpty()
      val rewinded: Boolean = ContextManager(sessionDir).rewindTo(messageId)
      if (rewinded) {
        SessionSnapshotManager.revertTurnSnapshot(
          projectRoot = projectRootPath.toString(),
          sessionId = sessionKey,
          messageId = messageId
        )

        if (previousTitle.isNotBlank()) {
          SessionTranscriptWriter.renameSession(
            projectRoot = projectRootPath,
            sessionId = sessionKey,
            newTitle = previousTitle
          )
        }
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

    post("/sessions/merge") {
      val requestBody: MergeSessionsRequestBody = call.receive<MergeSessionsRequestBody>()
      val rawProjectRoot: String = requestBody.projectRoot
      if (rawProjectRoot.isBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "projectRoot is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val sourceIds: List<String> = requestBody.sessionIds.map { it.trim() }.filter { it.isNotEmpty() }
      if (sourceIds.size < 2) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "sessionIds must list at least two sessions")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      if (requestBody.baseTitle.isBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "baseTitle is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val projectRootPath: Path = Paths.get(rawProjectRoot).toAbsolutePath().normalize()
      val mergeResult: SessionMerger.MergeResult? = SessionMerger.merge(
        projectRoot = projectRootPath,
        sourceIds = sourceIds,
        baseTitle = requestBody.baseTitle
      )
      if (mergeResult == null) {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "source_not_found")),
        )
        return@post
      }
      call.respondText(
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(
          mapOf(
            "status" to "merged",
            "sessionId" to mergeResult.sessionId,
            "title" to mergeResult.title
          )
        ),
      )
    }

    get("/session/state") {
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

      val storedState: JsonObject? = SessionStateStore.read(projectRootPath, sessionKey)
      val transcriptMeta: SessionTranscriptWriter.TranscriptMeta? =
        SessionTranscriptWriter.readMeta(projectRootPath, sessionKey)
      if (storedState == null && transcriptMeta == null) {
        call.respondText(
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("status" to "not_found", "sessionId" to sessionKey)),
        )
        return@get
      }

      val fullMessages: List<JsonElement> =
        (storedState?.get("messages") as? JsonArray)?.toList() ?: emptyList()
      val sinceSeq: Int = call.request.queryParameters["since"]?.toIntOrNull() ?: -1

      val activeEntry: Map.Entry<String, SessionEntry>? = activeSessions.entries
        .firstOrNull { entry: Map.Entry<String, SessionEntry> -> entry.value.sessionId == sessionKey }
      val activeRunId: String? = activeEntry?.key

      call.respondText(
        contentType = ContentType.Application.Json,
        text = JsonUtil.encodeMap(
          mapOf(
            "runId" to activeRunId,
            "sessionId" to sessionKey,
            "isRunning" to (activeRunId != null),
            "lastMessageIndex" to (fullMessages.size - 1),
            "title" to (transcriptMeta?.title ?: ""),
            "messages" to fullMessages.drop(sinceSeq + 1),
            "modelName" to (transcriptMeta?.modelName ?: ""),
            "createdAt" to (transcriptMeta?.createdAt ?: 0L),
            "updatedAt" to (transcriptMeta?.updatedAt ?: 0L),
            "tokenUsage" to aggregateTokenUsage(fullMessages),
            "pendingAsks" to pendingQuestions.pending(sessionKey),
            "subAgents" to (activeEntry?.value?.subAgents?.toList() ?: emptyList())
          )
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
