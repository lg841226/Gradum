/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.editor

data class PendingMessage(
  val content: String,
  val attachments: List<AttachedContext>
)
