package gradum.idea.chat.model

/**
 * Codes for the errors this plugin raises itself. Codes sent by the server are
 * opaque strings and are only translated for display (see `friendlyErrorMessage`),
 * so the plugin keeps no mirror of the server's full enum; this object names
 * just the codes the plugin can emit.
 */
object ChatErrorCode {
  const val CLIENT_ERROR: String = "CLIENT_ERROR"
  const val LLM_UPSTREAM_ERROR: String = "LLM_UPSTREAM_ERROR"
  const val LLM_AUTH_ERROR: String = "LLM_AUTH_ERROR"
  const val INTERRUPTED: String = "INTERRUPTED"
}
