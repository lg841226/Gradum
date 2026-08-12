/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelInfo.kt  2026-08-12 12:38:25 Changed by gwy
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
   * `true` while the model is reachable. The server's `ModelDiscovery`
   * flips it to `false` on a failed health check; the plugin filters out
   * `available = false` entries in `GradumChatSession.applyModelList`.
   *
   * Defaults to `true` so a `ModelInfo` built from a partial/older payload
   * behaves as usable until the next `/models` poll proves otherwise.
   */
  val available: Boolean = true
) {
  /**
   * True when this entry identifies the same model as [other] — same
   * display name on the same server. Used everywhere the code needs a
   * stable key (pinning, selection preservation, toggle-off) without
   * repeating the dual-field comparison.
   */
  fun sameAs(other: ModelInfo): Boolean =
    name == other.name && serverName == other.serverName
}
