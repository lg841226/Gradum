package gradum.server

import gradum.*
import gradum.Version
import gradum.agent.Agent
import gradum.agent.AttachmentPayload
import gradum.agent.GradumEventType
import gradum.client.*
import gradum.logging.EventStreamDump
import gradum.mode.AgentMode
import gradum.mode.ModeRegistry
import gradum.server.ConfigOverrides.Companion.fromRequestMap
import gradum.server.persistence.*
import gradum.skill.PendingQuestions
import gradum.skill.external.DeployResult
import gradum.skill.external.ExternalDiagnosticSeverity
import gradum.skill.external.ExternalSkillHost
import gradum.skill.resolveProjectPath
import gradum.task.BackgroundTaskManager
import gradum.task.JobOutcome
import gradum.utils.ContextManager
import gradum.utils.JsonUtil
import gradum.vcs.SessionSnapshotManager
import gradum.workspace.WorkspaceHost
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors.newFixedThreadPool

private val logger: Logger = LoggerFactory.getLogger("GradumServerRoutes")

private const val EVENTS_CHANNEL_CAPACITY: Int = 2048

private const val FINISHED_RUN_RETENTION_MILLIS: Long = 5 * 60 * 1000L

private class ActiveRun() {
  private val lock = Any()
  private val frames: MutableList<Pair<Long, String>> = mutableListOf()
  private var subscriber: Channel<String>? = null

  var finished: Boolean = false
    private set

  var finishedAtMillis: Long = 0L
    private set

  fun publish(seq: Long, frame: String): Boolean = synchronized(lock) {
    frames.add(seq to frame)
    subscriber?.trySend(frame)?.isSuccess ?: true
  }

  fun subscribe(since: Long, channel: Channel<String>): List<String> = synchronized(lock) {
    subscriber?.close()
    subscriber = channel
    val replayed: List<String> =
      frames.filter { (seq, _) -> seq > since }.map { (_, frame) -> frame }
    if (finished) {
      subscriber = null
      channel.close()
    }
    replayed
  }

  fun unsubscribe(channel: Channel<String>) {
    synchronized(lock) {
      if (subscriber === channel) {
        subscriber = null
      }
    }
  }

  fun markFinished() {
    synchronized(lock) {
      finished = true
      finishedAtMillis = System.currentTimeMillis()
      subscriber?.close()
      subscriber = null
    }
  }
}

private data class SessionEntry(
  val agent: Agent,
  val sessionId: String? = null,
  val parentRunId: String? = null,
  val childRunIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
  val subAgents: MutableList<Map<String, Any>> = CopyOnWriteArrayList()
)

private fun aggregateTokenUsage(messages: List<Map<String, Any?>>): Map<String, Any> {
  var totalSum = 0
  var promptSum = 0
  var completionSum = 0
  var contextTokens = 0

  for (message: Map<String, Any?> in messages) {
    val usage: Map<*, *> = message["tokenUsage"] as? Map<*, *> ?: continue

    promptSum += (usage["promptTokens"] as? Number)?.toInt() ?: 0
    completionSum += (usage["completionTokens"] as? Number)?.toInt() ?: 0
    totalSum += (usage["totalTokens"] as? Number)?.toInt() ?: 0
    contextTokens = ((usage["contextTokens"] as? Number)?.toInt() ?: 0).takeIf { it > 0 } ?: contextTokens
  }
  return mapOf(
    "promptTokens" to promptSum,
    "completionTokens" to completionSum,
    "totalTokens" to totalSum,
    "contextTokens" to contextTokens
  )
}

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
  val sessionId: String? = null,
  val messageId: String? = null,
  val loadContext: Boolean = true,
  val promptVariant: String? = null,
  val displayText: String? = null,
  val config: Map<String, String>? = null,
  val thinkingLevel: String? = null,
  val think: Boolean? = null,
  val toolCallXml: String? = null,
  val projectRoot: String? = null,
  val attachments: List<AttachmentDto> = emptyList(),
)

@Serializable
data class AttachmentDto(
  val type: String,
  val mime: String? = null,
  val data: String? = null,
  val filename: String? = null,
  val path: String? = null,
  val endLine: Int? = null,
  val startLine: Int? = null,
  val symbolName: String? = null,
  val attachmentText: String? = null,
  val errorCount: Int? = null
)

@Serializable
data class StopRequestBody(
  val runId: String
)

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
internal data class WorkspaceFileRequest(val path: String, val content: String)

