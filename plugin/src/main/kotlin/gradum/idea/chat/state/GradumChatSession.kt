/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSession.kt  2026-06-27 19:38:28 Changed by gwy
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
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.model.ChatEvent
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ModelInfo
import gradum.idea.chat.model.ToolCallInfo
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.PendingMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

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
    var selectedPermission: String = message("gradum.readonly")

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
            models.clear()
            models.addAll(response.models)
            modelsLoaded = true
            if (selectedModel == null && models.isNotEmpty()) {
                selectedModel = models.first()
            }
            pinnedModels.removeAll { pinned ->
                models.none { it.name == pinned.name && it.serverName == pinned.serverName }
            }
        } catch (exception: Exception) {
            log.warn("Failed to load models from ${apiClient.baseUrl}", exception)
            models.clear()
            modelsLoaded = false
        }
    }

    /**
     * Stops the current active session.
     *
     * Cancels the local coroutine Job and sends a stop request to the server
     * to abort the agent's execution.
     */
    suspend fun stopSession() {
        currentJob?.cancel()
        currentJob = null

        val currentSessionId = sessionId
        if (currentSessionId != null) {
            try {
                apiClient.stopSession(currentSessionId)
            } catch (exception: Exception) {
                log.warn("Failed to send stop request for session $currentSessionId", exception)
            }
            sessionId = null
        }

        isSending = false
        isWaitingForResponse = false
        resetThinkingState()
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
    suspend fun sendMessage(userMessage: String, projectDir: String? = null) {
        val modelConfig: Map<String, String> = buildModelConfig()
        val messageWithHint = "$userMessage Do not use Markdown tables."

        // Validate server connectivity and model availability before sending.
        val validationStart: Long = System.currentTimeMillis()
        try {
            val modelsJson: String = apiClient.getModels()
            val response: ModelsListResponse = jsonFormat.decodeFromString<ModelsListResponse>(modelsJson)
            val currentModel: ModelInfo? = selectedModel
            if (currentModel != null && response.models.none { it.name == currentModel.name }) {
                val assistantIndex: Int = messages.lastIndex
                if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                    messages[assistantIndex] = messages[assistantIndex].copy(
                        events = messages[assistantIndex].events + ChatEvent.Error("Model ${currentModel.name} is no longer available")
                    )
                }
                isSending = false
                isWaitingForResponse = false
                processPendingQueue()
                return
            }
        } catch (exception: Exception) {
            log.warn("Model validation failed for ${apiClient.baseUrl}", exception)
            val assistantIndex: Int = messages.lastIndex
            if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                messages[assistantIndex] = messages[assistantIndex].copy(
                    events = messages[assistantIndex].events + ChatEvent.Error("Cannot reach server at ${apiClient.baseUrl}")
                )
            }
            isSending = false
            isWaitingForResponse = false
            processPendingQueue()
            return
        }

        // Ensure the "Sending" animation is visible for at least MIN_SENDING_MS.
        val elapsed: Long = System.currentTimeMillis() - validationStart
        if (elapsed < MIN_SENDING_MS) {
            delay(MIN_SENDING_MS - elapsed)
        }

        apiClient.sendMessage(
            message = messageWithHint,
            model = selectedModel?.name,
            config = modelConfig,
            loadContext = true,
            projectDir = projectDir
        ).catch { exception ->
            log.warn("Failed to send message to ${apiClient.baseUrl}", exception)
            val assistantIndex: Int = messages.lastIndex
            if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
                messages[assistantIndex] = messages[assistantIndex].copy(
                    events = messages[assistantIndex].events + ChatEvent.Error(exception.message ?: "Connection failed")
                )
            }
            isSending = false
            processPendingQueue()
        }.collect { ndjsonLine ->
            try {
                val event: JsonObject = eventJson.parseToJsonElement(ndjsonLine) as JsonObject
                val type: String = event["type"]?.jsonPrimitive?.content ?: return@collect
                val data: JsonObject? = event["data"]?.jsonObject

                when (type) {
                    "session_start" -> {
                        sessionId = event["sessionId"]?.jsonPrimitive?.content
                    }

                    "response" -> {
                        isWaitingForResponse = false
                        handleResponseEvent(data)
                    }

                    "thinking" -> {
                        isWaitingForResponse = false
                        handleThinkingEvent(data)
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
            messages[assistantIndex] = messages[assistantIndex].copy(
                events = messages[assistantIndex].events + ChatEvent.Response(content)
            )
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
            messages[assistantIndex] = messages[assistantIndex].copy(
                events = messages[assistantIndex].events + ChatEvent.Thinking(content)
            )
        }
    }

    /**
     * Resets thinking state when a new response starts.
     */
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
                messages[assistantIndex] = messages[assistantIndex].copy(
                    events = messages[assistantIndex].events + ChatEvent.ToolCall(toolCall)
                )
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
        val errorMessage: String = data?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
        val assistantIndex: Int = messages.lastIndex
        if (assistantIndex >= 0 && !messages[assistantIndex].isUserMessage) {
            messages[assistantIndex] = messages[assistantIndex].copy(
                events = messages[assistantIndex].events + ChatEvent.Error(errorMessage)
            )
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
            messages.add(ChatMessage(role = "user", content = next.content, attachments = next.attachments))
            messages.add(ChatMessage(role = "assistant", content = ""))
            hasSentMessage = true
            isSending = true
            isWaitingForResponse = true
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
        }
        return config
    }

    private fun parseArguments(jsonObject: JsonObject?): Map<String, Any> {
        if (jsonObject == null) return emptyMap()
        return jsonObject.mapValues { (_, value) ->
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

    companion object {
        private val jsonFormat: Json = Json { ignoreUnknownKeys = true }
        const val MAX_ATTACHMENTS: Int = 5
        const val MAX_PENDING_MESSAGES: Int = 2
        /** Minimum milliseconds to display the "Sending" animation before the request fires. */
        const val MIN_SENDING_MS: Long = 400
    }
}
