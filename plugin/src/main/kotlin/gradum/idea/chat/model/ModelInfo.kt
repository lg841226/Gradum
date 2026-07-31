/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelInfo.kt  2026-07-31 15:54:30 Changed by gwy
 */

package gradum.idea.chat.model

import kotlinx.serialization.Serializable

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
   * session-scanState boundary; nothing in the renderer reads it.
   *
   * Defaults to `true` so a `ModelInfo` constructed from a
   * partial / older payload (e.g. cached in the IDE) still
   * behaves as a usable model until the next `/models` poll
   * proves otherwise.
   */
  val available: Boolean = true
)