@Serializable
internal data class PairRequestBody(val code: String = "")

@Serializable
internal data class TaskSubmitRequest(
  val command: String,
  val sessionId: String,
  val projectRoot: String,
  val timeoutSeconds: Long? = null
)

fun Application.registerAllRoutes(
  pairingState: PairingState = PairingState(),
  serverConfiguration: ServerConfiguration = ServerConfiguration(),
  pendingQuestions: PendingQuestions = PendingQuestions()
) {
  val serverStartTime: LocalDateTime = LocalDateTime.now()
  val activeSessions: ConcurrentHashMap<String, SessionEntry> = ConcurrentHashMap()

  val runScope: CoroutineScope = this
  val activeRuns: ConcurrentHashMap<String, ActiveRun> = ConcurrentHashMap()

  fun purgeExpiredRuns() {
    val now: Long = System.currentTimeMillis()
    activeRuns.entries.removeIf { entry: Map.Entry<String, ActiveRun> ->
      entry.value.finished && now - entry.value.finishedAtMillis > FINISHED_RUN_RETENTION_MILLIS
    }
  }

  suspend fun respondAsSubscriber(call: ApplicationCall, run: ActiveRun, since: Long) {
    val subscriber: Channel<String> = Channel(capacity = EVENTS_CHANNEL_CAPACITY)
    val replayed: List<String> = run.subscribe(since, subscriber)
    try {
      call.respond(object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.parse("application/x-ndjson")

        override suspend fun writeTo(channel: ByteWriteChannel) {
          for (frame in replayed) {
            channel.writeStringUtf8(frame)
            channel.flush()
          }
          for (frame in subscriber) {
            channel.writeStringUtf8(frame)
            channel.flush()
          }
        }
      })
    } finally {
      run.unsubscribe(subscriber)
    }
  }

  val backgroundTaskWorkers: ExecutorService =
    newFixedThreadPool(4) { runnable ->
      Thread(runnable, "gradum-bg-task").apply { isDaemon = true }
    }
  val backgroundTaskOutcomes: Channel<JobOutcome> = Channel(capacity = 256)
  val backgroundTaskManager = BackgroundTaskManager.initialize(
    workers = backgroundTaskWorkers,
    outcomes = backgroundTaskOutcomes,
  )

  routing {
    post("/events") {
      val reconnectRunId: String? =
        call.request.queryParameters["runId"]?.trim()?.takeIf { it.isNotEmpty() }
      if (reconnectRunId != null) {
        val since: Long = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        purgeExpiredRuns()
        val reconnectRun: ActiveRun? = activeRuns[reconnectRunId]
        if (reconnectRun == null) {
          call.respondText(
            status = HttpStatusCode.NotFound,
            contentType = ContentType.Application.Json,
            text = JsonUtil.encodeMap(mapOf("status" to "not_found", "runId" to reconnectRunId)),
          )
          return@post
        }
        respondAsSubscriber(call = call, run = reconnectRun, since = since)
        return@post
      }

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

      val resolvedSessionId: String = requestBody.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: SessionIds.next()
      val eventSequencer = EventStreamSequencer()
      val run = ActiveRun()
      purgeExpiredRuns()
      activeRuns[runId] = run
      val configOverrides: ConfigOverrides = fromRequestMap(requestBody.config)
      val resolvedToolMode: String =
        if (requestBody.toolCallXml != null) "agent"
        else requestBody.toolMode?.takeIf { it.isNotBlank() } ?: "agent"

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
        configOverrides.think ?: requestBody.think ?: serverConfiguration.defaultThinkEnabled

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
        sessionId = resolvedSessionId,
        serverUrl = "http://${serverConfiguration.hostAddress}:${serverConfiguration.portNumber}",
        serverApiKey = serverConfiguration.authToken
      )

      runScope.launch(Dispatchers.IO) {
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

              val seq: Long = eventSequencer.claimSequence()
              val eventFrame: Map<String, Any?> = mapOf(
                "data" to data,
                "type" to eventType,
                "runId" to runId,
                "sessionId" to resolvedSessionId,
                "gap" to eventSequencer.pendingGap(),
                "seq" to seq,
                "timestamp" to LocalDateTime.now().toString()
              )
              val ndjsonLine: String = JsonUtil.encodeMap(eventFrame) + "\n"
              EventStreamDump.append(ndjsonLine)
              if (!run.publish(seq, ndjsonLine)) {
                eventSequencer.recordDrop()
                logger.warn(
                  "Events channel full for run $runId \u2014 dropping event " +
                    "'$eventType' (client is not draining; a reconnect replays from the buffer)"
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
              AttachmentPayload(
                type = attachment.type,
                data = attachment.data.orEmpty(),
                mime = attachment.mime,
                filename = attachment.filename,
                path = attachment.path,
                endLine = attachment.endLine,
                startLine = attachment.startLine,
                errorCount = attachment.errorCount,
                symbolName = attachment.symbolName,
                attachmentText = attachment.attachmentText,
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
          run.markFinished()
        }
      }

      respondAsSubscriber(call = call, run = run, since = 0L)
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
        activeRuns.remove(runId)
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
      val authToken: String? = serverConfiguration.authToken?.takeIf { it.isNotEmpty() }
      if (authToken != null &&
        ServerAuth.sessionRemainingSeconds(
          call.request.cookies[ServerAuth.SESSION_COOKIE_NAME],
          authToken,
        ) == null
      ) {
        call.respondText(
          status = HttpStatusCode.Unauthorized,
          contentType = ContentType.Application.Json,
          text = JsonUtil.encodeMap(mapOf("error" to "unauthorized")),
        )
        return@post
      }

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

      SessionSnapshotManager.discardSnapshot(
        sessionId = sessionKey,
        projectRoot = projectRootPath.toString()
      )

      backgroundTaskManager.cancelSession(sessionKey)

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

      backgroundTaskManager.cancelSession(sessionKey)

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

      val storedMessages: List<Map<String, Any?>> =
        SessionHistoryReader.readStoredMessages(
          (storedState?.get("messages") as? JsonArray)?.toList() ?: emptyList()
        )
      val bubbles: List<Map<String, Any?>> = SessionHistoryReader.toBubbles(storedMessages)
      val sinceIndex: Int = call.request.queryParameters["since"]?.toIntOrNull() ?: -1

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
            "lastMessageIndex" to (bubbles.size - 1),
            "title" to (transcriptMeta?.title ?: ""),
            "messages" to bubbles.drop(sinceIndex + 1),
            "modelName" to (transcriptMeta?.modelName ?: ""),
            "createdAt" to (transcriptMeta?.createdAt ?: 0L),
            "updatedAt" to (transcriptMeta?.updatedAt ?: 0L),
            "tokenUsage" to aggregateTokenUsage(storedMessages),
            "pendingAsks" to pendingQuestions.pending(sessionKey),
            "pendingBackgroundTasks" to backgroundTaskManager.pendingForSession(sessionKey),
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
      val aggregate: Map<String, Any?> = HealthMonitor.snapshot()

      val presence: HealthMonitor.Presence =
        HealthMonitor.recordPresence(call.request.local.remoteHost)
      call.respondText(
        text = JsonUtil.encodeMap(
          mapOf(
            "status" to aggregate["status"],
            "uptimeSeconds" to uptimeSeconds,
            "version" to Version.GRADUM_VERSION,
            "timestamp" to LocalDateTime.now().toString(),
            "onlineDevices" to presence.total,
            "onlineExternal" to presence.external,
            "system" to aggregate["system"],
            "components" to aggregate["components"]
          )
        ),
        contentType = ContentType.Application.Json
      )
    }

    get("/models") {
      val discoveredModels: List<ModelEntry> = ModelIdentity.discoverModels()

      val ollamaCatalogs: Map<String, OllamaModelCatalog> = discoveredModels
        .filter { it.providerType == Provider.OLLAMA.wireType }
        .map { it.serverUrl }
        .distinct()
        .associateWith { serverUrl -> OllamaModelCatalog(baseUrl = serverUrl) }
      val lmStudioCatalogs: Map<String, LmStudioModelCatalog> = discoveredModels
        .filter { it.providerType == Provider.LMSTUDIO.wireType }
        .map { it.serverUrl }
        .distinct()
        .associateWith { serverUrl -> LmStudioModelCatalog(baseUrl = serverUrl) }
      val enrichedModels: List<ModelEntry> =
        if (ollamaCatalogs.isNotEmpty() || lmStudioCatalogs.isNotEmpty()) {
          coroutineScope {
            discoveredModels.map { entry ->
              async {
                val ollamaCatalog: OllamaModelCatalog? = ollamaCatalogs[entry.serverUrl]
                val lmStudioCatalog: LmStudioModelCatalog? = lmStudioCatalogs[entry.serverUrl]
                when {
                  ollamaCatalog != null -> {
                    val caps: OllamaModelCapabilities = ollamaCatalog.capabilitiesFor(modelName = entry.modelName)
                    entry.copy(
                      reasoning = caps.supportsThinking,
                      reasoningLevels = caps.supportsThinkingLevels,
                      toolCall = caps.supportsTools,
                      attachment = caps.supportsVision,
                      contextLimit = caps.contextLength,
                      parameterSizeB = caps.parameterSizeB ?: entry.parameterSizeB
                    )
                  }

                  lmStudioCatalog != null -> {
                    val caps: LmStudioModelCapabilities = lmStudioCatalog.capabilitiesFor(modelName = entry.modelName)
                    entry.copy(
                      reasoning = caps.reasoningOptions.isNotEmpty(),
                      reasoningLevels = caps.reasoningOptions.isNotEmpty(),
                      toolCall = caps.supportsTools,
                      attachment = caps.supportsVision || entry.attachment
                    )
                  }

                  else -> entry
                }
              }
            }.awaitAll()
          }
        } else {
          discoveredModels
        }
      val modelToJson: (ModelEntry) -> Map<String, Any?> = { entry: ModelEntry ->
        mapOf(
          "name" to entry.modelName,
          "server" to entry.serverUrl,
          "toolCall" to entry.toolCall,
          "reasoning" to entry.reasoning,
          "reasoningLevels" to entry.reasoningLevels,
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
          mapOf("models" to enrichedModels.map(modelToJson))
        ),
        contentType = ContentType.Application.Json
      )
    }

    get("/modes") {
      val modesToJson: List<Map<String, Any?>> = ModeRegistry.list().map { mode: AgentMode ->
        mapOf(
          "id" to mode.id,
          "label" to mode.label,
          "description" to mode.description,
          "aliases" to mode.aliases,
        )
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("modes" to modesToJson)),
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

    get("/workspace/tree") {
      val projectRoot: String? = call.request.queryParameters["root"]
      if (!projectRoot.isNullOrBlank()) {
        respondProjectDirectory(call = call, projectRoot = projectRoot)
        return@get
      }
      val workspace = WorkspaceHost.workspace
      if (workspace == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Workspace is not initialized")),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("roots" to workspace.tree())),
        contentType = ContentType.Application.Json,
      )
    }

    get("/workspace/file") {
      val workspace = WorkspaceHost.workspace
      if (workspace == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Workspace is not initialized")),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      val requestedPath: String? = call.request.queryParameters["path"]
      if (requestedPath.isNullOrBlank()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "The path query parameter is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      val content: String? = try {
        workspace.read(requestedPath)
      } catch (invalidPath: IllegalArgumentException) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to (invalidPath.message ?: "Invalid workspace path"))),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@get
      } catch (readFailure: Exception) {
        logger.error("Workspace read failed for {}", requestedPath, readFailure)
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Read failed: ${readFailure.message}")),
          status = HttpStatusCode.InternalServerError,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      if (content == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Unknown workspace file: $requestedPath")),
          status = HttpStatusCode.NotFound,
          contentType = ContentType.Application.Json,
        )
        return@get
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("path" to requestedPath, "content" to content)),
        contentType = ContentType.Application.Json,
      )
    }

    post("/workspace/file") {
      val workspace = WorkspaceHost.workspace
      if (workspace == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Workspace is not initialized")),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val request: WorkspaceFileRequest = try {
        call.receive<WorkspaceFileRequest>()
      } catch (malformedBody: Exception) {
        if (malformedBody is CancellationException) {
          currentCoroutineContext().ensureActive()
        }
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Invalid file request body: ${malformedBody.message}")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      try {
        workspace.write(request.path, request.content)
      } catch (invalidPath: IllegalArgumentException) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to (invalidPath.message ?: "Invalid workspace path"))),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      } catch (writeFailure: Exception) {
        logger.error("Workspace write failed for {}", request.path, writeFailure)
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Write failed: ${writeFailure.message}")),
          status = HttpStatusCode.InternalServerError,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("path" to request.path, "written" to true)),
        contentType = ContentType.Application.Json,
      )
    }

    post("/workspace/deploy") {
      val workspace = WorkspaceHost.workspace
      if (workspace == null) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Workspace is not initialized")),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val request: WorkspaceFileRequest =
        try {
          call.receive<WorkspaceFileRequest>()
        } catch (malformedBody: Exception) {
          if (malformedBody is CancellationException) {
            currentCoroutineContext().ensureActive()
          }
          call.respondText(
            text = JsonUtil.encodeMap(mapOf("error" to "Invalid deploy request body: ${malformedBody.message}")),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Application.Json,
          )
          return@post
        }
      val deployResult: DeployResult = try {
        workspace.deploy(request.path, request.content)
      } catch (invalidPath: IllegalArgumentException) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to (invalidPath.message ?: "Invalid workspace path"))),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      } catch (unavailable: IllegalStateException) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to (unavailable.message ?: "Skills are not initialized"))),
          status = HttpStatusCode.ServiceUnavailable,
          contentType = ContentType.Application.Json,
        )
        return@post
      } catch (deployFailure: Exception) {
        logger.error("Workspace deploy failed for {}", request.path, deployFailure)
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "Deploy failed: ${deployFailure.message}")),
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
          if (malformedBody is CancellationException) {
            currentCoroutineContext().ensureActive()
          }
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
        call.response.header(
          HttpHeaders.SetCookie,
          ServerAuth.sessionCookieHeader(authToken, serverConfiguration.sessionTtlSeconds),
        )
      }
      call.respondText(
        text = JsonUtil.encodeMap(mapOf("ok" to true)),
        contentType = ContentType.Application.Json,
      )
    }

    post("/tasks") {
      val requestBody = call.receive<TaskSubmitRequest>()
      val sessionId: String = requestBody.sessionId.trim()
      if (sessionId.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "sessionId is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }
      val command: String = requestBody.command.trim()
      if (command.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "command is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val projectRoot: String = requestBody.projectRoot.trim()
      if (projectRoot.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "projectRoot is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@post
      }

      val timeoutSeconds: Long = requestBody.timeoutSeconds ?: 600L
      val backgroundJob = backgroundTaskManager.submit(
        sessionId = sessionId,
        command = command,
        projectRoot = projectRoot,
        timeoutSeconds = timeoutSeconds,
      )

      call.respondText(
        status = HttpStatusCode.Accepted,
        text = JsonUtil.encodeMap(
          mapOf(
            "taskId" to backgroundJob.taskId,
            "status" to backgroundJob.status.name.lowercase(),
            "startedAt" to backgroundJob.startedAt,
          )
        ),
        contentType = ContentType.Application.Json,
      )
    }

    get("/tasks") {
      val sessionId: String = call.request.queryParameters["sessionId"]?.trim().orEmpty()
      if (sessionId.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "sessionId query parameter is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@get
      }

      val jobs = backgroundTaskManager.listForSession(sessionId)
      call.respondText(
        text = Json.encodeToString(
          JsonElement.serializer(),
          JsonUtil.toJsonElement(jobs.map { job ->
            mapOf(
              "taskId" to job.taskId,
              "status" to job.status.name.lowercase(),
              "command" to job.command,
              "startedAt" to job.startedAt,
            )
          })
        ),
        contentType = ContentType.Application.Json,
      )
    }

    get("/tasks/{taskId}") {
      val taskId: String = call.parameters["taskId"]?.trim().orEmpty()
      if (taskId.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "taskId path parameter is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@get
      }

      val outcome = backgroundTaskManager.getOutcome(taskId)
      if (outcome == null) {
        val jobSnapshot = backgroundTaskManager.get(taskId)
        if (jobSnapshot == null) {
          call.respondText(
            status = HttpStatusCode.NotFound,
            text = JsonUtil.encodeMap(mapOf("status" to "not_found", "taskId" to taskId)),
            contentType = ContentType.Application.Json,
          )
          return@get
        }
        call.respondText(
          text = JsonUtil.encodeMap(
            mapOf(
              "output" to null,
              "exitCode" to null,
              "durationMs" to null,
              "taskId" to jobSnapshot.taskId,
              "command" to jobSnapshot.command,
              "startedAt" to jobSnapshot.startedAt,
              "status" to jobSnapshot.status.name.lowercase()
            )
          ),
          contentType = ContentType.Application.Json,
        )
        return@get
      }

      call.respondText(
        text = JsonUtil.encodeMap(
          mapOf(
            "taskId" to outcome.job.taskId,
            "status" to outcome.job.status.name.lowercase(),
            "command" to outcome.job.command,
            "startedAt" to outcome.job.startedAt,
            "exitCode" to outcome.exitCode,
            "durationMs" to outcome.durationMs,
            "output" to outcome.output,
          )
        ),
        contentType = ContentType.Application.Json,
      )
    }

    delete("/tasks/{taskId}") {
      val taskId: String = call.parameters["taskId"]?.trim().orEmpty()
      if (taskId.isEmpty()) {
        call.respondText(
          text = JsonUtil.encodeMap(mapOf("error" to "taskId path parameter is required")),
          status = HttpStatusCode.BadRequest,
          contentType = ContentType.Application.Json,
        )
        return@delete
      }

      val cancelled: Boolean = backgroundTaskManager.cancel(taskId)
      if (!cancelled) {
        val jobSnapshot = backgroundTaskManager.get(taskId)
        if (jobSnapshot == null) {
          call.respondText(
            status = HttpStatusCode.NotFound,
            text = JsonUtil.encodeMap(mapOf("status" to "not_found", "taskId" to taskId)),
            contentType = ContentType.Application.Json,
          )
          return@delete
        }
      }

      call.respondText(
        text = JsonUtil.encodeMap(mapOf("status" to "cancelled", "taskId" to taskId)),
        contentType = ContentType.Application.Json,
      )
    }

    // Product-level UI routes (editor pages) are installed by the host
    // application; the engine itself serves only the API.
    serverConfiguration.uiRoutes?.invoke(serverConfiguration, this)
  }
}

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

