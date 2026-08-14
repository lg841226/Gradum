/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSession.kt  2026-08-14 13:14:02 Changed by gwy
 */

package gradum.idea.chat.state

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.history.ChatSessionStore
import gradum.idea.chat.history.ChatTranscript
import gradum.idea.chat.history.SessionMeta
import gradum.idea.chat.model.*
import gradum.idea.chat.ui.chat.errorDetailText
import gradum.idea.chat.ui.chat.friendlyErrorMessage
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.chat.ui.util.ThinkingPromptInjector
import gradum.idea.editor.*
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Raw response from the `/models` endpoint, containing the list of discovered LLM models.
 *
 * `recommended` is the server-side pick driven by the `ModelRecommender` algorithm
 * (cloud-first, then biggest local model that fits in the current free RAM).
 * It is nullable for backward compatibility with older server builds and is
 * decoded leniently — a missing field is treated as "no recommendation".
 */
@Serializable
private data class ModelsListResponse(
  val models: List<ModelInfo>,
  val recommended: ModelInfo? = null
)

/**
 * Project-level service that manages the chat session state.
 *
 * This class serves as the central state holder for the Gradum chat interface,
 * coordinating between the UI layer (JetBrains Compose) and the backend server.
 * It manages message history, model selection, file attachments, and the message
 * queue for handling concurrent requests.
 *
 * The session communicates with the Gradum server via [GradumApiClient] to
 * fetch available models and send chat messages. Responses are streamed back
 * as NDJSON events and progressively update the assistant's message content.
 *
 * Registered as a project-level service in `plugin.xml` so that state persists
 * across tool window open/close cycles within the same project.
 */
@Service(Service.Level.PROJECT)
class GradumChatSession {

  private val log: Logger = Logger.getInstance(GradumChatSession::class.java)

  /** Coroutine scope used by [processPendingQueue] to launch the next send. */
  var scope: CoroutineScope? = null

  /**
   * The IntelliJ project this session belongs to. Set once by
   * [gradum.idea.GradumToolWindowFactory.createToolWindowContent] and used
   * to resolve the project root path that is sent with every chat request
   * so the server knows which tree to operate on.
   */
  var project: Project? = null

  /** The text field state for the chat input area. */
  val textState: TextFieldState = TextFieldState()

  /** The list of chat messages displayed in the conversation view. */
  val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()

  /** File and text attachments currently staged for the next message. */
  val attachedFiles: SnapshotStateList<AttachedContext> = mutableStateListOf()

  /** Messages queued while the assistant is still processing a previous request. */
  val pendingMessages: SnapshotStateList<PendingMessage> = mutableStateListOf()

  /** Whether the user has sent at least one message in this session. */
  var hasSentMessage: Boolean by mutableStateOf(false)

  /**
   * Strength of the reasoning hint the plugin will append to the next
   * outgoing user message. Defaults to [ThinkingLevel.MEDIUM] — a
   * brand-new session gets a balanced hint out of the box; the user
   * can dial it down to [ThinkingLevel.LOW] for cheap-and-fast or up
   * to [ThinkingLevel.HIGH] for refactors and architecture questions.
   * Mutated only via [setThinkingLevel] so the call site stays
   * idempotent (a no-op when the value is already current). Held as
   * a `MutableState` directly (not `by` delegation) so Kotlin doesn't
   * synthesize a public setter that would clash with
   * [setThinkingLevel] at the JVM level.
   */
  private val _thinkingLevel = mutableStateOf(ThinkingLevel.MEDIUM)
  val thinkingLevel: ThinkingLevel get() = _thinkingLevel.value

  /**
   * Sets [thinkingLevel]. Idempotent: calling with the already-current
   * level is a no-op so the model-list / model-switch callers don't
   * have to compare first.
   *
   * No capability clamp: the hint is prompt-injected and works on
   * every model, so the dropdown is always enabled regardless of the
   * selected model's `reasoning` catalog flag. Keeping this a plain
   * setter also lets the user freely carry a [ThinkingLevel.HIGH]
   * preference across models without it silently dropping on switch.
   */
  fun setThinkingLevel(level: ThinkingLevel) {
    if (level != _thinkingLevel.value) _thinkingLevel.value = level
  }

  /** Whether the assistant is currently generating a response. */
  var isSending: Boolean by mutableStateOf(false)

  /** Whether we are waiting for the first event from the server (request sent, no response yet). */
  var isWaitingForResponse: Boolean by mutableStateOf(false)

  /** Whether the chat input area currently has focus. */
  var isFocused: Boolean by mutableStateOf(false)

  /** Whether the permission selector dropdown is visible. */
  var isMenuVisible: Boolean by mutableStateOf(false)

  /** Whether the attachment details panel is expanded. */
  var isExpanded: Boolean by mutableStateOf(false)

  /** Whether the "add context" popup menu is visible. */
  var showAddMenu: Boolean by mutableStateOf(false)

  /**
   * The currently selected permission level, stored as the wire-format
   * string the server understands (`"read_only"`, `"edit"`, or
   * `"agent"`). The UI label is a separate concern, looked up in
   * [gradum.idea.chat.ui.input.permissionLabel] from this value.
   */
  var selectedPermission: String by mutableStateOf(PermissionMode.READONLY)

