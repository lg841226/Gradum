/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelInfo.kt  2026-06-26 23:55:00 Changed by gwy
 */

package gradum.idea.chat.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelInfo(
    val name: String,
    val serverName: String = ""
)
