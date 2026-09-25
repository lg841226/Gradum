/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSession.kt  2026-09-26 00:24:56 Changed by gwy
 */

package gradum.idea.chat.state

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.PluginConfig
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.history.ChatSessionStore
import gradum.idea.chat.history.ChatTranscript
import gradum.idea.chat.history.SessionMeta
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.chat.ui.input.PermissionMode
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.PendingMessage
import gradum.idea.provider.ProviderCoordinator
import gradum.idea.provider.ProviderSettings
import gradum.idea.settings.AppearanceSettings
import gradum.idea.utils.GradumBundle.message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.PROJECT)
class GradumChatSession {

  internal val log: Logger = Logger.getInstance(GradumChatSession::class.java)
  internal val WEAVING_FADE_BUFFER_MS: Long = 300L

  var scope: CoroutineScope? = null
  var project: Project? = null

  val textState: TextFieldState = TextFieldState()
  val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()
  val attachedFiles: SnapshotStateList<AttachedContext> = mutableStateListOf()
  val pendingMessages: SnapshotStateList<PendingMessage> = mutableStateListOf()

  /**
   * Request ids of ask cards the user has already answered. Held at session
   * scope (the project-scoped service survives panel refreshes) so an answered
   * card stays dropped from the layout instead of reappearing after a refresh.
   */
  val dismissedAskRequestIds: MutableState<Set<String>> = mutableStateOf(emptySet())

  /** Marks an ask card [requestId] as answered so it is removed everywhere. */
  fun dismissAsk(requestId: String) {
    dismissedAskRequestIds.value += requestId
  }

  var hasSentMessage: Boolean by mutableStateOf(false)

  private val _thinkingLevel = mutableStateOf(ThinkingLevel.MEDIUM)
  val thinkingLevel: ThinkingLevel get() = _thinkingLevel.value
  fun setThinkingLevel(level: ThinkingLevel) {
    if (level != _thinkingLevel.value) _thinkingLevel.value = level
  }

  var isSending: Boolean by mutableStateOf(false)
  var isWaitingForResponse: Boolean by mutableStateOf(false)
  var isFocused: Boolean by mutableStateOf(false)
  var isMenuVisible: Boolean by mutableStateOf(false)
  var isExpanded: Boolean by mutableStateOf(false)
  var showAddMenu: Boolean by mutableStateOf(false)
  var selectedPermission: String by mutableStateOf(PermissionMode.READONLY)

  init {
    try {
      val appearance = AppearanceSettings.getInstance().snapshot
      if (appearance.rememberPermission) selectedPermission = appearance.lastPermission
      if (appearance.rememberContext) isExpanded = appearance.lastContextEnabled
    } catch (_: Throwable) { /* no IntelliJ platform in unit tests */
    }
  }

  val toolMode: String get() = selectedPermission
  val apiClient: GradumApiClient = GradumApiClient()

  val models: SnapshotStateList<ModelInfo> = mutableStateListOf()
  val pinnedModels: SnapshotStateList<ModelInfo> = mutableStateListOf()
  var selectedModel: ModelInfo? by mutableStateOf(null)
  var modelsLoaded: Boolean by mutableStateOf(false)
  var suggestionVariants: List<Int> by mutableStateOf(List(4) { Random.nextInt(5) })

  val isAttachmentLimitReached: Boolean
    get() = attachedFiles.size >= MAX_ATTACHMENTS
  val isPendingQueueFull: Boolean
    get() = pendingMessages.size >= MAX_PENDING_MESSAGES

  var currentJob: Job? by mutableStateOf(null)
  internal var subAgentTimeoutJob: Job? = null
  var sessionId: String? by mutableStateOf(null)
  var activeSessionId: String? by mutableStateOf(null)
  var currentSessionTitle: String by mutableStateOf("")

  val sessions: SnapshotStateList<SessionMeta> = mutableStateListOf()
  private var sessionRefreshJob: Job? = null
  var isMergeModeActive: Boolean by mutableStateOf(false)
  val mergeSelection: SnapshotStateList<String> = mutableStateListOf()

  private val chatStore: ChatSessionStore?
    get() = project?.basePath?.let { ChatSessionStore(Path.of(it)) }

  var sendingPhase: String by mutableStateOf("")
  private var pollingJob: Job? = null
  private var probeRefreshJob: Job? = null
  val subAgentState: SubAgentState = SubAgentState()

  private fun clearConversationState() {
    messages.clear()
    attachedFiles.clear()
    pendingMessages.clear()
    textState.edit { delete(0, length) }
  }

  private fun clearSendState() {
    isSending = false
    isWaitingForResponse = false
    sendingPhase = ""
  }