  /**
   * The ToolMode string sent to the server. Derived from
   * [selectedPermission] so the two cannot diverge: the only way to
   * change what the server sees is to change [selectedPermission], and
   * the two are always equal. Allowed values:
   *
   * - `"read_only"` — only `read_file`, `explore_project`, `run_cmd`.
   * - `"edit"` — read-only set plus `edit_file` / `save_file`,
   *   no task planning (`to_do` / `finish_to_do_item` are blocked).
   * - `"agent"` — every Skill is exposed.
   * - `"debug"` — bypasses the LLM entirely; reads the focused editor file
   *   and renders it directly as a Markdown preview.
   */
  val toolMode: String get() = selectedPermission

  /** HTTP client for communicating with the Gradum backend server. */
  val apiClient: GradumApiClient = GradumApiClient()

  /** All discovered LLM models from nearby local servers. */
  val models: SnapshotStateList<ModelInfo> = mutableStateListOf()

  /** Models the user has pinned for quick access. */
  val pinnedModels: SnapshotStateList<ModelInfo> = mutableStateListOf()

  /**
   * Server-side recommendation for auto-select mode. Refreshed on every
   * successful `/models` response. Null only when the server did not
   * include the field (older build) or when no models are discoverable.
   */
  var recommendedModel: ModelInfo? by mutableStateOf(null)

  /** The currently selected model, or `null` when no model is selectable yet. */
  var selectedModel: ModelInfo? by mutableStateOf(null)

  /**
   * True when the user picked "Auto" from the model menu (or when a
   * previous manual selection disappeared, and we fell back to auto).
   * In both cases [selectedModel] is the server's recommendation rather
   * than a user-chosen entry.
   */
  var isAutoSelected: Boolean by mutableStateOf(false)

  /** Whether models have been loaded from the server at least once. */
  var modelsLoaded: Boolean by mutableStateOf(false)

  /** Random indices for the quick-start suggestion categories. */
  var suggestionVariants: List<Int> by mutableStateOf(List(4) { Random.nextInt(5) })

  /** Whether the maximum number of file attachments (5) has been reached. */
  val isAttachmentLimitReached: Boolean
    get() = attachedFiles.size >= MAX_ATTACHMENTS

  /** Whether the pending message queue is full (max 2 queued messages). */
  val isPendingQueueFull: Boolean
    get() = pendingMessages.size >= MAX_PENDING_MESSAGES

  /** The coroutine Job for the current sendMessage operation, used for cancellation. */
  var currentJob: Job? by mutableStateOf(null)

  /** The session ID returned by the server, used for stop requests. */
  var sessionId: String? by mutableStateOf(null)

  /**
   * The client-generated conversation session id (`yyyyMMdd-HHmmss-xxxxxx`).
   *
   * Distinct from [sessionId] (the server's agent-session id used for stop):
   * this one scopes model context on the server (`.gradum/sessions/<id>/`)
   * and names the local `conversation.md` transcript. Null until the first
   * message of a session is sent.
   */
  var activeSessionId: String? by mutableStateOf(null)

  /** Title of the active conversation (first user message, truncated). */
  var currentSessionTitle: String by mutableStateOf("")

  /** All saved sessions, most recently updated first (Welcome "Recent Chats"). */
  val sessions: SnapshotStateList<SessionMeta> = mutableStateListOf()

  /** In-flight [refreshSessions] scan; canceled before starting a newer one. */
  private var sessionRefreshJob: Job? = null

  /** Whether the Welcome screen is in "merge sessions" selection mode. */
  var isMergeModeActive: Boolean by mutableStateOf(false)

  /** Session ids ticked on the manage board (merge / batch delete / rename). */
  val mergeSelection: SnapshotStateList<String> = mutableStateListOf()

  /**
   * Lazily created transcript store, rooted at the current project's base
   * path. Null until [project] is set by the tool-window factory.
   */
  private val chatStore: ChatSessionStore?
    get() = project?.basePath?.let { ChatSessionStore(Path.of(it)) }

  /** Current phase label shown during sending (e.g. "Synthesizing...", "Distilling..."). */
  var sendingPhase: String by mutableStateOf("")

  /** Background job for periodic model polling. */
  private var pollingJob: Job? = null

  /**
   * Resets the entire session to its initial state.
   *
   * Persists the current conversation (if any messages exist) to its
   * session transcript before clearing, so "New Chat" truly starts a fresh
   * session — both in the UI and in the server's per-session model context.
   * Then clears all messages, attachments, pending items, and the input
   * field, and assigns a brand-new [activeSessionId] for the next
   * conversation.
   *
   * Called when the user clicks "New Chat" or when the session needs to be
   * discarded and started fresh.
   */
  fun reset() {
    saveCurrentSession()
    exitMergeMode()
    val job = currentJob
    currentJob = null
    if (job != null) {
      try {
        job.cancel(CancellationException("Gradum: reset session"))
      } catch (throwable: Throwable) {
        log.warn("Failed to cancel current job on reset", throwable)
      }
    }
    sendingPhase = ""
    sessionId = null
    activeSessionId = chatStore?.let { ChatSessionStore.nextSessionId() }
    currentSessionTitle = ""
    isSending = false
    hasSentMessage = false
    messages.clear()
    attachedFiles.clear()
    pendingMessages.clear()
    textState.edit { delete(0, length) }
    refreshSessions()
  }