private suspend fun respondProjectDirectory(call: ApplicationCall, projectRoot: String) {
  val rootPath: Path = try {
    Paths.get(projectRoot).toAbsolutePath().normalize()
  } catch (invalidRoot: Exception) {
    call.respondText(
      text = JsonUtil.encodeMap(mapOf("error" to "Invalid project root: ${invalidRoot.message}")),
      status = HttpStatusCode.BadRequest,
      contentType = ContentType.Application.Json,
    )
    return
  }
  if (!rootPath.toFile().isDirectory) {
    call.respondText(
      text = JsonUtil.encodeMap(mapOf("error" to "Project root is not a directory: $rootPath")),
      status = HttpStatusCode.BadRequest,
      contentType = ContentType.Application.Json,
    )
    return
  }

  val requestedPath: String = call.request.queryParameters["path"]?.takeIf { it.isNotBlank() } ?: "."
  val resolvedPath = resolveProjectPath(requestedPath, rootPath.toString())
  val rejectionReason: String? = resolvedPath.rejectionReason
  if (rejectionReason != null) {
    call.respondText(
      text = JsonUtil.encodeMap(
        mapOf(
          "error" to "Path is outside the project root: $rejectionReason",
          "code" to "PERMISSION_DENIED",
        )
      ),
      status = HttpStatusCode.Forbidden,
      contentType = ContentType.Application.Json,
    )
    return
  }

  val directory: File = resolvedPath.resolved.toFile()
  if (!directory.isDirectory) {
    call.respondText(
      text = JsonUtil.encodeMap(mapOf("error" to "Not a directory: $requestedPath")),
      status = HttpStatusCode.BadRequest,
      contentType = ContentType.Application.Json,
    )
    return
  }

  call.respondText(
    text = JsonUtil.encodeMap(
      mapOf("entries" to projectDirectoryEntries(root = rootPath, directory = directory.toPath()))
    ),
    contentType = ContentType.Application.Json,
  )
}

private fun projectDirectoryEntries(root: Path, directory: Path): List<Map<String, Any?>> {
  val children: Array<File> = directory.toFile().listFiles() ?: return emptyList()
  val withinRoot: Boolean = directory.startsWith(root)
  return children.asSequence()
    .filter { child -> !child.name.startsWith(".") }
    .filter { child -> !Files.isSymbolicLink(child.toPath()) }
    .sortedWith(compareBy({ child -> !child.isDirectory }, { child -> child.name.lowercase() }))
    .mapNotNull { child ->
      val path: String = if (withinRoot) {
        root.relativize(child.toPath()).toString().replace(File.separatorChar, '/')
      } else {
        child.absolutePath.replace(File.separatorChar, '/')
      }
      when {
        child.isDirectory -> mapOf("name" to child.name, "path" to path, "directory" to true)
        child.isFile -> mapOf("name" to child.name, "path" to path, "directory" to false)
        else -> null
      }
    }
    .toList()
}