  fun reset() {
    cleanupSubAgent()
    saveCurrentSession()
    exitMergeMode()
    val cancellableJob = currentJob
    currentJob = null
    if (cancellableJob != null) {
      try {
        cancellableJob.cancel(CancellationException("Gradum: reset session"))
      } catch (cancellationError: Throwable) {
        log.warn("Failed to cancel current job on reset", cancellationError)
      }
    }
    clearSendState()
    sessionId = null
    activeSessionId = chatStore?.let { ChatSessionStore.nextSessionId() }
    currentSessionTitle = ""
    hasSentMessage = false
    clearConversationState()
    refreshSessions()
  }

  internal fun saveCurrentSession() {
    if (messages.isEmpty()) return
    val sessionStore: ChatSessionStore = chatStore ?: return
    val targetSessionId: String = activeSessionId
      ?: ChatSessionStore.nextSessionId().also {
        activeSessionId = it
      }
    val createdAt: Long = messages.firstOrNull { it.isUserMessage }
      ?.timestamp ?: System.currentTimeMillis()
    val modelName: String = messages.lastOrNull()?.modelName.orEmpty()
    val sessionTitle: String = currentSessionTitle.ifBlank {
      ChatTranscript.titleFor(messages)
    }
    currentSessionTitle = sessionTitle
    sessionStore.saveSession(
      SessionMeta(
        title = sessionTitle,
        modelName = modelName,
        createdAt = createdAt,
        sessionId = targetSessionId,
        updatedAt = System.currentTimeMillis()
      ),
      messages.toList()
    )
  }

  fun refreshSessions() {
    val sessionStore: ChatSessionStore = chatStore ?: return
    val coroutineScope: CoroutineScope? = scope
    if (coroutineScope == null) {
      sessions.clear()
      sessions.addAll(elements = sessionStore.listSessions())
      return
    }
    sessionRefreshJob?.cancel(cause = CancellationException("Gradum, refresh sessions"))
    sessionRefreshJob = coroutineScope.launch {
      val listedSessions: List<SessionMeta> = withContext(Dispatchers.IO) {
        sessionStore.listSessions()
      }
      sessions.clear()
      sessions.addAll(elements = listedSessions)
    }
  }

  fun enterMergeMode() {
    isMergeModeActive = true
    mergeSelection.clear()
  }

  fun exitMergeMode() {
    isMergeModeActive = false
    mergeSelection.clear()
  }

  fun toggleMergeSelection(sessionId: String) {
    if (sessionId in mergeSelection) mergeSelection.remove(element = sessionId)
    else mergeSelection.add(sessionId)
  }

  suspend fun mergeSelectedSessions(): Boolean {
    if (mergeSelection.size < MIN_MERGE_SESSIONS) return false
    val sessionStore: ChatSessionStore = chatStore ?: return false
    val resultTitle: String = nextMergeTitle()
    withContext(Dispatchers.IO) {
      sessionStore.mergeSessions(sessionIds = mergeSelection.toList(), resultTitle)
    } ?: return false
    mergeSelection.clear()
    refreshSessions()
    return true
  }

  private fun nextMergeTitle(): String {
    val base: String = message("gradum.merge.titled")
    val numberedTitlePattern = Regex(pattern = "^${Regex.escape(literal = base)}\\s+(\\d+)$")
    var maxIndex = 0
    sessions.forEach { sessionMeta: SessionMeta ->
      val match: MatchResult? = numberedTitlePattern.matchEntire(input = sessionMeta.title)
      val index: Int = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
      if (index > maxIndex) maxIndex = index
    }
    return "$base ${maxIndex + 1}"
  }

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

  fun deleteSessions(sessionIds: List<String>) {
    sessionIds.forEach { sessionId: String -> deleteSession(targetSessionId = sessionId) }
  }

  suspend fun switchSession(targetSessionId: String): Boolean {
    if (isSending) return false
    val sessionStore: ChatSessionStore = chatStore ?: return false
    val loadedTranscript: ChatTranscript.ParsedTranscript = withContext(Dispatchers.IO) {
      sessionStore.loadSession(targetSessionId)
    } ?: return false

    val job = currentJob
    currentJob = null
    job?.cancel(cause = CancellationException("Gradum: switch session"))
    sessionId = null
    clearSendState()
    if (activeSessionId != null && messages.isNotEmpty()) saveCurrentSession()

    clearConversationState()
    messages.addAll(elements = loadedTranscript.messages)
    activeSessionId = targetSessionId
    currentSessionTitle = loadedTranscript.sessionMeta.title
    hasSentMessage = loadedTranscript.messages.isNotEmpty()
    return true
  }