  /**
   * Saves the current in-memory conversation to its session transcript
   * (only when it has at least one message and a session id is available).
   */
  private fun saveCurrentSession() {
    if (messages.isEmpty()) return
    val sessionStore: ChatSessionStore = chatStore ?: return
    val targetSessionId: String = activeSessionId ?: ChatSessionStore.nextSessionId().also { activeSessionId = it }
    val createdAt: Long = messages.firstOrNull { it.isUserMessage }?.timestamp ?: System.currentTimeMillis()
    val modelName: String = messages.lastOrNull()?.modelName.orEmpty()
    // Keep an already-set title (a resumed or merged session keeps its
    // persisted/auto-generated name); only derive from the first message for
    // a brand-new conversation whose title has not been assigned yet.
    val sessionTitle: String = currentSessionTitle.ifBlank { ChatTranscript.titleFor(messages) }
    currentSessionTitle = sessionTitle
    sessionStore.saveSession(
      SessionMeta(
        sessionId = targetSessionId,
        title = sessionTitle,
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis(),
        modelName = modelName
      ),
      messages.toList()
    )
  }

  /**
   * Re-scans [ChatSessionStore.listSessions] into [sessions] (most recent first).
   *
   * The disk scan runs on [Dispatchers.IO] so tool-window creation and
   * new-session actions never block the UI thread. The previous in-flight
   * scan is canceled first so a slower, older result can never overwrite
   * a newer one.
   */
  fun refreshSessions() {
    val sessionStore: ChatSessionStore = chatStore ?: return
    val coroutineScope: CoroutineScope? = scope
    if (coroutineScope == null) {
      // No UI scope yet (tool-window init runs before the Compose tab);
      // fall back to a synchronous scan — headers-only reads keep it cheap.
      sessions.clear()
      sessions.addAll(sessionStore.listSessions())
      return
    }
    // Calls the non-synthetic `cancel(CancellationException)` overload on
    // purpose: plain `cancel()` compiles to the `cancel$default` bridge, which
    // is missing in the IDE's coroutines rebuild and throws NoSuchMethodError.
    sessionRefreshJob?.cancel(CancellationException("Gradum: refresh sessions"))
    sessionRefreshJob = coroutineScope.launch {
      val listedSessions: List<SessionMeta> = withContext(Dispatchers.IO) {
        sessionStore.listSessions()
      }
      sessions.clear()
      sessions.addAll(listedSessions)
    }
  }

  /**
   * Enters merge mode: shows the full session board (Welcome hides everything
   * else) and clears any previous selection.
   */
  fun enterMergeMode() {
    isMergeModeActive = true
    mergeSelection.clear()
  }

  /** Leaves merge mode unconditionally, dropping the current selection. */
  fun exitMergeMode() {
    isMergeModeActive = false
    mergeSelection.clear()
  }

  /**
   * Ticks or unticks [sessionId] on the manage board. Any number of sessions
   * may be selected at once; the enabled bottom actions depend on the count.
   */
  fun toggleMergeSelection(sessionId: String) {
    if (sessionId in mergeSelection) {
      mergeSelection.remove(sessionId)
    } else {
      mergeSelection.add(sessionId)
    }
  }

  /**
   * Creates a single merged session from every selected session (interleaved
   * by message timestamp) and opens it.
   *
   * @return `true` when all sources merged successfully and the merged
   *   session was opened.
   */
  suspend fun mergeSelectedSessions(): Boolean {
    if (mergeSelection.size < MIN_MERGE_SESSIONS) return false
    val sessionStore: ChatSessionStore = chatStore ?: return false
    val resultTitle: String = nextMergeTitle()
    withContext(Dispatchers.IO) {
      sessionStore.mergeSessions(mergeSelection.toList(), resultTitle)
    } ?: return false
    mergeSelection.clear()
    refreshSessions()
    return true
  }

  /**
   * Auto-names a merge result `Merged conversation <n>` (`gradum.merge.titled`),
   * continuing the highest existing number so repeated merges produce
   * "合并后的对话 1", "合并后的对话 2", … without collisions.
   */
  private fun nextMergeTitle(): String {
    val base: String = message("gradum.merge.titled")
    val numberedTitlePattern = Regex("^${Regex.escape(base)}\\s+(\\d+)$")
    var maxIndex = 0
    sessions.forEach { sessionMeta ->
      val match: MatchResult? = numberedTitlePattern.matchEntire(sessionMeta.title)
      val index: Int = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
      if (index > maxIndex) maxIndex = index
    }
    return "$base ${maxIndex + 1}"
  }

  /**
   * Renames a saved session (persisted to its transcript header) and refreshes
   * the list. When the renamed session is the active conversation its
   * [currentSessionTitle] follows along.
   *
   * @return `true` when the rename was persisted.
   */
  suspend fun renameSession(sessionId: String, newTitle: String): Boolean {
    val sessionStore: ChatSessionStore = chatStore ?: return false
    val renamed: Boolean = withContext(Dispatchers.IO) {
      sessionStore.renameSession(sessionId, newTitle)
    }
    if (!renamed) return false
    if (sessionId == activeSessionId) currentSessionTitle = newTitle.trim()
    refreshSessions()
    return true
  }

