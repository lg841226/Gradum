package gradum.idea.editor

data class PendingMessage(
  val content: String,
  val attachments: List<AttachedContext>
)
