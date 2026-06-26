/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSession.kt  2026-06-26 00:00:00 Changed by gwy
 */

package gradum.idea

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.intellij.openapi.components.Service
import gradum.idea.GradumBundle.message

/**
 * Project-level state holder for the Gradum chat tool window.
 *
 * The IntelliJ Platform **disposes a tool window's content** every time the
 * user collapses it via the sidebar logo; [GradumToolWindowFactory.createToolWindowContent]
 * is then called again to rebuild it from scratch on the next expand.
 * Holding chat state in Compose `remember { ... }` therefore loses the
 * entire conversation on every collapse, which is what made the
 * "click logo → click logo → chat gone" bug visible.
 *
 * This service lives for the lifetime of the project, so its state
 * survives that round-trip without any explicit persistence. It is
 * intentionally **not** a `PersistentStateComponent` — chat history is
 * session-scoped, and the upcoming persistence feature will live in a
 * separate component so this one stays simple.
 */
@Service(Service.Level.PROJECT)
class GradumChatSession {

    /** Draft text in the input area. */
    val textState: TextFieldState = TextFieldState()

    /** All sent messages, oldest first. */
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()

    /** Files attached to the next message (cleared on send). */
    val attachedFiles: SnapshotStateList<AttachedContext> = mutableStateListOf()

    /** Messages waiting to be sent while isSending is true. */
    val pendingMessages: SnapshotStateList<PendingMessage> = mutableStateListOf()

    /** Toggles the welcome page ↔ chat page. */
    var hasSentMessage: Boolean by mutableStateOf(false)

    /** Drives the send/stop icon and the streaming indicator. */
    var isSending: Boolean by mutableStateOf(false)

    /** Whether the input area currently holds IME focus. */
    var isFocused: Boolean by mutableStateOf(false)

    /** Visibility of the permission dropdown. */
    var isMenuVisible: Boolean by mutableStateOf(false)

    /** Visibility of the editor-context summary row. */
    var isExpanded: Boolean by mutableStateOf(false)

    /** Visibility of the add-file popup. */
    var showAddMenu: Boolean by mutableStateOf(false)

    /** Currently selected permission label ("Read-only" / "Full"). */
    var selectedPermission: String = message("gradum.readonly")

    /** Mirrors the UI's attachment cap; consumed by both UI and callbacks. */
    val isAttachmentLimitReached: Boolean
        get() = attachedFiles.size >= MAX_ATTACHMENTS

    /** Whether the pending message queue is full. */
    val isPendingQueueFull: Boolean
        get() = pendingMessages.size >= MAX_PENDING_MESSAGES

    /**
     * Resets the session to the welcome page, dropping all messages,
     * attachments, and draft text. Used by the "New Chat" title action.
     */
    fun reset() {
        hasSentMessage = false
        isSending = false
        messages.clear()
        attachedFiles.clear()
        pendingMessages.clear()
        textState.edit { delete(0, length) }
    }

    companion object {
        /**
         * Maximum number of attachments a single user message may carry.
         * Mirrors the cap previously inlined in [GradumToolWindowFactory].
         */
        const val MAX_ATTACHMENTS: Int = 5

        /** Maximum number of messages that can be queued while waiting for a response. */
        const val MAX_PENDING_MESSAGES: Int = 2
    }
}