  /**
   * Deletes several sessions at once (transcript directories locally, server
   * context via `POST /session/delete` for each), then refreshes the list.
   */
  fun deleteSessions(sessionIds: List<String>) {
    sessionIds.forEach { sessionId -> deleteSession(sessionId) }
  }

  /**
   * Switches the UI to a saved session: loads its transcript into
   * [messages] and restores [activeSessionId] / [currentSessionTitle].
   *
   * @return `true` when the session existed and was restored.
   */
  suspend fun switchSession(targetSessionId: String): Boolean {
    if (isSending) return false
    val sessionStore: ChatSessionStore = chatStore ?: return false
    val loadedTranscript: ChatTranscript.ParsedTranscript = withContext(Dispatchers.IO) {
      sessionStore.loadSession(targetSessionId)
    } ?: return false

    val job = currentJob
    currentJob = null
    job?.cancel(CancellationException("Gradum: switch session"))
    sessionId = null
    isSending = false
    isWaitingForResponse = false
    sendingPhase = ""
    messages.clear()
    messages.addAll(loadedTranscript.messages)
    activeSessionId = targetSessionId
    currentSessionTitle = loadedTranscript.sessionMeta.title
    hasSentMessage = loadedTranscript.messages.isNotEmpty()
    attachedFiles.clear()
    pendingMessages.clear()
    textState.edit { delete(0, length) }
    return true
  }

  /**
   * Deletes a session locally (transcript directory) and cascades to the
   * server (`POST /session/delete`) so its model context is removed too.
   *
   * If the deleted session is the active one, returns to a fresh Welcome.
   */
  fun deleteSession(targetSessionId: String) {
    val sessionStore: ChatSessionStore = chatStore ?: return
    sessionStore.deleteSession(targetSessionId)

    val projectRoot: String? = project?.basePath
    if (projectRoot != null) {
      scope?.launch {
        apiClient.deleteSession(projectRoot, targetSessionId)
      }
    }

    sessions.removeAll { it.sessionId == targetSessionId }
    if (activeSessionId == targetSessionId) {
      // The active conversation is gone; return to a fresh Welcome without
      // letting reset()'s saveCurrentSession resurrect the deleted directory.
      activeSessionId = null
      messages.clear()
      reset()
    }
    refreshSessions()
  }

  /**
   * Loads a local Markdown file directly for debug preview.
   * Used in debug mode to render focused .md file without calling LLM.
   */
  fun loadDebugMarkdown(content: String) {
    hasSentMessage = true
    val message = ChatMessage(
      content = "",
      role = "debug",
      modelName = message("gradum.debug.model.name"),
    )
    val updatedMessage = message.appendEvent(ChatEvent.Response(content))
    messages.add(updatedMessage)
    isSending = false
  }

  /**
   * Loads the list of available LLM models from the Gradum server.
   *
   * The server probes local LLM providers (Ollama, LM Studio, vLLM, LocalAI)
   * and returns a consolidated list. On success, updates [models] and
   * auto-selects the first model if none is currently selected. On failure,
   * clears the model list and sets [modelsLoaded] to `false`.
   */
  suspend fun loadModels() {
    try {
      val json: String = apiClient.getModels()
      val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(json)
      applyModelList(response.models, response.recommended)
    } catch (iOException: IOException) {
      log.warn("Failed to load models from ${apiClient.baseUrl}", iOException)
      models.clear(); modelsLoaded = false
    }
  }

  private fun applyModelList(newModels: List<ModelInfo>, recommended: ModelInfo? = null) {
    val healthyModels: List<ModelInfo> = newModels.filter { it.available }
    models.clear()
    models.addAll(healthyModels)
    modelsLoaded = true
    recommendedModel = recommended?.takeIf { it.available }

    if (models.isEmpty()) {
      selectedModel = null
      isAutoSelected = false
      pinnedModels.clear()
      return
    }

    val selectedEntry = selectedModel
    when {
      selectedEntry == null -> {
        selectedModel = recommendedModel ?: models.first()
        isAutoSelected = true
      }

      models.none { selectedEntry.sameAs(it) } -> {
        selectedModel = recommendedModel ?: models.first()
        isAutoSelected = true
      }
      // else: the user's prior pick is still present; leave it.
    }

    pinnedModels.removeAll { pinned ->
      models.none { pinned.sameAs(it) }
    }
  }

