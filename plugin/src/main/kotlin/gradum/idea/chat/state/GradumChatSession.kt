/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSession.kt  2026-06-29 19:53:42 Changed by gwy
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
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.model.*
import gradum.idea.chat.state.GradumChatSession.Companion.POLL_INTERVAL_MS
import gradum.idea.chat.ui.chat.errorDetailText
import gradum.idea.chat.ui.chat.friendlyErrorMessage
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.AttachedFile
import gradum.idea.editor.PendingMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Raw response from the `/models` endpoint, containing the list of discovered LLM models.
 */
@Serializable
private data class ModelsListResponse(val models: List<ModelInfo>)

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
    private val eventJson: Json = Json { ignoreUnknownKeys = true }

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

    /** The currently selected permission level (read-only or full). */
    var selectedPermission: String = message("gradum.read")

    /**
     * Tool surface for the current session. Local models perform better with
     * a smaller tool list, and read-only sessions cannot accidentally mutate
     * the project.
     *
     * Allowed values (sent verbatim to the server):
     * - `"write"` (default) — every Skill is exposed.
     * - `"read_only"` — only `read_file`, `explore_project`, `run_cmd`.
     *
     * Any other value is treated as `"write"` by the server.
     */
    var toolMode: String by mutableStateOf("write")

    /**
     * Which system prompt the server should load. `"auto"` (default) lets
     * the server pick based on the model/provider: cloud-style prompt for
     * hosted frontier models, terse rule-only prompt for small local ones.
     *
     * Allowed values (sent verbatim to the server):
     * - `"auto"` (default) — server picks based on provider
     * - `"cloud"` — verbose, philosophy-rich prompt for strong models
     * - `"local"` — terse, rule-only prompt for small models
     */
    var promptVariant: String by mutableStateOf("auto")

    /** HTTP client for communicating with the Gradum backend server. */
    val apiClient: GradumApiClient = GradumApiClient()

    /** All discovered LLM models from nearby local servers. */
    val models: SnapshotStateList<ModelInfo> = mutableStateListOf()

    /** Models the user has pinned for quick access. */
    val pinnedModels: SnapshotStateList<ModelInfo> = mutableStateListOf()

    /** The currently selected model, or `null` when in auto-select mode. */
    var selectedModel: ModelInfo? by mutableStateOf(null)

    /** Whether the model selector is in "auto-select" mode (first available model). */
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

    /** Background job for periodic model polling. */
    private var pollingJob: Job? = null

    /** Whether context has already been loaded for this session. */
    var contextLoaded: Boolean = false
        private set

    /**
     * Resets the entire session to its initial state.
     *
     * Clears all messages, attachments, pending items, and the input field.
     * Called when the user clicks "New Chat" or when the session needs to be
     * discarded and started fresh.
     */
    fun reset() {
        hasSentMessage = false
        isSending = false
        currentJob?.cancel()
        currentJob = null
        sessionId = null
        contextLoaded = false
        messages.clear()
        attachedFiles.clear()
        pendingMessages.clear()
        textState.edit { delete(0, length) }
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
            applyModelList(response.models)
        } catch (exception: Exception) {
            log.warn("Failed to load models from ${apiClient.baseUrl}", exception)
            models.clear(); modelsLoaded = false
        }
    }

    private fun applyModelList(newModels: List<ModelInfo>) {
        models.clear(); models.addAll(newModels); modelsLoaded = true

        val current = selectedModel
        if (current != null) {
            if (models.none { it.name == current.name && it.serverName == current.serverName }) {
                selectedModel = null; isAutoSelected = true
            }
        } else if (models.isNotEmpty()) {
            selectedModel = models.first()
        }

        pinnedModels.removeAll { pinned ->
            models.none { it.name == pinned.name && it.serverName == pinned.serverName }
        }
    }

    /**
     * Starts background polling for model availability.
     *
     * Periodically queries the server for available models and updates the
     * model list if changes are detected. This allows the plugin to discover
     * new LLM servers (e.g., Ollama) that start after the plugin is loaded.
     *
     * Polling runs on [Dispatchers.IO] to avoid blocking the UI thread.
     * The polling interval is [POLL_INTERVAL_MS] milliseconds.
     *
     * @param scope The coroutine scope to launch the polling job in.
     */
    fun startModelPolling(scope: CoroutineScope) {
        stopModelPolling()

        pollingJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS.milliseconds)
                try {
                    val json: String = withContext(Dispatchers.IO) { apiClient.getModels() }
                    val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(json)
                    val newModels: List<ModelInfo> = response.models

                    // Only update if the model list actually changed.
                    if (newModels.size != models.size ||
                        newModels.map { it.name }.toSet() != models.map { it.name }.toSet()
                    ) {
                        applyModelList(newModels)
                        log.info("Model list updated: ${newModels.size} models discovered")
                    }
                } catch (_: Exception) {
                    // Silently ignore polling failures — the server may be temporarily
                    // unreachable. The next poll cycle will retry.
                }
            }
        }
    }

    /**
     * Stops the background model polling job if it is running.
     */
    fun stopModelPolling() {
        pollingJob?.cancel(); pollingJob = null
    }

    /**
     * Stops the current active session.
     *
     * Cancels the local coroutine Job and sends a stop request to the server
     * to abort the agent's execution.
     */
    suspend fun stopSession() {
        currentJob?.cancel(); currentJob = null

        val currentSessionId = sessionId
        if (currentSessionId != null) {
            try {
                apiClient.stopSession(currentSessionId)
            } catch (exception: Exception) {
                log.warn("Failed to send stop request for session $currentSessionId", exception)
            }
            sessionId = null
        }

        isSending = false; isWaitingForResponse = false; resetThinkingState(); processPendingQueue()
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
        userMessage: String,
        attachments: List<AttachedContext> = emptyList(),
        contextPath: String = ""
    ) {
        val modelConfig: Map<String, String> = buildModelConfig()
        val attachmentPaths: List<String> = attachments.filterIsInstance<AttachedFile>().map { it.file.path }
        val prefix: String = buildString {
            if (contextPath.isNotEmpty()) append("<Context path=\"$contextPath\"/>")
            if (attachmentPaths.isNotEmpty()) append("<Attachments paths=\"${attachmentPaths.joinToString(", ")}\"/>")
        }
        val messageWithHint = "${prefix}${userMessage} Don't use Markdown tables."

        // Validate server connectivity and model availability before sending.
        val validationStart: Long = System.currentTimeMillis()
        try {
            val modelsJson: String = apiClient.getModels()
            val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(modelsJson)
            val currentModel: ModelInfo? = selectedModel
            if (currentModel != null && response.models.none { it.name == currentModel.name }) {
                val assistantIndex: Int = messages.lastIndex
                if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                    messages[assistantIndex] = messages[assistantIndex].appendEvent(
                        ChatEvent.Error(
                            "Model ${currentModel.name} is no longer available", code = ErrorCode.CLIENT_ERROR.code
                        )
                    )
                }
                isSending = false; isWaitingForResponse = false; processPendingQueue()

                return
            }
        } catch (exception: Exception) {
            log.warn("Model validation failed for ${apiClient.baseUrl}", exception)
            val assistantIndex: Int = messages.lastIndex
            if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                messages[assistantIndex] = messages[assistantIndex].appendEvent(
                    ChatEvent.Error(
                        "Cannot reach server at ${apiClient.baseUrl}", code = ErrorCode.CLIENT_ERROR.code
                    )
                )
            }
            isSending = false; isWaitingForResponse = false; processPendingQueue()

            return
        }

        // Ensure the "Sending" animation is visible for at least MIN_SENDING_MS.
        val elapsed: Long = System.currentTimeMillis() - validationStart
        if (elapsed < MIN_SENDING_MS) delay((MIN_SENDING_MS - elapsed).milliseconds)

        try {
            val shouldLoadContext: Boolean = !contextLoaded
            apiClient.sendMessage(
                message = messageWithHint,
                model = selectedModel?.name,
                config = modelConfig,
                loadContext = shouldLoadContext,
                toolMode = toolMode,
                projectRoot = project?.basePath,
            ).catch { exception ->
                log.warn("Failed to send message to ${apiClient.baseUrl}", exception)
                val assistantIndex: Int = messages.lastIndex
                if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                    messages[assistantIndex] = messages[assistantIndex].appendEvent(
                        ChatEvent.Error(
                            exception.message ?: "Connection failed", code = ErrorCode.CLIENT_ERROR.code
                        )
                    )
                }
                isSending = false; processPendingQueue()
            }.collect { ndjsonLine ->
                try {
                    val event: JsonObject = eventJson.parseToJsonElement(ndjsonLine) as JsonObject
                    val type: String = event["type"]?.jsonPrimitive?.content ?: return@collect
                    val data: JsonObject? = event["data"]?.jsonObject

                    when (type) {
                        "session_start" -> {
                            sessionId = event["sessionId"]?.jsonPrimitive?.content
                            contextLoaded = true
                        }

                        "response" -> {
                            isWaitingForResponse = false; handleResponseEvent(data)
                        }

                        "thinking" -> {
                            isWaitingForResponse = false; handleThinkingEvent(data)
                        }

                        "tool_call" -> handleToolCallEvent(data)
                        "error" -> handleErrorEvent(data)
                        "session_end" -> {
                            isSending = false
                            isWaitingForResponse = false
                            currentJob = null
                            sessionId = null
                            resetThinkingState()
                            processPendingQueue()
                        }
                    }
                } catch (exception: Exception) {
                    log.warn("Failed to parse NDJSON event: $ndjsonLine", exception)
                }
            }
        } catch (exception: Exception) {
            log.warn("Streaming interrupted for ${apiClient.baseUrl}", exception)
            val assistantIndex: Int = messages.lastIndex
            if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                messages[assistantIndex] = messages[assistantIndex].appendEvent(
                    ChatEvent.Error(
                        exception.message ?: "Streaming interrupted", code = ErrorCode.CLIENT_ERROR.code
                    )
                )
            }
            isSending = false; isWaitingForResponse = false; processPendingQueue()
        }
    }

    /**
     * Handles a `response` event by appending the LLM's text content to the
     * current assistant message.
     *
     * @param data The event data object containing a `content` field.
     */
    private fun handleResponseEvent(data: JsonObject?) {
        val content: String = data?.get("content")?.jsonPrimitive?.content ?: return
        if (content.isBlank()) return

        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
            messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.Response(content))
        }
    }

    /**
     * Handles a `thinking` event by accumulating thinking content and tracking duration.
     *
     * @param data The event data object containing a `content` field.
     */
    private fun handleThinkingEvent(data: JsonObject?) {
        val content: String = data?.get("content")?.jsonPrimitive?.content ?: return

        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
            messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.Thinking(content))
        }
    }

    // Resets thinking state when a new response starts.
    private fun resetThinkingState() {
        // No longer needed - thinking state is managed via events list
    }

    /**
     * Handles a `tool_call` event by adding a tool invocation to
     * the current assistant message.
     *
     * @param data The event data object containing tool call information.
     */
    private fun handleToolCallEvent(data: JsonObject?) {
        try {
            val toolName: String = data?.get("tool")?.jsonPrimitive?.content ?: "unknown"
            val alias: String = data?.get("alias")?.jsonPrimitive?.content ?: toolName
            val toolCallId: String = data?.get("toolCallId")?.jsonPrimitive?.content ?: ""
            val success: Boolean = data?.get("success")?.toString()?.trim('"')?.toBooleanStrictOrNull() ?: true
            val result: String = data?.get("result")?.toString() ?: ""
            val arguments: Map<String, Any> = parseArguments(data?.get("arguments")?.jsonObject)

            val toolCall = ToolCallInfo(
                toolName = toolName,
                alias = alias,
                toolCallId = toolCallId,
                success = success,
                result = result,
                arguments = arguments
            )

            val assistantIndex: Int = messages.lastIndex
            if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                messages[assistantIndex] = messages[assistantIndex].appendEvent(ChatEvent.ToolCall(toolCall))
            }
        } catch (exception: Exception) {
            log.warn("Failed to parse tool_call event", exception)
        }
    }

    /**
     * Handles an `error` event by appending an error message to the current
     * assistant message.
     *
     * @param data The event data object containing a `message` field with the error description.
     */
    private fun handleErrorEvent(data: JsonObject?) {
        val rawMessage: String = data?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
        val code: String = data?.get("code")?.jsonPrimitive?.content ?: ""
        val tool: String = data?.get("tool")?.jsonPrimitive?.content ?: ""
        val assistantIndex: Int = messages.lastIndex

        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
            val updated = messages[assistantIndex].updateLastError(
                friendlyErrorMessage(code),
                errorDetailText(code, rawMessage, tool)
            )
            if (updated !== messages[assistantIndex]) {
                messages[assistantIndex] = updated
            } else {
                messages[assistantIndex] = messages[assistantIndex].appendEvent(
                    ChatEvent.Error(rawMessage, code, tool)
                )
            }
        }
    }

    /**
     * Processes the next message in the pending queue, if any.
     *
     * When the assistant finishes responding to one message, this method
     * dequeues the next pending message and starts a new send cycle.
     */
    private fun processPendingQueue() {
        if (pendingMessages.isNotEmpty()) {
            val next: PendingMessage = pendingMessages.removeFirst()
            val displayName: String = selectedModel?.name ?: "Auto"
            val providerName: String = selectedModel?.provider ?: ""
            val serverLabel: String = selectedModel?.serverName ?: ""
            messages.add(ChatMessage(role = "user", content = next.content, attachments = next.attachments))
            messages.add(ChatMessage(role = "assistant", content = "", modelName = displayName, provider = providerName, serverName = serverLabel))

            hasSentMessage = true; isSending = true; isWaitingForResponse = true
            currentJob = scope?.launch { sendMessage(next.content, next.attachments, "") }
        }
    }

    /**
     * Builds the configuration map to send with the request to the server.
     *
     * Currently only sets the `provider` when in auto-select mode.
     * Additional configuration options (temperature, context window, etc.)
     * may be added in the future.
     *
     * @return A map of configuration key-value pairs, or an empty map if no overrides are needed.
     */
    private fun buildModelConfig(): Map<String, String> {
        val config: MutableMap<String, String> = mutableMapOf()
        if (isAutoSelected) {
            config["provider"] = "ollama"
        } else {
            val model = selectedModel
            if (model != null) {
                if (model.provider.isNotBlank()) config["provider"] = model.provider
                if (model.server.isNotBlank()) config["baseUrl"] = model.server
            }
        }
        return config
    }

    private fun parseArguments(jsonObject: JsonObject?): Map<String, Any> {
        if (jsonObject == null) return emptyMap()
        return jsonObject.mapValues { (_, value) ->
            when (value) {
                is JsonObject -> value.toString()
                is JsonArray -> value.toString()
                else -> {
                    val primitive = value.jsonPrimitive
                    when {
                        primitive.isString -> primitive.content
                        primitive.booleanOrNull != null -> primitive.boolean
                        primitive.intOrNull != null -> primitive.int
                        primitive.longOrNull != null -> primitive.long
                        primitive.doubleOrNull != null -> primitive.double
                        else -> primitive.content
                    }
                }
            }
        }
    }

    companion object {
        private val jsonFormat: Json = Json { ignoreUnknownKeys = true }
        const val MAX_ATTACHMENTS: Int = 5
        const val MAX_PENDING_MESSAGES: Int = 2

        /** Minimum milliseconds to display the "Sending" animation before the request fires. */
        const val MIN_SENDING_MS: Long = 400

        /** Interval between model polling requests in milliseconds. */
        const val POLL_INTERVAL_MS: Long = 5_000

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
            text: String,
            focusedFilePath: String,
            openFiles: List<VirtualFile>
        ): Pair<String, Boolean> {
            val hasFocusTag = focusedFilePath.isNotEmpty() && FOCUS_FILE_PATTERN.containsMatchIn(text)
            val hasFileRef = FILE_REF_PATTERN.containsMatchIn(text)
            if (!hasFocusTag && !hasFileRef) return text to false

            val result = StringBuilder(text);
            var replaced = false

            if (hasFocusTag) {
                val resolved = result.replace(FOCUS_FILE_PATTERN, "<Context path=\"$focusedFilePath\"/>")
                result.clear(); result.append(resolved); replaced = true
            }

            if (hasFileRef) {
                FILE_REF_PATTERN.findAll(result).toList().reversed().forEach { match ->
                    val fileName: String = match.groupValues[1]
                    val matchedFile: VirtualFile? = openFiles.find { it.name == fileName }
                    if (matchedFile != null) {
                        result.replace(
                            match.range.first,
                            match.range.last + 1,
                            "<Attachments paths=\"${matchedFile.path}\"/>"
                        )
                        replaced = true
                    }
                }
            }

            return result.toString() to replaced
        }
    }
}
