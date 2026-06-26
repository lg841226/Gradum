package gradum.idea

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.GradumBundle.message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ModelsListResponse(val models: List<ModelInfo>)

@Service(Service.Level.PROJECT)
class GradumChatSession {

    private val log: Logger = Logger.getInstance(GradumChatSession::class.java)

    val textState: TextFieldState = TextFieldState()
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    val attachedFiles: SnapshotStateList<AttachedContext> = mutableStateListOf()
    val pendingMessages: SnapshotStateList<PendingMessage> = mutableStateListOf()

    var hasSentMessage: Boolean by mutableStateOf(false)
    var isSending: Boolean by mutableStateOf(false)
    var isFocused: Boolean by mutableStateOf(false)
    var isMenuVisible: Boolean by mutableStateOf(false)
    var isExpanded: Boolean by mutableStateOf(false)
    var showAddMenu: Boolean by mutableStateOf(false)
    var selectedPermission: String = message("gradum.readonly")

    val apiClient: GradumApiClient = GradumApiClient()
    val models: SnapshotStateList<ModelInfo> = mutableStateListOf()
    var selectedModel: ModelInfo? by mutableStateOf(null)
    var modelsLoaded: Boolean by mutableStateOf(false)

    val isAttachmentLimitReached: Boolean
        get() = attachedFiles.size >= MAX_ATTACHMENTS
    val isPendingQueueFull: Boolean
        get() = pendingMessages.size >= MAX_PENDING_MESSAGES

    fun reset() {
        hasSentMessage = false
        isSending = false
        messages.clear()
        attachedFiles.clear()
        pendingMessages.clear()
        textState.edit { delete(0, length) }
    }

    suspend fun loadModels() {
        try {
            val json = apiClient.getModels()
            val response = jsonFormat.decodeFromString<ModelsListResponse>(json)
            models.clear()
            models.addAll(response.models)
            modelsLoaded = true
        } catch (e: Exception) {
            log.warn("Failed to load models from ${apiClient.baseUrl}", e)
        }
    }

    companion object {
        private val jsonFormat: Json = Json { ignoreUnknownKeys = true }
        const val MAX_ATTACHMENTS: Int = 5
        const val MAX_PENDING_MESSAGES: Int = 2
    }
}