  /**
   * Starts background polling for model availability so the plugin can
   * discover LLM servers that start after it loads. Runs on
   * [Dispatchers.IO]; interval is [POLL_INTERVAL_MS].
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun startModelPolling(scope: CoroutineScope) {
    stopModelPolling()

    // tickerFlow ──flatMapLatest──▶ fetchModelsOnce ──▶ collect
    // flatMapLatest cancels any in-flight fetch when the next tick arrives.
    pollingJob = scope.launch {
      tickerFlow()
        .flatMapLatest { fetchModelsOnce() }
        .catch { exception ->
          log.warn("Model polling stream error: ${exception.message}", exception)
        }
        .collect { json ->
          runCatching {
            val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(json)
            val newModels: List<ModelInfo> = response.models
            val newRecommended: ModelInfo? = response.recommended
            val modelsChanged: Boolean = newModels.size != models.size ||
              newModels.map { it.name }.toSet() != models.map { it.name }.toSet()
            val recommendedChanged: Boolean =
              newRecommended?.name != recommendedModel?.name ||
                newRecommended?.serverName != recommendedModel?.serverName
            if (modelsChanged || recommendedChanged) {
              applyModelList(newModels, newRecommended)
              log.info("Model scanState updated: ${newModels.size} models, recommended = ${newRecommended?.name ?: "<none>"}")
            }
          }.onFailure { exception ->
            log.debug("Failed to decode /models response, skipping this tick", exception)
          }
        }
    }
  }

  private fun tickerFlow(): Flow<Unit> = flow {
    while (currentCoroutineContext().isActive) {
      delay(POLL_INTERVAL_MS.milliseconds)
      emit(Unit)
    }
  }

  /** Single-shot /models fetch; errors are swallowed so the ticker just fires again. */
  private fun fetchModelsOnce(): Flow<String> = flow {
    val json: String = try {
      withContext(Dispatchers.IO) { apiClient.getModels() }
    } catch (exception: CancellationException) {
      // Structured concurrency: never swallow cancellation.
      throw exception
    } catch (exception: Exception) {
      log.debug("Polling /models failed: ${exception.message}")
      return@flow
    }
    emit(json)
  }

  /**
   * Stops the background model polling job if it is running.
   *
   * Calls the non-synthetic `Job.cancel(CancellationException)` overload on
   * purpose: `job.cancel()` compiles to the synthetic `cancel$default`
   * bridge, which may be missing in the IDE's coroutines rebuild
   * (`1.10.2-intellij-1`) and would throw `NoSuchMethodError` at runtime.
   */
  fun stopModelPolling() {
    val job = pollingJob
    pollingJob = null
    if (job != null) {
      try {
        job.cancel(CancellationException("Gradum: stop model polling"))
      } catch (throwable: Throwable) {
        log.warn("Failed to cancel polling job", throwable)
      }
    }
  }

  /**
   * Stops the current active session.
   *
   * Cancels the local coroutine Job and sends a stop request to the server
   * to abort the agent's execution.
   */
  suspend fun stopSession() {
    val job = currentJob
    currentJob = null
    if (job != null) {
      try {
        job.cancel(CancellationException("Gradum: stop session"))
      } catch (throwable: Throwable) {
        log.warn("Failed to cancel current job on stopSession", throwable)
      }
    }

    val currentSessionId = sessionId
    if (currentSessionId != null) {
      try {
        apiClient.stopSession(currentSessionId)
      } catch (exception: Exception) {
        log.warn("Failed to send stop request for session $currentSessionId", exception)
      }
      sessionId = null
    }
    sendingPhase = ""
    isSending = false
    isWaitingForResponse = false
    processPendingQueue()
  }

  /**
   * Sends a user message to the Gradum server and processes the streaming response.
   *
   * This method:
   * 1. Sends the message via [GradumApiClient] to the `POST /events` endpoint.
   * 2. Collects NDJSON event lines from the server's streaming response.
   * 3. Updates the assistant's message content in real-time as events arrive.
   * 4. Handles errors gracefully by displaying error messages in the conversation.
   * 5. Processes the pending message queue when the session ends.
   *
   * Supported event types:
   * - `response` — Appends LLM text content to the assistant message.
   * - `thinking` — Ignored for now (reserved for future reasoning display).
   * - `tool_call` — Displays a tool invocation indicator.
   * - `error` — Appends an error message to the assistant message.
   * - `session_end` — Marks the response as complete and processes the queue.
   *
   * @param userMessage The text content of the user's message.
   */
  suspend fun sendMessage(
    userMessage: String, attachments: List<AttachedContext> = emptyList(), contextPath: String = "",
    toolCallXml: String? = null
  ) {
    val modelConfig: Map<String, String> = buildModelConfig()
    val attachmentPaths: List<String> = attachments.filterIsInstance<AttachedFile>().map { it.file.path }
    val textAttachments: List<AttachedText> = attachments.filterIsInstance<AttachedText>()
    val prefix: String = buildString {
      if (contextPath.isNotEmpty()) append("<Context path=\"$contextPath\"/>")
      if (attachmentPaths.isNotEmpty()) append("<Attachments paths=\"${attachmentPaths.joinToString(", ")}\"/>")
      textAttachments.forEach { append("<Context text=\"${it.content}\"/>") }
    }

    val systemRule = """
        <Rule>
          - Answer in English by default. Use another language only if the user asks.
          - No use emojis in anywhere (eg: Code or text).
          - Use Mermaid for diagrams. Do not use text-based drawings.
          - Give complete (600+ words), clear, and knowledgeable answers. Avoid short or vague replies.
        </Rule>
    """.trimIndent()

    val messageWithHint = "${prefix}${userMessage}\n\n$systemRule" +
      "\n\n${ThinkingPromptInjector.guideFor(thinkingLevel)}"
    // Validate server connectivity and model availability before sending.
    // The server may not be running yet, so retry with exponential backoff
    // (2s, 4s, 8s, ...) up to MAX_CONNECT_ATTEMPTS times before surfacing a
    // connection error. The sweep-light sending phase stays visible so the
    // UI reads as "still trying" rather than failing instantly.
    sendingPhase = message("gradum.phase.synthesizing")
    val validationStart: Long = System.currentTimeMillis()
    var response: ModelsListResponse? = null
    var lastFailure: Exception? = null
    for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
      try {
        val modelsJson: String = apiClient.getModels()
        response = jsonFormat.decodeFromString<ModelsListResponse>(modelsJson)
        break
      } catch (exception: Exception) {
        log.warn(
          "Model validation failed for ${apiClient.baseUrl} (attempt $attempt/" +
            "$MAX_CONNECT_ATTEMPTS)",
          exception
        )
        lastFailure = exception
        if (attempt < MAX_CONNECT_ATTEMPTS) {
          sendingPhase = message("gradum.phase.connecting", attempt, MAX_CONNECT_ATTEMPTS - 1)
          delay((CONNECT_BACKOFF_MS shl (attempt - 1)).milliseconds)
        }
      }
    }
    val currentModel: ModelInfo? = selectedModel