  fun deleteSession(targetSessionId: String) {
    val sessionStore: ChatSessionStore = chatStore ?: return
    sessionStore.deleteSession(targetSessionId)
    val projectRoot: String? = project?.basePath
    if (projectRoot != null) {
      scope?.launch { apiClient.deleteSession(projectRoot, targetSessionId) }
    }
    sessions.removeAll { it.sessionId == targetSessionId }
    if (activeSessionId == targetSessionId) {
      activeSessionId = null
      messages.clear()
      reset()
    }
    refreshSessions()
  }

  fun deleteMessage(userMessageIndex: Int) {
    if (userMessageIndex !in messages.indices) return
    if (!messages[userMessageIndex].isUserMessage) return
    val withdrawn: ChatMessage = messages[userMessageIndex]
    val withdrawnContent: String = withdrawn.content
    val messageId: String = withdrawn.messageId
    val assistantMessageIndex: Int? = (userMessageIndex + 1 until messages.size)
      .firstOrNull { !messages[it].isUserMessage }
    val messagesToRemove: Int =
      if (assistantMessageIndex != null)
        assistantMessageIndex - userMessageIndex + 1
      else 1
    repeat(times = messagesToRemove) { messages.removeAt(userMessageIndex) }
    if (messages.isEmpty()) {
      clearSendState()
      hasSentMessage = false
      clearConversationState()
    }
    // Refill the withdrawn text back into the input so the user can re-edit
    // and re-send; it is prepended so any freshly typed draft is preserved.
    // Done after the empty-conversation branch above because
    // clearConversationState() resets the text state.
    if (withdrawnContent.isNotBlank()) {
      textState.edit { replace(start = 0, end = 0, withdrawnContent) }
    }
    // Truncate the server-side context to match the local withdrawal.
    val sessionKey: String? = activeSessionId
    val projectRoot: String? = project?.basePath
    if (sessionKey != null && projectRoot != null && messageId.isNotBlank()) {
      scope?.launch { apiClient.rewindSession(projectRoot, sessionKey, messageId) }
    }
    saveCurrentSession()
  }

  fun loadDebugMarkdown(content: String) {
    hasSentMessage = true
    val chatMsg = ChatMessage(
      role = "debug",
      modelName = message("gradum.debug.model.name"),
    )
    messages.add(chatMsg.appendEvent(ChatEvent.Response(content)))
    isSending = false
  }

  internal fun processPendingQueue() {
    if (pendingMessages.isNotEmpty()) {
      val next: PendingMessage = pendingMessages.removeFirst()
      val displayName: String = selectedModel?.name ?: ""
      val providerName: String = selectedModel?.provider ?: ""
      val serverLabel: String = selectedModel?.serverName ?: ""
      val messageId: String = UUID.randomUUID().toString()
      messages.add(
        ChatMessage(
          role = "user",
          attachments = next.attachments,
          content = next.content,
          messageId = messageId
        )
      )
      messages.add(
        ChatMessage(
          role = "assistant",
          provider = providerName,
          modelName = displayName,
          serverName = serverLabel
        )
      )
      isSending = true
      hasSentMessage = true
      isWaitingForResponse = true
      currentJob = scope?.launch {
        sendMessage(
          userMessage = next.content,
          next.attachments,
          contextPath = "",
          messageId = messageId
        )
      }
    }
  }

  suspend fun loadModels() {
    try {
      val json: String = apiClient.getModels()
      val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(json)
      applyModelList(newModels = response.models)

    } catch (loadException: Exception) {
      if (loadException is CancellationException) throw loadException
      log.warn("Failed to load models from ${apiClient.baseUrl}", loadException)
      models.clear(); modelsLoaded = false
    }
  }

