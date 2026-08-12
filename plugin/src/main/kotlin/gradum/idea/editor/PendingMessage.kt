/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PendingMessage.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.idea.editor

data class PendingMessage(
  val content: String,
  val attachments: List<AttachedContext>
)