    if (response != null && currentModel != null && response.models.none { it.name == currentModel.name }) {
      val assistantIndex: Int = messages.lastIndex

      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        messages[assistantIndex] = messages[assistantIndex].appendEvent(
          ChatEvent.Error(
            code = ErrorCode.CLIENT_ERROR.code,
            message = "Model ${currentModel.name} is no longer available"
          )
        )
      }
      isSending = false
      sendingPhase = ""
      isWaitingForResponse = false
      processPendingQueue()
      return
    }

    if (response == null) {
      log.warn("Cannot reach server at ${apiClient.baseUrl}", lastFailure)
      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        messages[assistantIndex] = messages[assistantIndex].appendEvent(
          ChatEvent.Error(
            code = ErrorCode.CLIENT_ERROR.code,
            message = "Cannot reach server at ${apiClient.baseUrl}"
          )
        )
      }
      sendingPhase = ""
      isSending = false
      isWaitingForResponse = false
      processPendingQueue()
      return
    }

    // Ensure the "Sending" animation is visible for at least MIN_SENDING_MS.
    val elapsed: Long = System.currentTimeMillis() - validationStart
    if (elapsed < MIN_SENDING_MS) delay((MIN_SENDING_MS - elapsed).milliseconds)

    try {
      // Server-side Agent resets per request. `context.json` persists history
      // across calls, so loading it keeps the conversation continuous.
      val loadContext = true
      sendingPhase = message("gradum.phase.distilling")

      // First message of a conversation: assign a session id so the model
      // context stays scoped to this conversation on the server and the
      // transcript has a home once the turn completes.
      if (activeSessionId == null) activeSessionId = ChatSessionStore.nextSessionId()

      // Extract images pre-serialization. Validate against current model —
      // stale vision attachments may remain after switching to a text-only model.
      val imageAttachments: List<GradumApiClient.ApiImageAttachment> =
        attachments.filterIsInstance<AttachedImage>()
          .map { attachment ->
            GradumApiClient.ApiImageAttachment(
              mime = attachment.mime,
              data = attachment.data,
              filename = attachment.originalName
            )
          }

      if (imageAttachments.isNotEmpty() && selectedModel?.attachment != true) {
        val userIndex = messages.lastIndex
        if (userIndex >= 0 && messages[userIndex].isUserMessage)
          messages.removeAt(userIndex)

        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
          messages[assistantIndex] = messages[assistantIndex].appendEvent(
            ChatEvent.Error(code = ErrorCode.CLIENT_ERROR.code, message = message("gradum.model.no.vision"))
          )
        }
        isSending = false
        sendingPhase = ""
        return
      }

      apiClient.sendMessage(
        message = messageWithHint,
        modelName = selectedModel?.name,
        modelParams = modelConfig,
        loadContext = loadContext,
        toolMode = toolMode,
        projectRoot = project?.basePath,
        imageAttachments = imageAttachments,
        toolCallXml = toolCallXml,
        sessionId = activeSessionId
      ).catch { exception ->
        if (exception is CancellationException) {
          val assistantIndex: Int = messages.lastIndex
          if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
            messages[assistantIndex] = messages[assistantIndex].appendEvent(
              ChatEvent.Error("", code = ErrorCode.INTERRUPTED.code)
            )
          }
          isSending = false
          sendingPhase = ""
          return@catch
        }
        log.warn("Failed to send message to ${apiClient.baseUrl}", exception)
        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
          messages[assistantIndex] = messages[assistantIndex].appendEvent(
            ChatEvent.Error(
              exception.message ?: "Connection failed", code = ErrorCode.CLIENT_ERROR.code
            )
          )
        }

        isSending = false
        sendingPhase = ""
        processPendingQueue()
      }.collect { event: JsonObject ->
        // Per-line parse errors are handled by the client and skipped.
        // This collector only receives valid JsonObject emissions.
        val messageType: String = event["type"]?.jsonPrimitive?.content ?: return@collect
        val payload: JsonObject? = event["data"]?.jsonObject

        when (messageType) {
          "session_start" -> {
            sessionId = event["sessionId"]?.jsonPrimitive?.content
          }

          "response" -> {
            isWaitingForResponse = false; handleResponseEvent(payload)
          }

          "thinking" -> {
            isWaitingForResponse = false; handleThinkingEvent(payload)
          }

          "tool_call" -> handleToolCallEvent(payload)

          "tool_expect_mismatch" -> {
            isWaitingForResponse = false; handleToolExpectMismatch(payload)
          }

          "error" -> handleErrorEvent(payload)

          "session_end" -> {
            isSending = false; sendingPhase = ""
            isWaitingForResponse = false; currentJob = null; sessionId = null
            saveCurrentSession()
            processPendingQueue()
          }
        }
      }
    } catch (exception: Exception) {
      if (exception is CancellationException) {
        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
          messages[assistantIndex] = messages[assistantIndex].appendEvent(
            ChatEvent.Error("", code = ErrorCode.INTERRUPTED.code)
          )
        }
        isSending = false; sendingPhase = ""; isWaitingForResponse = false
        return
      }
      log.warn("Streaming interrupted for ${apiClient.baseUrl}", exception)
      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        messages[assistantIndex] = messages[assistantIndex].appendEvent(
          ChatEvent.Error(
            exception.message ?: "Streaming interrupted", code = ErrorCode.CLIENT_ERROR.code
          )
        )
      }
      isSending = false
      sendingPhase = ""
      isWaitingForResponse = false
      processPendingQueue()
    }
  }

  private fun handleResponseEvent(responseData: JsonObject?) {
    if (responseData == null) return

    val assistantIndex: Int = messages.lastIndex
    if (assistantIndex < 0 || messages[assistantIndex].isUserMessage)
      return

    var updatedMessage = messages[assistantIndex]

    val responseContent: String = responseData["content"]?.jsonPrimitive?.content ?: ""
    if (responseContent.isNotEmpty()) {
      updatedMessage = updatedMessage.appendEvent(ChatEvent.Response(responseContent))
    }

    val totalTokens = responseData["totalTokens"]?.jsonPrimitive?.intOrNull ?: 0
    if (totalTokens > 0) {
      updatedMessage = updatedMessage.copy(
        tokenUsage = TokenUsage(
          promptTokens = responseData["promptTokens"]?.jsonPrimitive?.intOrNull ?: 0,
          completionTokens = responseData["completionTokens"]?.jsonPrimitive?.intOrNull ?: 0,
          totalTokens = totalTokens,
        )
      )
    }

    messages[assistantIndex] = updatedMessage
  }

  private fun handleThinkingEvent(data: JsonObject?) {
    val content: String = data?.get("content")?.jsonPrimitive?.content ?: return

    val assistantIndex: Int = messages.lastIndex

    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage)
      messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.Thinking(content))
  }

  private fun handleToolCallEvent(data: JsonObject?) {
    try {
      val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: "unknown"
      sendingPhase = message("gradum.phase.weaving")

      val toolAlias = data?.get("alias")?.jsonPrimitive?.content ?: toolName
      val toolCallId = data?.get("toolCallId")?.jsonPrimitive?.content ?: ""
      val callSuccess = data?.get("success")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
      val toolResultString = data?.get("result")?.toString() ?: ""
      val callArguments = parseArguments(data?.get("arguments")?.jsonObject)

      val toolCall = ToolCallInfo(
        toolName = toolName,
        alias = toolAlias,
        toolCallId = toolCallId,
        success = callSuccess,
        result = toolResultString,
        arguments = callArguments
      )

      val assistantIndex: Int = messages.lastIndex
      if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
        messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(toolCall))
      }
    } catch (exception: Exception) {
      log.warn("Failed to parse tool_call event", exception)
    }
  }

  private fun handleToolExpectMismatch(data: JsonObject?) {
    val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: "unknown"
    val expectSuccess: String =
      data?.get("expectSuccess")?.jsonPrimitive?.content ?: "?"
    val actualSuccess: String =
      data?.get("actualSuccess")?.jsonPrimitive?.content ?: "?"

    val message: String =
      "Playback assertion mismatch on tool '$toolName': expected success=$expectSuccess, " +
        "actual=$actualSuccess"
    val assistantIndex: Int = messages.lastIndex
    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
      messages[assistantIndex] = messages[assistantIndex].appendEvent(
        ChatEvent.Error(message, code = "TOOL_EXPECT_MISMATCH")
      )
    }
  }

  private fun handleErrorEvent(data: JsonObject?) {
    val rawMessage: String = data?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
    val errorCode: String = data?.get("code")?.jsonPrimitive?.content ?: ""
    val errorToolName: String = data?.get("tool")?.jsonPrimitive?.content ?: ""
    val assistantIndex: Int = messages.lastIndex

    if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
      val updatedMessage = messages[assistantIndex].updateLastError(
        friendlyErrorMessage(errorCode),
        errorDetailText(errorCode, rawMessage, errorToolName)
      )
      if (updatedMessage !== messages[assistantIndex])
        messages[assistantIndex] = updatedMessage
      else
        messages[assistantIndex] = messages[assistantIndex].appendEvent(
          ChatEvent.Error(rawMessage, errorCode, errorToolName)
        )
    }
  }

  private fun processPendingQueue() {
    if (pendingMessages.isNotEmpty()) {
      val next: PendingMessage = pendingMessages.removeFirst()
      val displayName: String = selectedModel?.name ?: "Auto"
      val providerName: String = selectedModel?.provider ?: ""
      val serverLabel: String = selectedModel?.serverName ?: ""

      messages.add(ChatMessage(role = "user", content = next.content, attachments = next.attachments))
      messages.add(
        ChatMessage(
          role = "assistant",
          content = "",
          modelName = displayName,
          provider = providerName,
          serverName = serverLabel
        )
      )

      isSending = true
      hasSentMessage = true
      isWaitingForResponse = true
      currentJob = scope?.launch {
        sendMessage(next.content, next.attachments, "")
      }
    }
  }

  private fun buildModelConfig(): Map<String, String> {
    val requestParams = mutableMapOf<String, String>()

    if (isAutoSelected) {
      requestParams["provider"] = "ollama"
    } else {
      selectedModel?.let { model ->
        if (model.provider.isNotBlank()) requestParams["provider"] = model.provider
        if (model.server.isNotBlank()) requestParams["baseUrl"] = model.server
      }
    }
    return requestParams
  }

  private fun parseArguments(jsonObject: JsonObject?): Map<String, Any> {
    if (jsonObject == null) return emptyMap()

    return try {
      jsonObject.mapValues { (_, jsonElement) -> convertJsonElement(jsonElement) ?: "" }
    } catch (exception: Exception) {
      log.warn("Failed to parse tool arguments", exception)
      emptyMap()
    }
  }

  private fun convertJsonElement(jsonElement: JsonElement): Any? {
    return when (jsonElement) {
      is JsonObject -> jsonElement.mapValues { (_, value) -> convertJsonElement(value) }
      is JsonArray -> jsonElement.map { convertJsonElement(it) }
      is JsonPrimitive -> {
        when {
          jsonElement.isString -> jsonElement.content
          jsonElement.booleanOrNull != null -> jsonElement.boolean
          jsonElement.intOrNull != null -> jsonElement.int
          jsonElement.longOrNull != null -> jsonElement.long
          jsonElement.doubleOrNull != null -> jsonElement.double
          else -> jsonElement.content
        }
      }

      is JsonNull -> null
    }
  }

  companion object {
    private val jsonFormat: Json = Json { ignoreUnknownKeys = true }
    const val MAX_ATTACHMENTS: Int = 10
    const val MAX_PENDING_MESSAGES: Int = 2

    /** Minimum number of sessions a merge combines. */
    const val MIN_MERGE_SESSIONS: Int = 2

    /** Minimum milliseconds to display the "Sending" animation before the request fires. */
    const val MIN_SENDING_MS: Long = 400

    /** Interval between model polling requests in milliseconds. */
    const val POLL_INTERVAL_MS: Long = 5_000

    /** Max connection retry attempts before surfacing a server error. */
    const val MAX_CONNECT_ATTEMPTS: Int = 6

    /** Base backoff delay in ms, doubled after each failed attempt (2s, 4s, 8s, ...). */
    const val CONNECT_BACKOFF_MS: Long = 2_000

    private val FOCUS_FILE_PATTERN: Regex = Regex("@focus")
    private val FILE_REF_PATTERN: Regex = Regex("""@file:(\S+)""")

    /**
     * Scans [text] for `@focusfile` and `@file:xxx` tags, replacing them with
     * XML-style `<Context>` and `<Attachments>` tags respectively.
     *
     * @param text The raw user message text.
     * @param focusedFilePath The absolute path of the currently focused editor file.
     * @param openFiles All files currently open in the editor.
     * @return A pair of (resolved text, whether any replacements were made).
     */
    fun resolveInlineTags(
      text: String, focusedFilePath: String, openFiles: List<VirtualFile>
    ): Pair<String, Boolean> {
      val hasFocusTag = focusedFilePath.isNotEmpty() && FOCUS_FILE_PATTERN.containsMatchIn(text)
      val hasFileReference = FILE_REF_PATTERN.containsMatchIn(text)

      if (!hasFocusTag && !hasFileReference) return text to false

      val resultBuffer = StringBuilder(text)
      var wasReplaced = false

      if (hasFocusTag) {
        val resolvedText = resultBuffer.replace(
          regex = FOCUS_FILE_PATTERN,
          replacement = "<Context path=\"$focusedFilePath\"/>"
        )

        resultBuffer.clear()
        resultBuffer.append(resolvedText)
        wasReplaced = true
      }

      if (hasFileReference) {
        FILE_REF_PATTERN.findAll(resultBuffer).toList().reversed().forEach { match ->
          val fileName: String = match.groupValues[1]
          val matchedFile: VirtualFile? = openFiles.find { it.name == fileName }
          if (matchedFile != null) {
            resultBuffer.replace(
              match.range.first,
              match.range.last + 1,
              "<Attachments paths=\"${matchedFile.path}\"/>"
            )
            wasReplaced = true
          }
        }
      }
      return resultBuffer.toString() to wasReplaced
    }
  }
}
