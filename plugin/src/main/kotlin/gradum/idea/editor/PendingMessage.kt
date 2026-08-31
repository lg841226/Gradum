/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PendingMessage.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.idea.editor

data class PendingMessage(
  val content: String,
  val attachments: List<AttachedContext>
)
