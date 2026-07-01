/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelInfo.kt  2026-06-30 23:35:47 Changed by gwy
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
    val attachment: Boolean = false
)
