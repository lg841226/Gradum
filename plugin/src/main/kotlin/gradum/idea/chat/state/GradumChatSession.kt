/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSession.kt  2026-06-26 23:55:00 Changed by gwy
 */

package gradum.idea.chat.state

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import gradum.idea.bundle.GradumBundle.message
import gradum.idea.chat.api.GradumApiClient
import gradum.idea.chat.model.ChatMessage
import gradum.idea.chat.model.ModelInfo
import gradum.idea.editor.AttachedContext
import gradum.idea.editor.PendingMessage
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
    val pinnedModels: SnapshotStateList<ModelInfo> = mutableStateListOf()
    var selectedModel: ModelInfo? by mutableStateOf(null)
    var isAutoSelected: Boolean by mutableStateOf(false)
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
        }
    }

    companion object {
        private val jsonFormat: Json = Json { ignoreUnknownKeys = true }
        const val MAX_ATTACHMENTS: Int = 5
        const val MAX_PENDING_MESSAGES: Int = 2
    }
}