  private fun applyModelList(newModels: List<ModelInfo>) {
    val autoFilter: Boolean = ProviderSettings.getInstance().snapshot.ollamaAutoFilter
    val healthyModels: List<ModelInfo> =
      if (autoFilter) newModels.filter { it.available }
      else newModels

    if (healthyModels == models) {
      modelsLoaded = true
      return
    }

    models.clear()
    models.addAll(elements = healthyModels)
    modelsLoaded = true

    if (models.isEmpty()) {
      selectedModel = null
      pinnedModels.clear(); return
    }

    val selectedEntry: ModelInfo? = selectedModel
    when {
      selectedEntry == null -> selectedModel = models.first()
      models.none {
        selectedEntry.sameAs(other = it)
      } -> selectedModel = models.first()
    }

    pinnedModels.removeAll { pinned: ModelInfo ->
      models.none {
        pinned.sameAs(other = it)
      }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun startModelPolling(autoDetect: Boolean, pollIntervalMs: Long, scope: CoroutineScope) {
    stopModelPolling()
    probeRefreshJob = scope.launch {
      ProviderCoordinator.probeSucceeded.collect {
        log.info("Provider probe succeeded, refreshing models")
        loadModels()
      }
    }
    if (!autoDetect) {
      log.info("Auto-detect disabled, skipping periodic model polling")
      return
    }
    pollingJob = scope.launch {
      tickerFlow(pollIntervalMs)
        .flatMapLatest { fetchModelsOnce() }
        .catch { exception: Throwable ->
          log.warn("Model polling stream error: ${exception.message}", exception)
        }
        .collect { json: String ->
          runCatching {
            val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(json)
            val before: List<ModelInfo> = models.toList()
            applyModelList(newModels = response.models)
            if (before != models.toList())
              log.info("Model state updated: ${models.size} models")
          }.onFailure { exception: Throwable ->
            log.debug("Failed to decode /models response, skipping this tick", exception)
          }
        }
    }
  }

  private fun tickerFlow(intervalMs: Long): Flow<Unit> = flow {
    while (currentCoroutineContext().isActive) {
      delay(duration = intervalMs.milliseconds)
      emit(value = Unit)
    }
  }

  private fun fetchModelsOnce(): Flow<String> = flow {
    val json: String = try {
      withContext(Dispatchers.IO) { apiClient.getModels() }
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: Exception) {
      log.debug("Polling /models failed: ${exception.message}"); return@flow
    }
    emit(value = json)
  }

  fun stopModelPolling() {
    val pollingActiveJob: Job? = pollingJob; pollingJob = null
    if (pollingActiveJob != null) {
      try {
        pollingActiveJob.cancel(cause = CancellationException("Gradum: stop model polling"))
      } catch (throwable: Throwable) {
        log.warn("Failed to cancel polling job", throwable)
      }
    }
    val probeRefreshActiveJob: Job? = probeRefreshJob; probeRefreshJob = null
    if (probeRefreshActiveJob != null) {
      try {
        probeRefreshActiveJob.cancel(cause = CancellationException("Gradum: stop probe refresh job"))
      } catch (throwable: Throwable) {
        log.warn("Failed to cancel probe refresh job", throwable)
      }
    }
  }

  suspend fun stopSession() {
    cleanupSubAgent()
    val activeJob: Job? = currentJob
    currentJob = null

    if (activeJob != null) {
      try {
        activeJob.cancel(cause = CancellationException("Gradum: stop session"))
      } catch (throwable: Throwable) {
        log.warn("Failed to cancel current job on stopSession", throwable)
      }
    }
    val currentSessionId: String? = sessionId
    if (currentSessionId != null) {
      try {
        apiClient.stopSession(currentSessionId)
      } catch (cancelError: Exception) {
        log.warn("Failed to send stop request for session $currentSessionId", cancelError)
      }
      sessionId = null
    }
    clearSendState()
    processPendingQueue()
  }

  companion object {
    internal val jsonFormat: Json = Json { ignoreUnknownKeys = true }
    const val MAX_ATTACHMENTS: Int = PluginConfig.MAX_ATTACHMENTS
    const val MIN_MERGE_SESSIONS: Int = PluginConfig.MIN_MERGE_SESSIONS
    internal const val MIN_SENDING_MS: Long = PluginConfig.MIN_SENDING_MS
    internal const val CONNECT_BACKOFF_MS: Long = PluginConfig.CONNECT_BACKOFF_MS
    private const val MAX_PENDING_MESSAGES: Int = PluginConfig.MAX_PENDING_MESSAGES
    internal const val MAX_CONNECT_ATTEMPTS: Int = PluginConfig.MAX_CONNECT_ATTEMPTS

    private val FOCUS_FILE_PATTERN: Regex = Regex(pattern = "@focus")
    private val FILE_REF_PATTERN: Regex = Regex(pattern = """@file:(\S+)""")

    fun resolveInlineTags(
      text: String, focusedFilePath: String, openFiles: List<VirtualFile>
    ): Pair<String, Boolean> {
      val hasFocusTag: Boolean = focusedFilePath.isNotEmpty()
        && FOCUS_FILE_PATTERN.containsMatchIn(input = text)
      val hasFileReference: Boolean = FILE_REF_PATTERN.containsMatchIn(input = text)
      if (!hasFocusTag && !hasFileReference) return text to false

      val resultBuffer = StringBuilder(text)
      var wasReplaced = false

      if (hasFocusTag) {
        val resolvedText: String = resultBuffer.replace(
          regex = FOCUS_FILE_PATTERN,
          replacement = "<Context path=\"$focusedFilePath\"/>"
        )
        resultBuffer.clear()
        resultBuffer.append(resolvedText)
        wasReplaced = true
      }
      if (hasFileReference) {
        FILE_REF_PATTERN.findAll(input = resultBuffer)
          .toList()
          .reversed()
          .forEach { match: MatchResult ->
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
