/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelInfo.kt  2026-07-01 21:53:11 Changed by gwy
 */

package gradum.idea.chat.model

import kotlinx.serialization.Serializable

/**
 * Wire-only mirror of the server-side `UnavailableReason` enum.
 *
 * The server sends the enum name verbatim in the `/models` JSON,
 * and kotlinx-serialization decodes it back into the matching
 * value here. The names are wire-stable — do not rename a value
 * without also renaming it on the server and handling backwards
 * compatibility at the boundary.
 *
 * The plugin UI no longer surfaces unavailable models (they are
 * filtered out in
 * `gradum.idea.chat.state.GradumChatSession.applyModelList`), so
 * this enum is currently only used as a deserialization target.
 * It is kept here so a future re-introduction of the disabled-row
 * view does not require changing the wire format.
 */
@Serializable
enum class UnavailableReason {
    AUTH,
    QUOTA_EXCEEDED,
    RATE_LIMIT,
    NETWORK,
    OTHER,
}

@Serializable
data class ModelInfo(
    val name: String,
    val serverName: String = "",
    val provider: String = "",
    val server: String = "",
    val contextLimit: Int = 0,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val openWeights: Boolean = false,
    val attachment: Boolean = false,
    /**
     * `true` while the model is reachable. The server's
     * `ModelDiscovery` flips this to `false` when the most recent
     * health check failed (stale Ollama signin, exhausted quota,
     * 5xx upstream, or a network blip).
     *
     * The plugin UI filters out `available = false` entries in
     * `GradumChatSession.applyModelList` before they ever reach
     * the popup, so this flag is only meaningful at the
     * session-state boundary; nothing in the renderer reads it.
     *
     * Defaults to `true` so a `ModelInfo` constructed from a
     * partial / older payload (e.g. cached in the IDE) still
     * behaves as a usable model until the next `/models` poll
     * proves otherwise.
     */
    val available: Boolean = true,
    /**
     * When [available] is `false`, the upstream reason the model
     * is unreachable. `null` when [available] is `true`.
     *
     * Currently a wire-only field: it is preserved here so
     * kotlinx-serialization can decode the server payload without
     * the entire `/models` array failing, but the plugin UI does
     * not render it (unavailable models are dropped from the
     * selector before the user sees them). Kept on the type for
     * future re-introduction of the disabled-row view.
     */
    val unavailableReason: UnavailableReason? = null
)
